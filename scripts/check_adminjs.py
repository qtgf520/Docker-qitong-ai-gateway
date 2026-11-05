#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""提取 AdminJs*.kt 的全部 JS 并做 node --check 语法校验（防白屏铁律）"""
import re, io, subprocess, glob, sys

# AdminJs 文件里的 JS 是三引号包裹的整块字符串，去掉外层 Kotlin 包装后整体就是 JS
# 更稳的方式：把三引号块内容全部拼起来
for f in sorted(glob.glob('src/main/kotlin/com/qitong/gateway/AdminJs*.kt')):
    text = io.open(f, encoding='utf-8').read()
    # 提取所有 三引号 块（""" ... """）
    blocks = re.findall(r'\"\"\"(.*?)\"\"\"', text, re.S)
    js = '\n'.join(blocks)
    if not js.strip():
        print(f'⚠️ {f}: 未提取到 JS 块')
        continue
    with open('/tmp/adminjs_check.js', 'w', encoding='utf-8') as w:
        w.write(js)
    r = subprocess.run(['node', '--check', '/tmp/adminjs_check.js'], capture_output=True, text=True)
    if r.returncode != 0:
        print(f'❌ {f}: JS 语法错误')
        print(r.stderr[:2000])
        sys.exit(1)
    else:
        print(f'✅ {f}: JS 语法通过（{len(js)} 字符）')
print('全部通过')