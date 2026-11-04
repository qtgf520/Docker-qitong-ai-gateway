# -*- coding: utf-8 -*-
"""HTTP Webhook 服务：把框架变成可独立部署的小项目。
零第三方依赖（仅 Python 标准库）：启动后任意平台（QQ/微信/网页/自建）发 HTTP POST 即可对话，
适合快速体验/内网部署/对接现有平台回调。

接口：
  GET  /health           健康检查
  POST /webhook          收消息（JSON body），走完整管道后返回回复
  GET  /                 HTML 迷你聊天页（浏览器直接体验）——可选

body 格式（对齐 platform.WebhookPlatform.incoming）：
  {"text": "你好", "sender": {"id":"u1","name":"用户","role":"user"}, "conv": {"id":"c1","kind":"private"}}
"""
import json
import logging
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

log = logging.getLogger("qtgw.web")


class WebhookHandler(BaseHTTPRequestHandler):
    """HTTP 处理器：把请求桥接到框架管道"""

    # 由外部注入（避免类级耦合）
    app = None          # 持有 platform / pipeline / ctx 的轻型容器
    chat_ui = ""        # 迷你聊天页 HTML

    # ---- 基础 ----
    def log_message(self, fmt, *args):  # 静默默认访问日志，用自有 logger
        log.debug("%s - %s", self.address_string(), fmt % args)

    def _send_json(self, code: int, obj: dict):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _read_json(self):
        try:
            length = int(self.headers.get("Content-Length", 0))
            if length <= 0 or length > 1_000_000:
                return None
            return json.loads(self.rfile.read(length).decode("utf-8"))
        except Exception as e:
            log.warning("body 解析失败: %s", e)
            return None

    # ---- 路由 ----
    def do_GET(self):
        path = urlparse(self.path).path
        if path == "/health":
            return self._send_json(200, {"status": "ok", "service": "qtgw-lite"})
        if path in ("/", "/chat"):
            body = (self.chat_ui or "<h2>綦桐AI网关 · 轻量框架</h2><p>请配置 Webhook</p>").encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        self._send_json(404, {"error": "not found"})

    def do_POST(self):
        path = urlparse(self.path).path
        if path != "/webhook":
            return self._send_json(404, {"error": "not found"})
        raw = self._read_json()
        if raw is None:
            return self._send_json(400, {"error": "bad json body"})
        app = self.app
        if app is None:
            return self._send_json(500, {"error": "app not ready"})
        try:
            # 字段适配：聊天场景常用 message/user_id，映射成 platform 期望的 text/sender
            raw.setdefault("text", raw.get("message") or raw.get("content") or "")
            if "user_id" in raw and "sender" not in raw:
                raw["sender"] = {"id": raw["user_id"], "name": raw.pop("user_name", raw["user_id"])}
            # 平台适配：WebhookPlatform 组装 Message -> 事件 -> 管道 -> 回复
            msg = app.platform.incoming(raw)
            if msg is None:
                return self._send_json(200, {"reply": "", "note": "empty message"})
            app.pipeline.process(msg, {**app.ctx, "agent": app.agent})
            reply = getattr(msg, "response", "") or ""
            ar = getattr(msg, "agent_result", None)
            return self._send_json(200, {
                "reply": reply,
                "route": msg.route,
                "tool_calls": [t.get("fn") for t in (ar.get("tool_calls", []) if ar else [])],
                "rounds": ar.get("rounds", 0) if ar else 0,
            })
        except Exception as e:
            log.exception("webhook 处理异常: %s", e)
            return self._send_json(500, {"error": str(e)})


class App:
    """应用容器：组装 platform + pipeline + agent，供 WebhookHandler 使用"""

    def __init__(self, platform, pipeline, agent, ctx=None):
        self.platform = platform
        self.pipeline = pipeline
        self.agent = agent
        self.ctx = ctx or {"agent": agent}


def run_server(platform, pipeline, agent, ctx=None, host="127.0.0.1", port=8800,
               chat_ui: str = "", log_level=logging.INFO):
    """启动 Webhook 服务（阻塞）。返回 ThreadingHTTPServer。"""
    logging.basicConfig(level=log_level, format="%(levelname)s %(name)s: %(message)s")
    WebhookHandler.app = App(platform, pipeline, agent, ctx)
    WebhookHandler.chat_ui = chat_ui
    server = ThreadingHTTPServer((host, port), WebhookHandler)
    log.info("🟢 綦桐AI网关轻量框架已启动: http://%s:%d  (/health 健康检查, /webhook 对话入口)", host, port)
    return server


def serve_forever(platform, pipeline, agent, ctx=None, host="127.0.0.1", port=8800, chat_ui=""):
    """启动并阻塞（供 run_server.py 使用）"""
    server = run_server(platform, pipeline, agent, ctx, host, port, chat_ui)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        log.info("收到退出信号，已停止")
    finally:
        server.server_close()