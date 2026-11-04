#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""v1.184 更新日志插入（README.md + Database.kt）"""
import io

# 1) README.md 顶部插入 v1.184 条目（在 v1.183 条目之前）
p = "README.md"
s = io.open(p, encoding="utf-8").read()
anchor = "## v1.183"
entry = """## v1.184
**管道 Stage 深化（限流/白名单 Stage 真正接管三渠道）** —— 统一编排层从"骨架"到"真正生效"：

- **新增 PipelineGate 管道预检**：三渠道（内置聊天/QQ/微信）消息进入正式处理前统一过预检
- **限流 Stage 真正生效**：绑定用户按配额（QPS/每日/RPM/TPM）检查，管理员不限——之前限流只存在于管道骨架、没接进实际链路，现在 QQ/微信/内置聊天 四入口全部接入
- **白名单 Stage 可配**：`pipeline_whitelist_enabled=true` 时未绑定用户仅放行绑定类指令（默认关，不破坏未绑定可聊体验）
- **零侵入**：只加判断不改原流程，与现有 askModel/并发池完全兼容

"""
if anchor in s and "## v1.184" not in s:
    s = s.replace(anchor, entry + anchor, 1)
    io.open(p, "w", encoding="utf-8").write(s)
    print(f"✅ {p} -> 插入 v1.184 条目")
else:
    print(f"⚠️ {p} 跳过（anchor 未找到或已插入）")

# 2) Database.kt 版本日志（在 v1.183 条目之前插入）
p = "src/main/kotlin/com/qitong/gateway/db/Database.kt"
s = io.open(p, encoding="utf-8").read()
anchor = 'Triple("v1.183"'
if anchor in s and 'Triple("v1.184"' not in s:
    new_entry = '            Triple("v1.184", "管道 Stage 深化（限流/白名单 Stage 真正接管三渠道）", "统一编排层从骨架到真正生效：①新增 PipelineGate 管道预检——三渠道（内置聊天/QQ/微信）消息进入正式处理前统一过预检；②限流 Stage 真正生效——绑定用户按配额（QPS/每日/RPM/TPM）检查，管理员不限，之前限流只存在于管道骨架没接进实际链路，现在 QQ/微信/内置聊天四入口全部接入；③白名单 Stage 可配——pipeline_whitelist_enabled=true 时未绑定用户仅放行绑定类指令（默认关，不破坏未绑定可聊体验）；④零侵入——只加判断不改原流程，与现有 askModel/并发池完全兼容。"),\n            '
    s = s.replace(anchor, new_entry + anchor, 1)
    io.open(p, "w", encoding="utf-8").write(s)
    print(f"✅ {p} -> 插入 v1.184 日志条目")
else:
    print(f"⚠️ {p} 跳过（anchor 未找到或已插入）")

print("done")