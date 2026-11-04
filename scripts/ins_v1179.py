#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""v1.179 版本号升级（防坑#77：不用 sed，用 python 精确替换）"""
import io

VER_OLD = "1.178"
VER_NEW = "1.179"

# 1) build.gradle.kts
p = "build.gradle.kts"
s = io.open(p, encoding="utf-8").read()
s = s.replace(f'version = "{VER_OLD}"', f'version = "{VER_NEW}"')
io.open(p, "w", encoding="utf-8").write(s)
print(f"✅ {p} -> {VER_NEW}")

# 2) Main.kt：version 字面量 + banner（只改 JsonPrimitive 形式与 banner 字符串）
p = "src/main/kotlin/com/qitong/gateway/Main.kt"
s = io.open(p, encoding="utf-8").read()
n = s.count(f'JsonPrimitive("{VER_OLD}")')
s = s.replace(f'JsonPrimitive("{VER_OLD}")', f'JsonPrimitive("{VER_NEW}")')
s = s.replace(f"Docker Server v{VER_OLD}", f"Docker Server v{VER_NEW}")
s = s.replace(f"Docker 服务器版 v{VER_OLD}", f"Docker 服务器版 v{VER_NEW}")
io.open(p, "w", encoding="utf-8").write(s)
print(f"✅ {p} -> 替换 {n} 处 version 字面量 + banner")

print("done")