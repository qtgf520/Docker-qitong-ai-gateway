#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""v1.186 更新日志插入（README.md + Database.kt）"""
import io

# 1) README.md 顶部插入 v1.186 条目
p = "README.md"
s = io.open(p, encoding="utf-8").read()
anchor = "## v1.185"
entry = """## v1.186
**QQ 私聊不回复 + QQ 不自然修复（AccessToken 401 自动刷新 + 收敛误触发）**：

- **QQ 私聊收不到回复根治**：日志实锤「私聊消息发送失败 HTTP 401 AccessToken无效或过期」——模型正常处理、结果已生成，但发送时 Token 被官方吊销而本地缓存仍认为有效（7200s 未到期）→ 用户永远看不到回复。修复：私聊/群消息/卡片发送遇 401 **自动强制刷新 AccessToken 并重试一次**（forceRefreshToken 清缓存 → 换新 token → 重新发送）
- **QQ 不像人/总解释"我没真调工具"根治**：原 closeBody 收敛触发条件用 `loopGuard > 0`（只要循环过就触发），但假调用引导（模型说"正在调用"却无标签 → 引导）也会 +1 → 纯聊天被诱导"总结已推送的工具结果"却没结果 → 编出"我没真调任何工具"式空转话痨。修复：改用 `executedCalls`（真执行过工具才触发收敛总结），完全对齐微信

"""
if anchor in s and "## v1.186" not in s:
    s = s.replace(anchor, entry + anchor, 1)
    io.open(p, "w", encoding="utf-8").write(s)
    print(f"✅ {p} -> 插入 v1.186 条目")
else:
    print(f"⚠️ {p} 跳过")

# 2) Database.kt 版本日志
p = "src/main/kotlin/com/qitong/gateway/db/Database.kt"
s = io.open(p, encoding="utf-8").read()
anchor = 'Triple("v1.185"'
if anchor in s and 'Triple("v1.186"' not in s:
    new_entry = '            Triple("v1.186", "QQ 私聊不回复 + QQ 不自然修复（AccessToken 401 自动刷新 + 收敛误触发）", "①QQ 私聊收不到回复根治：日志实锤私聊消息发送失败 HTTP 401 AccessToken无效或过期——模型正常处理结果已生成但发送时 Token 被官方吊销本地缓存仍认为有效（7200s 未到期），用户永远看不到回复；修复：私聊/群消息/卡片发送遇 401 自动强制刷新 AccessToken 并重试一次（forceRefreshToken 清缓存→换新 token→重新发送）。②QQ 不像人/总解释没真调工具根治：原 closeBody 收敛触发条件用 loopGuard>0（只要循环过就触发），但假调用引导（模型说正在调用却无标签→引导）也会+1 → 纯聊天被诱导总结已推送的工具结果却没结果→编出没真调任何工具式空转话痨；修复：改用 executedCalls（真执行过工具才触发收敛总结），完全对齐微信。"),\n            '
    s = s.replace(anchor, new_entry + anchor, 1)
    io.open(p, "w", encoding="utf-8").write(s)
    print(f"✅ {p} -> 插入 v1.186 日志条目")
else:
    print(f"⚠️ {p} 跳过")

print("done")