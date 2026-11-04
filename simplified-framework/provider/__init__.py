# -*- coding: utf-8 -*-
"""模型服务层：统一 LLM 接口。
核心对话逻辑只依赖 BaseProvider 接口，换厂商 = 换个实现类，零改动核心。
内置示例实现：
  EchoProvider    回显（调试用，不用真网络）
  HttpProvider    OpenAI 兼容 HTTP（可接任意兼容端点）
"""