#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""v1.180 更新日志插入（README.md + Database.kt）"""
import io

# 1) README.md 顶部插入 v1.180 条目（在 v1.179 条目之前）
p = "README.md"
s = io.open(p, encoding="utf-8").read()
anchor = "## v1.179"
entry = """## v1.180
**QQ 在线静默守护（修复微信能聊 QQ 收不到消息）** —— 根治 QQ 机器人"状态在线却收不到消息"的假死问题：

- **根因锁定**：QQ 官方 WebSocket 在推送通道损坏时不会主动报错（状态仍 ONLINE），旧掉线监控只看 ERROR/OFFLINE 检测不到 → 表现为"微信（长轮询主动拉）正常、QQ（WS 被动收）失灵"
- **在线假死检测**：QqGatewayClient 记录最后一次收到业务消息（Dispatch）的时间，暴露 `lastDispatchAtMs`
- **静默守护**：掉线监控增强——状态 ONLINE 但超过阈值分钟无任何业务消息推送 → 判定通道假死自动重启（收消息即恢复；默认 30 分钟，可配 `qq_silent_guard_min`，设 0 关闭）

"""
if anchor in s and "## v1.180" not in s:
    s = s.replace(anchor, entry + anchor, 1)
    io.open(p, "w", encoding="utf-8").write(s)
    print(f"✅ {p} -> 插入 v1.180 条目")
else:
    print(f"⚠️ {p} 跳过（anchor 未找到或已插入）")

# 2) Database.kt 版本日志（在 v1.179 条目之前插入）
p = "src/main/kotlin/com/qitong/gateway/db/Database.kt"
s = io.open(p, encoding="utf-8").read()
anchor = 'Triple("v1.179"'
if anchor in s and 'Triple("v1.180"' not in s:
    new_entry = '            Triple("v1.180", "QQ 在线静默守护（修复微信能聊QQ收不到消息）", "根治 QQ 机器人「状态在线却收不到消息」的假死问题：①根因——QQ 官方 WebSocket 在推送通道损坏时不会主动报错（状态仍 ONLINE），旧掉线监控只看 ERROR/OFFLINE 检测不到，表现为微信（长轮询主动拉）正常、QQ（WS 被动收）失灵；②在线假死检测——QqGatewayClient 记录最后一次收到业务消息（Dispatch）的时间 lastDispatchAtMs，READY/群消息/私聊消息都刷新；③静默守护——掉线监控增强：状态 ONLINE 但超过阈值分钟无任何业务消息推送 → 判定通道假死自动重启（默认 30 分钟，可配 qq_silent_guard_min，设 0 关闭）。"),\n            '
    s = s.replace(anchor, new_entry + anchor, 1)
    io.open(p, "w", encoding="utf-8").write(s)
    print(f"✅ {p} -> 插入 v1.180 日志条目")
else:
    print(f"⚠️ {p} 跳过（anchor 未找到或已插入）")

print("done")