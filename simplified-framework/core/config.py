# -*- coding: utf-8 -*-
"""配置中心：框架级全局配置 + 用户会话级配置。
简化版用内存字典实现（生产可接数据库/文件）。"""
from typing import Any, Dict, Optional
import threading


class Config:
    """两级配置：global(框架) + per_session(会话)"""
    def __init__(self):
        self._global: Dict[str, Any] = {}
        self._session: Dict[str, Dict[str, Any]] = {}
        self._lock = threading.Lock()

    # ---- 全局 ----
    def set(self, key: str, value: Any):
        with self._lock:
            self._global[key] = value

    def get(self, key: str, default: Any = None) -> Any:
        with self._lock:
            return self._global.get(key, default)

    # ---- 会话级（按 session_id 隔离：每人/每群独立配置，如模型/人格/开关） ----
    def session_set(self, session_id: str, key: str, value: Any):
        with self._lock:
            self._session.setdefault(session_id, {})[key] = value

    def session_get(self, session_id: str, key: str, default: Any = None) -> Any:
        with self._lock:
            return self._session.get(session_id, {}).get(key, default)

    def session_all(self, session_id: str) -> Dict[str, Any]:
        with self._lock:
            return dict(self._session.get(session_id, {}))

    def dumps(self) -> Dict[str, Any]:
        with self._lock:
            return {"global": dict(self._global), "sessions": {k: dict(v) for k, v in self._session.items()}}


# 全局单例
conf = Config()