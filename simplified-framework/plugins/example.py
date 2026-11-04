# -*- coding: utf-8 -*-
"""示例插件：演示如何通过事件钩子扩展框架（不碰核心）。
功能：监听「消息已接收」和「已回复」事件，打印观察日志，并可注入额外行为。
"""
import logging

log = logging.getLogger("qtgw.plugin.example")


def on_message_received(msg):
    """消息进入管道前触发：可做日志/统计/拦截标记"""
    log.info("[插件] 收到消息: %s", msg.brief())


def on_message_replied(msg, reply_text):
    """回复完成触发：可做记录/转发/联动"""
    log.info("[插件] 已回复: %s", (reply_text or "")[:50])


def on_tool_called(msg, fn, args):
    """工具被调用时触发（Agent 内可调）"""
    log.info("[插件] 工具调用: %s%s", fn, args)


def install(bus):
    """框架加载插件时调用：注册监听"""
    bus.on("message.received", on_message_received)
    bus.on("message.replied", on_message_replied)
    bus.on("tool.called", lambda _msg, fn, args: on_tool_called(_msg, fn, args))
    log.info("示例插件已安装")