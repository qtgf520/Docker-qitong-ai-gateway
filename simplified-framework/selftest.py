# -*- coding: utf-8 -*-
"""框架链路自测：验证 消息 -> 管道(白名单/限流/Agent) -> 工具 -> 回复 全链路可用。
用「指令式 EchoProvider」模拟模型：检测用户输入关键词，决定输出工具调用还是直接回复。
运行： python3 selftest.py
"""
import sys, os, logging
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
logging.basicConfig(level=logging.WARNING, format="%(levelname)s %(name)s: %(message)s")

from core.message import Message, Sender, Conversation, MessageType
from core.event_bus import bus
from core.pipeline import Pipeline
from core.stages import WhitelistStage, RateLimitStage, AgentStage, RespondStage
from provider.base import BaseProvider, LLMMessage
from agent.agent import Agent, ToolRegistry, Tool

# ---- 指令式 Provider：根据输入生成「工具调用」或「直接回复」 ----
class ScriptedProvider(BaseProvider):
    name = "scripted"
    model = "script-1"

    def __init__(self):
        self.reset()

    def reset(self):
        self.messages_seen = []

    def chat(self, messages, system=None, max_tokens=1024, temperature=0.7):
        # 取最后一条 user 消息
        last_user = next((m.content for m in reversed(messages) if m.role == "user"), "")
        self.messages_seen.append(last_user)
        # 规则：按输入关键词决定模型输出
        if "echo 你好" in last_user or "回显" in last_user.lower():
            # 第一轮：输出工具调用；工具结果回填后（消息含[工具 echo]）输出最终
            if "[工具 echo" in last_user:
                return "收到，已调用工具。结果已拿到。"
            return "[[工具:echo(text=你好世界)]]"
        if "时间" in last_user or "几点" in last_user:
            if "[工具 now" in last_user:
                return "现在是当前时间。任务完成。"
            return "[[工具:now()]]"
        if "大写" in last_user.lower() or "uppercase" in last_user.lower():
            if "[工具 upper" in last_user:
                return "已转大写，任务完成。"
            return "[[工具:upper(text=abc)]]"
        # 普通闲聊
        return f"收到你的消息：{last_user}"


# ---- 工具 ----
def tool_echo(text: str) -> str:
    return f"✅ 回显: {text}"
def tool_now() -> str:
    import time
    return f"🕐 {time.strftime('%Y-%m-%d %H:%M:%S')}"
def tool_upper(text: str) -> str:
    return f"🔠 {text.upper()}"


def run_case(name, text, history=None, expect_tool=None):
    tools = ToolRegistry()
    tools.register(Tool("echo", tool_echo, "回显"))
    tools.register(Tool("now", tool_now, "时间"))
    tools.register(Tool("upper", tool_upper, "大写"))
    provider = ScriptedProvider()
    agent = Agent(provider, tools, max_rounds=4)

    pipe = Pipeline()
    pipe.register(WhitelistStage())
    pipe.register(RateLimitStage(default_limit=25, window=10))
    pipe.register(AgentStage())
    pipe.register(RespondStage())

    ctx = {"agent": agent, "whitelist": set(), "history": history if history is not None else {}}
    msg = Message(
        type=MessageType.TEXT, content=text,
        sender=Sender(id="tester", name="测试员", role="user"),
        conversation=Conversation(id="conv-test", kind="private"),
        session_id="conv-test",
    )
    pipe.process(msg, ctx)
    reply = getattr(msg, "response", "") or "(无回复)"
    ar = getattr(msg, "agent_result", None)
    tools_used = [t["fn"] for t in (ar.get("tool_calls", []) if ar else [])]
    ok = (expect_tool is None) or (expect_tool in tools_used)
    print(f"[{'✅' if ok else '❌'}] {name}")
    print(f"    输入: {text}")
    if ar: print(f"    轮次: {ar['rounds']} | 工具: {tools_used}")
    print(f"    回复: {reply}")
    return ok


def main():
    print("=" * 60)
    print("綦桐AI网关·轻量跨平台框架 —— 全链路自测")
    print("=" * 60)
    results = []
    results.append(run_case("回显工具调用", "请用 echo 回显 你好", expect_tool="echo"))
    provider2 = ScriptedProvider()
    h = {}
    results.append(run_case("时间工具调用", "现在几点", expect_tool="now"))
    results.append(run_case("转大写工具调用", "把 abc 转大写", expect_tool="upper"))
    results.append(run_case("闲聊直接回复", "你好呀", expect_tool=None))  # 不该有工具
    results.append(run_case("闲聊2", "今天天气怎么样", expect_tool=None))
    print("=" * 60)
    passed = sum(results)
    print(f"自测结果: {passed}/{len(results)} 通过")
    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())