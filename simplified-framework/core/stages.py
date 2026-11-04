# -*- coding: utf-8 -*-
"""管道内置 stage：开箱即用的关卡（白名单/限流/Agent 处理/回复）。
加新能力 = 新增 stage 子类并 register，核心零改动。
"""
import time
from core.pipeline import Stage, StageResult
from core.message import Message
from provider.base import LLMMessage


class WhitelistStage(Stage):
    """白名单检查：仅允许指定 sender.id 通过；其余拦截。
    配置: ctx['whitelist'] = set of ids；为空=不启用（放行全部）。"""
    name = "whitelist"

    def handle(self, ctx: dict, msg: Message) -> StageResult:
        wl = ctx.get("whitelist") or set()
        if not wl:
            return StageResult.next()
        if msg.sender and msg.sender.id in wl:
            return StageResult.next()
        return StageResult.stop(response="⛔ 该会话不在白名单，已拦截", reason="whitelist_blocked")


class RateLimitStage(Stage):
    """限流：按会话 限制单位时间消息数。
    配置: ctx['rate_limit'] = (max_count, window_seconds)；默认 10次/5秒。"""
    name = "ratelimit"

    def __init__(self, priority: int = 90, default_limit: int = 10, window: int = 5):
        super().__init__(priority)
        self.default_limit = default_limit
        self.window = window
        self._hits = {}   # conv_id -> list[timestamp]

    def handle(self, ctx: dict, msg: Message) -> StageResult:
        limit, window = ctx.get("rate_limit") or (self.default_limit, self.window)
        conv = msg.conversation.id if msg.conversation else "default"
        now = time.time()
        ts_list = self._hits.setdefault(conv, [])
        # 清掉窗口外
        while ts_list and now - ts_list[0] > window:
            ts_list.pop(0)
        if len(ts_list) >= limit:
            return StageResult.stop(response=f"⛔ 消息太频繁，请 {window} 秒后再试", reason="rate_limited")
        ts_list.append(now)
        return StageResult.next()


class AgentStage(Stage):
    """核心处理：把消息交给 Agent 循环（模型+工具），产出最终答复。
    配置: ctx['agent'] = Agent 实例；ctx['history'] = 可选会话历史管理器。"""
    name = "agent"

    def __init__(self, priority: int = 50):
        super().__init__(priority)

    def handle(self, ctx: dict, msg: Message) -> StageResult:
        agent = ctx.get("agent")
        if agent is None:
            return StageResult.next(response="❌ Agent 未配置")
        # 构造消息列表：注入当前用户消息
        msgs = [LLMMessage("user", msg.content)]
        # 若有会话历史（session 记忆），可前置拼接：ctx['history'] 是 dict[conv_id] -> list[LLMMessage]
        history = ctx.get("history")
        if history:
            conv = msg.conversation.id if msg.conversation else "default"
            past = history.get(conv) or []
            msgs = past[-8:] + msgs  # 最近 8 条上下文
        role = msg.sender.role if msg.sender else "user"
        result = agent.run(msgs, sender_role=role, session_id=msg.session_id)
        # 记录本轮历史（供下次）
        if history:
            conv = msg.conversation.id if msg.conversation else "default"
            history.setdefault(conv, []).extend([LLMMessage("user", msg.content), LLMMessage("assistant", result["reply"])])
        msg.agent_result = result   # 附带完整 trace 供调试/前端
        return StageResult.next(response=result["reply"])


class ResultDecorateStage(Stage):
    """结果装饰：给回复加前缀（如回复来源标记/时间戳）。
    配置: ctx['decorate'] = lambda reply, msg: str"""
    name = "decorate"

    def __init__(self, priority: int = 20):
        super().__init__(priority)

    def handle(self, ctx: dict, msg: Message) -> StageResult:
        decorator = ctx.get("decorate")
        if not msg.response and decorator is None:
            return StageResult.next()
        if hasattr(msg, "response") and msg.response:
            if decorator:
                msg.response = decorator(msg.response, msg)
            return StageResult.next()
        return StageResult.next()


class RespondStage(Stage):
    """回复出口：把暂存的 response 标记为已消费（由平台统一发送）。
    若前面的 stage 已给出 response（拦截/限流），这里负责落地发送。"""
    name = "respond"

    def __init__(self, priority: int = 10):
        super().__init__(priority)

    def handle(self, ctx: dict, msg: Message) -> StageResult:
        # 回复内容已在 msg.response，交给平台发送（demo 里由 main 统一 send_result）
        if hasattr(msg, "response") and msg.response:
            return StageResult.stop(response=msg.response, reason="responded")
        return StageResult.next()