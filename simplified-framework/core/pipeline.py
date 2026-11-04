# -*- coding: utf-8 -*-
"""责任链管道：消息处理流水线。
设计：消息进入管道后按顺序经过一组 stage（白名单/限流/唤醒/预处理/Agent处理/结果装饰/回复）。
每个 stage 独立可插拔、可配置顺序；加新能力 = 加一个 stage，核心零改动。
扩展思路：定义 Stage 子类 -> 注册进 Pipeline（可指定前驱/优先级）-> 自动生效。
"""
from abc import ABC, abstractmethod
from typing import List, Optional
from core.message import Message
import logging

log = logging.getLogger("qtgw.pipeline")


class StageResult:
    """stage 执行返回：决定消息是继续流、被拦截还是已消费"""
    def __init__(self, cont=True, consumed=False, response=None, reason=""):
        self.continue_ = cont      # True=继续下一个 stage
        self.consumed = consumed   # True=本消息已被处理完（不再走后续）
        self.response = response   # 若要向用户回复，填 Message 或 str
        self.reason = reason

    @staticmethod
    def next(response=None):
        """继续流转（可附带暂存回复）"""
        return StageResult(cont=True, response=response)

    @staticmethod
    def stop(response=None, reason="已拦截"):
        """停止流转（拦截/消费）"""
        return StageResult(cont=False, consumed=True, response=response, reason=reason)

    def __repr__(self):
        return f"<StageResult cont={self.continue_} consumed={self.consumed} reason={self.reason!r}>"


class Stage(ABC):
    """管道单级：子类实现 handle()"""
    name: str = "stage"

    def __init__(self, priority: int = 100):
        self.priority = priority  # 越大越先执行（可按需调整顺序）

    @abstractmethod
    def handle(self, ctx: dict, msg: Message) -> StageResult:
        """处理消息。ctx 是跨 stage 共享的上下文（可存 session/用户/Agent 状态）"""
        raise NotImplementedError

    def __repr__(self):
        return f"<Stage {self.name} p={self.priority}>"


class Pipeline:
    """消息处理管道：按 priority 排序的 stage 链条"""

    def __init__(self):
        self._stages: List[Stage] = []
        self._ctx: dict = {}  # 跨消息的全局上下文（会话管理器等由外面注入）

    def register(self, stage: Stage):
        """注册一个 stage（同 priority 时后注册者靠后）"""
        self._stages.append(stage)
        self._stages.sort(key=lambda s: -s.priority)
        log.info("pipeline: 注册 stage %s (priority=%s), 当前 %d 级", stage.name, stage.priority, len(self._stages))

    def remove(self, name: str):
        self._stages = [s for s in self._stages if s.name != name]
        log.info("pipeline: 移除 stage %s", name)

    def stages(self) -> List[str]:
        return [s.name for s in self._stages]

    def process(self, msg: Message, ctx: Optional[dict] = None) -> Message:
        """消息进管道：逐级处理直到 stop 或走完。返回最终 message（含响应）"""
        local_ctx = dict(self._ctx)
        if ctx:
            local_ctx.update(ctx)
        msg.route = []
        final = msg
        for stage in self._stages:
            if msg.consumed:
                break
            msg.route.append(stage.name)
            try:
                r = stage.handle(local_ctx, msg)
                if r is None:
                    continue
                if r.response is not None:
                    # 暂存响应（str -> 构造回复 Message）
                    if isinstance(r.response, str):
                        msg.response = r.response
                    else:
                        msg.response_obj = r.response
                if not r.continue_ or r.consumed:
                    msg.consumed = True
                    msg.stop_reason = r.reason
                    break
            except Exception as e:
                log.exception("pipeline: stage %s 异常: %s", stage.name, e)
                msg.consumed = True
                msg.stop_reason = f"stage[{stage.name}]异常: {e}"
                break
        return final

    def __len__(self):
        return len(self._stages)