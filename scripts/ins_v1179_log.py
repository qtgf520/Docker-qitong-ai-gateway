#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""v1.179 更新日志插入（README.md + Database.kt）"""
import io

# 1) README.md 顶部插入 v1.179 条目（在 v1.178 条目之前）
p = "README.md"
s = io.open(p, encoding="utf-8").read()
anchor = "## v1.178"
entry = """## v1.179
**三渠道（QQ/微信）全面接入事件总线** —— 补全 v1.178 统一消息管道的三渠道覆盖：

- **QQ 群/私聊接入**：群消息（带 groupOpenid 语义）与私聊消息进入管道时发布 `message.received` 事件
- **微信接入**：微信消息进入管道时发布 `message.received` 事件
- **三渠道闭环**：内置聊天 + QQ + 微信全部走统一事件总线，任何新增能力只需订阅事件即可三端同时生效

"""
if anchor in s and "## v1.179" not in s:
    s = s.replace(anchor, entry + anchor, 1)
    io.open(p, "w", encoding="utf-8").write(s)
    print(f"✅ {p} -> 插入 v1.179 条目")
else:
    print(f"⚠️ {p} 跳过（anchor 未找到或已插入）")

# 2) Database.kt 版本日志（在 v1.178 条目之前插入）
p = "src/main/kotlin/com/qitong/gateway/db/Database.kt"
s = io.open(p, encoding="utf-8").read()
anchor = 'Triple("v1.178"'
if anchor in s and 'Triple("v1.179"' not in s:
    new_entry = '            Triple("v1.179", "三渠道（QQ/微信）全面接入事件总线", "补全 v1.178 统一消息管道的三渠道覆盖：①QQ 群消息进入管道时发布 message.received 事件（带 groupOpenid 群语义），QQ 私聊消息同样接入；②微信消息进入管道时发布 message.received 事件；③三渠道闭环：内置聊天 + QQ + 微信全部走统一事件总线，任何新增能力只需订阅事件即可三端同时生效。"),\n            '
    s = s.replace(anchor, new_entry + anchor, 1)
    io.open(p, "w", encoding="utf-8").write(s)
    print(f"✅ {p} -> 插入 v1.179 日志条目")
else:
    print(f"⚠️ {p} 跳过（anchor 未找到或已插入）")

print("done")