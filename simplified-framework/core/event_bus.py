# -*- coding: utf-8 -*-
"""事件总线：模块间解耦的核心。
各模块（平台/Agent/插件）不直接相互引用，而是：
  event_bus.emit("message.received", msg)  发事件
  event_bus.on("message.received", handler) 挂监听
扩展 = 多挂一个处理器，不动核心。"""
from typing import Callable, Dict, List, Any
import threading
import logging

log = logging.getLogger("qtgw.bus")


class EventBus:
    def __init__(self):
        self._handlers: Dict[str, List[Callable]] = {}
        self._lock = threading.Lock()

    def on(self, event: str, handler: Callable):
        """注册事件监听（handler 可同步可异步，框架统一调度）"""
        with self._lock:
            self._handlers.setdefault(event, []).append(handler)
        log.debug("bus: 注册事件 %s -> %s", event, getattr(handler, "__name__", handler))

    def off(self, event: str, handler: Callable):
        with self._lock:
            hs = self._handlers.get(event, [])
            if handler in hs:
                hs.remove(handler)

    def emit(self, event: str, *args, **kwargs):
        """触发事件，同步调用所有处理器（异常隔离，一个坏了不影响其他）"""
        with self._lock:
            handlers = list(self._handlers.get(event, []))
        for h in handlers:
            try:
                r = h(*args, **kwargs)
                if hasattr(r, "__await__"):
                    # 简单起见同步场景忽略协程；生产环境接调度器
                    pass
            except Exception as e:
                log.exception("bus: 事件 %s 处理器 %s 异常: %s", event, getattr(h, "__name__", h), e)

    def has(self, event: str) -> bool:
        return bool(self._handlers.get(event))

    def events(self) -> List[str]:
        return list(self._handlers.keys())

    def clear(self):
        with self._lock:
            self._handlers.clear()


# 全局单例（框架内共用）
bus = EventBus()