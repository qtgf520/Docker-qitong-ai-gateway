#!/usr/bin/env python3
"""发版前必过检查：启动/复用本地实例，抓 /admin，抽取 inline JS，用 node --check 验证语法。
用法： python3 tools/check_admin_js.py [base_url]  默认 http://127.0.0.1:28080
退出码 0 = 通过；1 = JS 语法错误（后台会整页按钮失效）
"""
import re, subprocess, sys, tempfile, urllib.request
base = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:28080"
html = urllib.request.urlopen(base + "/admin", timeout=15).read().decode("utf-8", "ignore")
js = "\n".join(re.findall(r"<script(?![^>]*src)[^>]*>(.*?)</script>", html, re.S))
if not js.strip():
    print("WARN: /admin 没有 inline script，检查不生效"); sys.exit(0)
with tempfile.NamedTemporaryFile("w", suffix=".js", delete=False, encoding="utf-8") as f:
    f.write(js); path = f.name
r = subprocess.run(["node", "--check", path], capture_output=True, text=True)
if r.returncode != 0:
    print("❌ 后台 inline JS 语法错误（页面会全部按钮失效）:\n" + (r.stderr or "")[:2000])
    sys.exit(1)
print("✅ 后台 inline JS 语法通过，长度 %d" % len(js))
