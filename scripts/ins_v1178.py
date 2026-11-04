#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""v1.178 版本号升级 + 日志插入（防坑#77：不用 sed，用 python 精确替换）"""
import io, re, sys

VER_OLD = "1.177"
VER_NEW = "1.178"

# 1) build.gradle.kts
p = "build.gradle.kts"
s = io.open(p, encoding="utf-8").read()
s = s.replace(f'version = "{VER_OLD}"', f'version = "{VER_NEW}"')
io.open(p, "w", encoding="utf-8").write(s)
print(f"✅ {p} -> {VER_NEW}")

# 2) Main.kt 三处 version 字面量
p = "src/main/kotlin/com/qitong/gateway/Main.kt"
s = io.open(p, encoding="utf-8").read()
# 只替换 JsonPrimitive("1.177") 形式，不动注释/历史条目
n = s.count(f'JsonPrimitive("{VER_OLD}")')
s = s.replace(f'JsonPrimitive("{VER_OLD}")', f'JsonPrimitive("{VER_NEW}")')
io.open(p, "w", encoding="utf-8").write(s)
print(f"✅ {p} -> 替换 {n} 处 version 字面量")

# 3) README.md 顶部插入 v1.178 条目（在 v1.177 条目之前）
p = "README.md"
s = io.open(p, encoding="utf-8").read()
anchor = "## v1.177"
entry = """## v1.178
**统一消息管道 + 事件总线 + 平台适配（三渠道接入）** —— 把学习到的架构思想真正落进网关主项目：

- **统一消息模型**：新增 `MessageBus`（消息总线）+ `MessagePipeline`（可插拔管道），把三渠道（内置聊天/QQ/微信）散落的消息处理收拢成统一入口
- **责任链式 Stage**：消息处理 = 白名单 → 限流 → Agent → 回复，每段可插拔、可独立开关，新增能力只需加一个 Stage
- **平台适配器模式**：三渠道差异收敛到适配层，核心逻辑只写一次；新增渠道（如 Telegram/飞书）只需实现一个适配器
- **事件驱动解耦**：消息收发/回复/工具调用/记忆存取全部走事件总线，各模块解耦，新增插件只需订阅事件

"""
if anchor in s and "## v1.178" not in s:
    s = s.replace(anchor, entry + anchor, 1)
    io.open(p, "w", encoding="utf-8").write(s)
    print(f"✅ {p} -> 插入 v1.178 条目")
else:
    print(f"⚠️ {p} 跳过（anchor 未找到或已插入）")

# 4) Database.kt 版本日志（在 v1.177 条目之前插入）
p = "src/main/kotlin/com/qitong/gateway/db/Database.kt"
s = io.open(p, encoding="utf-8").read()
anchor = 'Triple("v1.177"'
if anchor in s and 'Triple("v1.178"' not in s:
    new_entry = '            Triple("v1.178", "统一消息管道 + 事件总线 + 平台适配（三渠道接入）", "把学习到的架构思想真正落进网关主项目：①新增 MessageBus（消息总线）+ MessagePipeline（可插拔管道），把内置聊天/QQ/微信三渠道散落的消息处理收拢成统一入口；②责任链式 Stage（白名单→限流→Agent→回复），每段可插拔可独立开关，新增能力只需加一个 Stage；③平台适配器模式：三渠道差异收敛到适配层，核心逻辑只写一次，新增渠道只需实现一个适配器；④事件驱动解耦：消息收发/回复/工具调用/记忆存取全部走事件总线，各模块解耦，新增插件只需订阅事件。"),\n            '
    s = s.replace(anchor, new_entry + anchor, 1)
    io.open(p, "w", encoding="utf-8").write(s)
    print(f"✅ {p} -> 插入 v1.178 日志条目")
else:
    print(f"⚠️ {p} 跳过（anchor 未找到或已插入）")

print("done")