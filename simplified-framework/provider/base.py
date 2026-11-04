# -*- coding: utf-8 -*-
"""统一 Provider 接口 + 内置实现。"""
from abc import ABC, abstractmethod
from typing import List, Dict, Any, Optional
import json
import time
import urllib.request
import urllib.error


class LLMMessage:
    """对话消息（role: system/user/assistant）"""
    def __init__(self, role: str, content: str):
        self.role = role
        self.content = content

    def to_dict(self) -> Dict[str, str]:
        return {"role": self.role, "content": self.content}


class BaseProvider(ABC):
    """模型服务统一接口：只需实现 chat()，Agent 循环依赖它拿到文本回复"""

    name: str = "base"
    model: str = ""

    @abstractmethod
    def chat(self, messages: List[LLMMessage], system: Optional[str] = None,
             max_tokens: int = 1024, temperature: float = 0.7) -> str:
        """输入多轮消息 + 可选 system，返回助手的文本回复。异常抛 ProviderError。"""
        raise NotImplementedError


class ProviderError(Exception):
    pass


class EchoProvider(BaseProvider):
    """回显 Provider：把最后一条用户消息回显（带标记）。用于离线调试管道/Agent，不碰网络。"""
    name = "echo"
    model = "echo-1"

    def __init__(self, prefix: str = "[Echo] ", suffix: str = ""):
        self.prefix = prefix
        self.suffix = suffix

    def chat(self, messages: List[LLMMessage], system: Optional[str] = None,
             max_tokens: int = 1024, temperature: float = 0.7) -> str:
        last_user = next((m.content for m in reversed(messages) if m.role == "user"), "")
        return f"{self.prefix}{last_user}{self.suffix}"


class HttpProvider(BaseProvider):
    """OpenAI 兼容 HTTP Provider：可接任意兼容端点（需传 base_url + api_key）。
    实际就是组装 OpenAI 格式请求 -> HTTP POST -> 解析 choices[0].message.content。"""

    def __init__(self, base_url: str, api_key: str, model: str, timeout: int = 30):
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key
        self.model = model
        self.timeout = timeout
        self.name = f"http:{model}"

    def chat(self, messages: List[LLMMessage], system: Optional[str] = None,
             max_tokens: int = 1024, temperature: float = 0.7) -> str:
        msgs = []
        if system:
            msgs.append({"role": "system", "content": system})
        msgs.extend(m.to_dict() for m in messages)

        body = json.dumps({
            "model": self.model,
            "messages": msgs,
            "max_tokens": max_tokens,
            "temperature": temperature,
            "stream": False,
        }).encode("utf-8")

        req = urllib.request.Request(
            f"{self.base_url}/v1/chat/completions",
            data=body,
            headers={
                "Content-Type": "application/json",
                "Authorization": f"Bearer {self.api_key}",
            },
            method="POST",
        )
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                data = json.loads(resp.read().decode("utf-8"))
            try:
                return data["choices"][0]["message"]["content"].strip()
            except (KeyError, IndexError, TypeError):
                raise ProviderError(f"响应格式异常: {str(data)[:200]}")
        except urllib.error.HTTPError as e:
            raise ProviderError(f"HTTP {e.code}: {e.read().decode('utf-8', 'ignore')[:200]}")
        except Exception as e:
            raise ProviderError(f"请求失败: {e}")


class ProviderRegistry:
    """Provider 注册表：按 name 取实现。"""
    def __init__(self):
        self._providers: Dict[str, BaseProvider] = {}

    def register(self, provider: BaseProvider):
        self._providers[provider.name] = provider

    def get(self, name: str) -> Optional[BaseProvider]:
        return self._providers.get(name)

    def names(self) -> List[str]:
        return list(self._providers.keys())


# 全局注册表
registry = ProviderRegistry()