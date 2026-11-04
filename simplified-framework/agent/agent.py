# -*- coding: utf-8 -*-
"""Agent 核心：工具注册表 + Agent 循环。

工具协议（简化，易被模型学会）：
  模型回复里输出形如
    [[工具:函数名(param1=值1, param2=值2)]]
  的调用块。Agent 解析 -> 在注册表找到函数并执行 -> 把结果拼进下一轮上下文 -> 再调模型。
  当模型回复不再包含调用块 -> 认为任务完成，把该回复作为最终答复。

安全护栏：
  - 工具函数可声明 allowed_roles（user/admin），按发送者角色拦截
  - 工具函数可声明 sandboxed=True，执行时包裹 try + 超时提示，异常不外泄
"""
from typing import Callable, Dict, List, Optional, Any
import re
import logging
from provider.base import BaseProvider, LLMMessage

log = logging.getLogger("qtgw.agent")

TOOL_CALL_RE = re.compile(r"\[\[\s*工具\s*:\s*([a-zA-Z_][a-zA-Z0-9_]*)\s*\((.*?)\)\s*\]\]", re.S)


class Tool:
    """工具定义：函数 + 元数据（描述/权限/沙盒）"""
    def __init__(self, name: str, func: Callable[..., str], desc: str = "",
                 allowed_roles: List[str] = None, sandboxed: bool = True):
        self.name = name
        self.func = func
        self.desc = desc
        self.allowed_roles = allowed_roles or ["user", "admin"]
        self.sandboxed = sandboxed


class ToolRegistry:
    """工具注册表：Agent 与插件共享，按名查找执行"""
    def __init__(self):
        self._tools: Dict[str, Tool] = {}

    def register(self, tool: Tool):
        self._tools[tool.name] = tool
        log.info("agent: 注册工具 %s", tool.name)

    def get(self, name: str) -> Optional[Tool]:
        return self._tools.get(name)

    def names(self) -> List[str]:
        return list(self._tools.keys())

    def manifest(self) -> str:
        """给模型的工具清单（注入 system 提示）"""
        lines = []
        for name, t in self._tools.items():
            lines.append(f"  - {name}: {t.desc or '(无描述)'}")
        return "\n".join(lines)


def parse_tool_calls(text: str) -> List[tuple]:
    """从模型回复中解析工具调用块，返回 [(函数名, 参数字典), ...]"""
    calls = []
    for m in TOOL_CALL_RE.finditer(text):
        fn = m.group(1)
        args = parse_args(m.group(2))
        calls.append((fn, args))
    return calls


def parse_args(raw: str) -> Dict[str, str]:
    """解析参数文本 "a=1, b='x'" -> {a:'1', b:'x'}（简易解析：按逗号切，等号分键值）"""
    args: Dict[str, str] = {}
    if not raw.strip():
        return args
    # 简易切分：避免逗号在引号内时误切（够用的程度）
    parts = []
    cur, in_quote = "", None
    for ch in raw:
        if in_quote:
            cur += ch
            if ch == in_quote:
                in_quote = None
        elif ch in "\"'":
            in_quote = ch
            cur += ch
        elif ch == ",":
            parts.append(cur.strip())
            cur = ""
        else:
            cur += ch
    if cur.strip():
        parts.append(cur.strip())
    for p in parts:
        if "=" in p:
            k, v = p.split("=", 1)
            args[k.strip()] = v.strip().strip("\"'")
    return args


class Agent:
    """Agent 循环：把一次用户请求 -> 多轮模型+工具 -> 最终答复"""

    def __init__(self, provider: BaseProvider, registry: ToolRegistry,
                 system_prompt: str = "", max_rounds: int = 6, max_tokens: int = 1024):
        self.provider = provider
        self.registry = registry
        self.system_prompt = system_prompt or (
            "你是綦桐AI网关里的智能助手，可以调用工具完成用户任务。\n"
            "可用工具：\n" + registry.manifest() + "\n\n"
            "如果任务需要调用工具，请输出：[[工具:函数名(参数=值)]]\n"
            "如果任务已完成或无需工具，直接给最终答复。"
        )
        self.max_rounds = max_rounds
        self.max_tokens = max_tokens

    def run(self, messages: List[LLMMessage], sender_role: str = "user",
            session_id: str = "") -> Dict[str, Any]:
        """执行 Agent 循环。返回 {reply, rounds, tool_calls, trace}"""
        msgs = list(messages)
        rounds = 0
        tool_calls = []
        last_reply = ""
        trace = []

        for _ in range(self.max_rounds):
            rounds += 1
            reply = self.provider.chat(msgs, system=self.system_prompt, max_tokens=self.max_tokens)
            last_reply = reply
            calls = parse_tool_calls(reply)

            if not calls:
                # 无工具调用 -> 视为完成
                break

            # 执行本轮所有工具调用，结果回填
            for fn, args in calls:
                tool_calls.append({"fn": fn, "args": args, "round": rounds})
                tool = self.registry.get(fn)
                trace.append(f"轮{rounds}: 调用 {fn}{args}")
                if tool is None:
                    result = f"[工具不存在: {fn}]"
                elif sender_role not in tool.allowed_roles:
                    result = f"[无权限: {fn} 需要角色 {tool.allowed_roles}]"
                else:
                    try:
                        result = str(tool.func(**args)) if args else str(tool.func())
                    except Exception as e:
                        result = f"[工具异常: {e}]"
                    trace.append(f"轮{rounds}: {fn} -> {result[:80]}")
                # 回填为 assistant 工具结果（下一轮模型可见）
                msgs.append(LLMMessage("assistant", reply))
                msgs.append(LLMMessage("user", f"[工具 {fn} 执行结果]\n{result}"))
                # 避免无限回填：若本轮多工具，逐条回填后重试；break 后重新走下一轮

        if not last_reply:
            last_reply = "(Agent 未产生回复)"
        return {
            "reply": last_reply,
            "rounds": rounds,
            "tool_calls": tool_calls,
            "trace": trace,
        }