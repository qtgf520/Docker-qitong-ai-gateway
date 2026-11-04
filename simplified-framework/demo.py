# -*- coding: utf-8 -*-
"""简化版框架 demo：控制台跑通「消息 → 管道(白名单/限流/Agent) → 工具 → 回复」全链路。
运行： python3 demo.py
输入："调用工具 测试回显 abc" 会触发工具；其他文本由 Agent 回显。
"""
import sys
import logging
import os

# 让 import 能找到包
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

logging.basicConfig(level=logging.INFO, format="%(levelname)s %(name)s: %(message)s")
log = logging.getLogger("demo")

from core.message import Message, Sender, Conversation, MessageType
from core.event_bus import bus
from core.pipeline import Pipeline
from core.stages import WhitelistStage, RateLimitStage, AgentStage, RespondStage
from core.config import conf
from provider.base import EchoProvider, ProviderRegistry
from agent.agent import Agent, ToolRegistry, Tool
from platform.base import ConsolePlatform, PlatformRegistry


# ============ 内置工具（演示） ============
def tool_echo(text: str) -> str:
    """回显工具：把输入原样返回"""
    return f"✅ 工具回显: {text}"


def tool_now() -> str:
    """当前时间工具"""
    import time
    return f"🕐 当前时间: {time.strftime('%Y-%m-%d %H:%M:%S')}"


def tool_upper(text: str) -> str:
    """转大写工具：把文本转成大写"""
    return f"🔠 大写结果: {text.upper()}"


def register_builtin_tools(registry: ToolRegistry):
    registry.register(Tool("echo", tool_echo, "回显文本内容", allowed_roles=["user", "admin"]))
    registry.register(Tool("now", tool_now, "获取当前时间", allowed_roles=["user", "admin"]))
    registry.register(Tool("upper", tool_upper, "把文本转大写", allowed_roles=["user", "admin"]))
    log.info("已注册 %d 个内置工具", len(registry.names()))


# ============ 装载 Agent 配置链 ============
def build():
    # Provider：用回显 Provider（离线调试，不碰网络）
    provider = EchoProvider(prefix="", suffix="")
    registry = ProviderRegistry()
    registry.register(provider)

    # 工具
    tools = ToolRegistry()
    register_builtin_tools(tools)

    # Agent：system prompt 明确告诉模型工具语法
    system = (
        "你是测试助手。你可以调用工具完成任务。\n"
        "可用工具：\n" + tools.manifest() + "\n\n"
        "调用格式（一行一个）：[[工具:函数名(参数=值)]]\n"
        "示例：用户说「用echo回显你好」→ [[工具:echo(text=你好)]]\n"
        "如果不需要工具，直接回答。\n"
    )
    agent = Agent(provider, tools, system_prompt=system, max_rounds=4)

    # 管道：白名单(关) -> 限流(25次/10秒) -> Agent -> 回复
    pipe = Pipeline()
    pipe.register(WhitelistStage())
    pipe.register(RateLimitStage(default_limit=25, window=10))
    pipe.register(AgentStage())
    pipe.register(RespondStage())

    # 会话历史（演示上下文）
    history = {}

    # ctx：管道共享上下文
    ctx = {"agent": agent, "whitelist": set(), "history": history}
    return ctx, pipe, tools


def main():
    ctx, pipe, tools = build()

    # 控制台平台
    console = ConsolePlatform()
    plat_reg = PlatformRegistry()
    plat_reg.register(console)

    # 插件：通过事件钩子观察
    from plugins.example import install as install_plugin
    install_plugin(bus)

    # 事件：消息接收 -> 进管道 -> 平台发送回复
    def handle_incoming(msg: Message):
        log.info("管道处理: %s", msg.brief())
        pipe.process(msg, ctx)
        reply = getattr(msg, "response", "") or ""
        if reply:
            console.send(msg, reply)
            bus.emit("message.replied", msg, reply)
        # 展示 Agent trace（调试）
        ar = getattr(msg, "agent_result", None)
        if ar:
            print("  └─ Agent 轮次:", ar.get("rounds"), "| 工具调用:", [t["fn"] for t in ar.get("tool_calls", [])])

    bus.on("message.received", handle_incoming)

    print("=" * 60)
    print("綦桐AI网关·轻量跨平台对话框架 (简化版) —— 控制台 Demo")
    print("示例输入:")
    print('  "用echo回显 你好"  → 触发 echo 工具')
    print('  "现在几点"          → 触发 now 工具')
    print('  "把 abc 转大写"     → 触发 upper 工具')
    print('  "你好"              → Agent 直接回复')
    print("  输入 exit 退出")
    print("=" * 60)

    # 控制台收消息（模拟平台推送）
    while True:
        try:
            line = input("你> ").strip()
        except (EOFError, KeyboardInterrupt):
            print("\n退出")
            break
        if not line:
            continue
        if line.lower() in ("exit", "quit", "q"):
            print("退出")
            break
        # 模拟平台收消息：控制台平台把输入转成 Message
        raw = {"text": line, "user": "demo-user", "role": "user", "conv": "console"}
        console.deliver(raw)


if __name__ == "__main__":
    main()