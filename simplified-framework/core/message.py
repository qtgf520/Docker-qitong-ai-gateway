# -*- coding: utf-8 -*-
"""统一消息模型：平台无关的消息（类型/内容/发送者/会话/原始平台）
所有平台适配器都把收到的外来消息转成这个对象，核心逻辑不感知平台差异。
"""
from dataclasses import dataclass, field
from typing import Optional
from enum import Enum
import time


class MessageType(str, Enum):
    TEXT = "text"          # 纯文本
    IMAGE = "image"        # 图片
    FILE = "file"          # 文件
    VOICE = "voice"        # 语音
    MENTION = "mention"    # @提及/指令触发


@dataclass
class Sender:
    """发送者（平台无关身份：平台openid / 用户名 / 会话key）"""
    id: str                      # 平台侧唯一 id
    name: str = ""               # 展示名
    role: str = "user"           # user / admin / agent
    platform: str = ""           # 来源平台名（qq/wecom/web…）


@dataclass
class Conversation:
    """会话（对话上下文容器）：群聊/私聊/单会话 统一模型"""
    id: str                      # 会话唯一 key（如 "qq:group:12345" / "wecom:user:abc"）
    kind: str = "private"        # private / group
    title: str = ""
    meta: dict = field(default_factory=dict)   # 平台额外信息（群号/机器人id等）


@dataclass
class Message:
    """统一消息：所有平台进出的中介模型"""
    type: MessageType = MessageType.TEXT
    content: str = ""                    # 文本内容（图片/文件时是描述或URL）
    raw: str = ""                        # 原始未处理内容
    sender: Sender = None
    conversation: Conversation = None
    msg_id: str = ""                     # 平台消息id（回执用）
    timestamp: int = field(default_factory=lambda: int(time.time() * 1000))  # 毫秒
    reply_target: Optional[str] = None   # 要回复的消息id（被动回复）
    # 管道/Agent 运行期附加（不进平台）
    session_id: str = ""                 # Agent 运行上下文 id（同会话连续）
    route: list = field(default_factory=list)   # 经过的 stage 记录（调试用）
    # ★ 管道运行期状态（由 Stage 写入）
    consumed: bool = False               # 消息是否已被消费（停止流转）
    response: str = ""                   # 最终回复文本（Stages 暂存）
    stop_reason: str = ""                # 停止流转原因（拦截/限流/异常）
    agent_result: dict = field(default_factory=dict)   # Agent 运行结果（trace/轮次/工具）

    def brief(self) -> str:
        """调试摘要"""
        return f"[{self.type.value}][{self.conversation.id if self.conversation else '?'}] {self.sender.name if self.sender else '?'}: {self.content[:50]}"

    def route_trace(self) -> str:
        return " -> ".join(self.route) or "(未经过管道)"