# -*- coding: utf-8 -*-
"""綦桐AI网关轻量框架 —— 独立可部署入口。
一条命令启动 HTTP Webhook 服务（零第三方依赖，Python 3.8+）：
    python3 run_server.py [--port 8800] [--host 127.0.0.1] [--provider echo|http|...]

玩法：
1) 浏览器打开 http://127.0.0.1:8800/   迷你聊天页（输入即聊，走完整管道）
2) POST /webhook  对接任意平台（QQ/微信/自建回调）：
   curl -X POST http://127.0.0.1:8800/webhook \
        -H "Content-Type: application/json" \
        -d '{"text":"现在几点","sender":{"id":"u1","name":"用户","role":"user"},"conv":{"id":"c1"}}'
3) GET /health   健康检查
"""
import argparse
import os
import sys
import logging

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

log = logging.getLogger("run_server")


# ============ 内置工具集（让部署即有意义） ============
def tool_now() -> str:
    import time
    return f"🕐 {time.strftime('%Y-%m-%d %H:%M:%S')}"


def tool_echo(text: str) -> str:
    return f"✅ 回显: {text}"


def tool_upper(text: str) -> str:
    return f"🔠 {text.upper()}"


def tool_ping(url: str) -> str:
    """探测 URL 连通性（GET，返回状态码）"""
    import urllib.request
    try:
        with urllib.request.urlopen(url, timeout=8) as r:
            return f"🌐 {url} → HTTP {r.status}"
    except Exception as e:
        return f"❌ {url} 不可达: {e}"


def tool_dice(max: str = "6") -> str:
    import random
    n = int(max or 6)
    return f"🎲 掷出: {random.randint(1, max(1, n))}"


def register_tools(registry):
    from agent.agent import Tool
    registry.register(Tool("now", tool_now, "获取当前时间"))
    registry.register(Tool("echo", tool_echo, "回显文本", allowed_roles=["user", "admin"]))
    registry.register(Tool("upper", tool_upper, "把文本转大写", allowed_roles=["user", "admin"]))
    registry.register(Tool("ping", tool_ping, "探测 URL 是否可达", allowed_roles=["user", "admin"]))
    registry.register(Tool("dice", tool_dice, "掷骰子，max 默认 6", allowed_roles=["user", "admin"]))
    log.info("已注册工具: %s", registry.names())


# ============ 组装 ============
def build(provider_name: str = "echo"):
    from provider.base import EchoProvider, HttpProvider, ProviderRegistry
    from agent.agent import Agent, ToolRegistry
    from core.pipeline import Pipeline
    from core.stages import WhitelistStage, RateLimitStage, AgentStage, RespondStage
    from platform.base import WebhookPlatform

    registry = ProviderRegistry()
    if provider_name == "http":
        # 可配置：python3 run_server.py --provider http --base-url ... --api-key ... --model ...
        base = os.environ.get("LLM_BASE", "https://api.openai.com")
        key = os.environ.get("LLM_KEY", "")
        model = os.environ.get("LLM_MODEL", "gpt-4o-mini")
        registry.register(HttpProvider(base, key, model))
        log.info("Provider: HTTP (%s/%s)", base, model)
    else:
        registry.register(EchoProvider())

    tools = ToolRegistry()
    register_tools(tools)

    system = (
        "你是綦桐AI网关里的智能助手。你可以调用工具完成任务。\n"
        "可用工具:\n" + tools.manifest() + "\n\n"
        "调用格式：[[工具:函数名(参数=值)]]，一行一个；多个工具依次输出。\n"
        "如果不需要工具，直接给最终答复。"
    )
    agent = Agent(registry.get(provider_name if provider_name == "http" else "echo"), tools,
                  system_prompt=system, max_rounds=6)

    pipe = Pipeline()
    pipe.register(WhitelistStage())          # 白名单（空=放行）
    pipe.register(RateLimitStage(default_limit=30, window=10))  # 限流 30次/10秒
    pipe.register(AgentStage())
    pipe.register(RespondStage())

    ctx = {"agent": agent, "whitelist": set(), "history": {}}
    platform = WebhookPlatform()
    return platform, pipe, agent, ctx


def make_chat_ui() -> str:
    """内嵌迷你聊天页（浏览器直接体验）"""
    return """<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8"><title>綦桐AI网关 · 轻量对话</title>
<style>
 body{font-family:-apple-system,'PingFang SC',sans-serif;background:#0F172A;color:#E2E8F0;margin:0;display:flex;flex-direction:column;height:100vh}
 .head{padding:14px 20px;background:#1E293B;border-bottom:1px solid #334155;font-weight:700;color:#fff}
 .head small{color:#94A3B8;font-weight:400}
 #box{flex:1;overflow-y:auto;padding:20px}
 .m{max-width:70%;padding:10px 14px;border-radius:12px;margin-bottom:10px;white-space:pre-wrap;word-break:break-all}
 .user{background:#4F46E5;margin-left:auto;color:#fff}
 .ai{background:#1E293B;border:1px solid #334155}
 .ai .fn{color:#38BDF8;font-size:12px;margin-top:4px}
 .bot{margin-top:2px;border-bottom:1px solid #334155;padding:6px 4px}
 .foot{display:flex;padding:12px;background:#1E293B;border-top:1px solid #334155}
 input{flex:1;background:#0F172A;border:1px solid #334155;color:#E2E8F0;border-radius:8px;padding:10px 14px;outline:none}
 button{margin-left:8px;padding:10px 18px;background:#4F46E5;color:#fff;border:0;border-radius:8px;cursor:pointer}
</style></head><body>
<div class="head">🧠 綦桐AI网关 · 轻量对话 <small>HTTP Webhook 迷你演示</small></div>
<div id="box"><div class="m ai">你好！我是綦桐AI网关的智能助手。可以试试：<br>· 现在几点<br>· 用echo回显 你好<br>· 掷骰子</div></div>
<div class="foot"><input id="in" placeholder="说点什么…" onkeydown="if(event.key==='Enter')send()"><button onclick="send()">发送</button></div>
<script>
function add(cls,txt){var b=$('box'),d=document.createElement('div');d.className='m '+cls;d.textContent=txt;b.appendChild(d);b.scrollTop=b.scrollHeight;return d}
async function send(){var i=$('in'),t=i.value.trim();if(!t)return;i.value='';add('user',t);
 var d=add('ai','…');try{var r=await fetch('/webhook',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({text:t,sender:{id:'web',name:'访客',role:'user'},conv:{id:'web'}})}).then(x=>x.json());
 var txt=r.reply||'(无回复)';if(r.tool_calls&&r.tool_calls.length)txt+=('\\n\\n🔧 调用了工具: '+r.tool_calls.join(', '));d.textContent=txt;}catch(e){d.textContent='❌ '+e}}
function $(id){return document.getElementById(id)}
</script></body></html>"""


def main():
    parser = argparse.ArgumentParser(description="綦桐AI网关轻量框架 - 独立部署")
    parser.add_argument("--host", default=os.environ.get("HOST", "127.0.0.1"))
    parser.add_argument("--port", type=int, default=int(os.environ.get("PORT", 8800)))
    parser.add_argument("--provider", default=os.environ.get("PROVIDER", "echo"),
                        choices=["echo", "http"], help="echo=离线回显 / http=真实LLM(需 LLM_BASE/KEY/MODEL)")
    args = parser.parse_args()

    platform, pipe, agent, ctx = build(args.provider)
    from core.web_server import serve_forever
    serve_forever(platform, pipe, agent, ctx, host=args.host, port=args.port, chat_ui=make_chat_ui())


if __name__ == "__main__":
    main()