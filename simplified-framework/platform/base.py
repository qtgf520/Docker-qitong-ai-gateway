# -*- coding: utf-8 -*-
"""平台适配器基类 + 示例（控制台/网页/QQ 简版）。"""
from abc import ABC, abstractmethod
from typing import Optional
from core.message import Message, Sender, Conversation, MessageType
from core.event_bus import bus


class BasePlatform(ABC):
    """平台适配器：收 -> Message，回 -> 平台"""

    name: str = "base"

    # ---- 收：平台回调进来 -> 构造统一 Message -> 发事件给管道 ----
    @abstractmethod
    def incoming(self, raw: dict) -> Optional[Message]:
        """把平台原始数据转成统一 Message。无法解析返回 None。"""
        raise NotImplementedError

    # ---- 发：把回复 Message 发出去 ----
    @abstractmethod
    def send(self, msg: Message, text: str):
        """向 msg 的会话发送 text。"""
        raise NotImplementedError

    # 便捷：把收到的消息丢给框架（内部调 bus.emit）
    def deliver(self, raw: dict):
        msg = self.incoming(raw)
        if msg is None:
            return
        msg.sender.platform = self.name
        bus.emit("message.received", msg)

    def send_result(self, msg: Message, result: dict):
        """管道处理完后的统一出口：把 Agent 结果发出去"""
        reply_text = result.get("reply", "")
        if reply_text:
            self.send(msg, reply_text)
        # 触发"回复完成"事件（供插件观察）
        bus.emit("message.replied", msg, reply_text)


class ConsolePlatform(BasePlatform):
    """控制台平台：stdin 收、print 发。用于本地跑通全链路。"""
    name = "console"

    def incoming(self, raw: dict) -> Optional[Message]:
        text = raw.get("text", "")
        user = raw.get("user", raw.get("sender", "console"))
        conv = raw.get("conv", "console")
        if not text:
            return None
        return Message(
            type=MessageType.TEXT,
            content=text,
            raw=text,
            sender=Sender(id=user, name=user, role=raw.get("role", "user"), platform=self.name),
            conversation=Conversation(id=conv, kind="private"),
            msg_id=raw.get("msg_id", ""),
        )

    def send(self, msg: Message, text: str):
        print(f"[{self.name}->{msg.conversation.id if msg.conversation else '?'}] {text}")


class WebhookPlatform(BasePlatform):
    """Webhook 平台：模拟 QQ/微信渠道（原样透传）。接入真实网关注册即可生效。"""
    name = "webhook"

    def incoming(self, raw: dict) -> Optional[Message]:
        text = raw.get("text") or raw.get("content", "")
        if not text:
            return None
        channel = raw.get("channel", self.name)  # qq / wecom / feishu ...
        sender_raw = raw.get("sender", {})
        if isinstance(sender_raw, str):
            sender_raw = {"id": sender_raw, "name": sender_raw}
        conv_raw = raw.get("conv", {})
        if isinstance(conv_raw, str):
            conv_raw = {"id": f"{channel}:{conv_raw}"}
        return Message(
            type=MessageType.TEXT,
            content=text,
            raw=raw.get("raw", text),
            sender=Sender(
                id=sender_raw.get("id", ""),
                name=sender_raw.get("name", ""),
                role=sender_raw.get("role", "user"),
                platform=channel,
            ),
            conversation=Conversation(id=conv_raw.get("id", f"{channel}:default"), kind=conv_raw.get("kind", "private")),
            msg_id=str(raw.get("msg_id", "")),
        )

    def send(self, msg: Message, text: str):
        # 真实场景：这里调用对应渠道的推送 API（网关已具备）
        bus.emit("webhook.out", msg, text)


# 平台注册表
class PlatformRegistry:
    def __init__(self):
        self._platforms = {}

    def register(self, p: BasePlatform):
        self._platforms[p.name] = p

    def get(self, name: str) -> Optional[BasePlatform]:
        return self._platforms.get(name)

    def names(self):
        return list(self._platforms.keys())


platforms = PlatformRegistry()