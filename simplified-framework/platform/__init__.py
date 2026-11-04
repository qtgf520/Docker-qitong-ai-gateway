# -*- coding: utf-8 -*-
"""平台适配层：平台中立的关键。
每个平台实现 BasePlatform，只做两件事：
  1) 接收外来消息 -> 转成统一 Message
  2) 把回复 Message -> 发给对应平台
框架核心 / 管道 / Agent 从不感知平台差异。
"""