#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""v1.181 更新日志插入（README.md + Database.kt）"""
import io

# 1) README.md 顶部插入 v1.181 条目（在 v1.180 条目之前）
p = "README.md"
s = io.open(p, encoding="utf-8").read()
anchor = "## v1.180"
entry = """## v1.181
**QQ 对话自然化（移除静默冷却 + 过程推送配额保护）** —— 让 QQ 像微信一样自然接话、长任务反馈不中断：

- **移除 3 秒静默吞消息**：AI 路径原来的 `onCooldown 3s` 会把用户 3 秒内连发的【不同】消息静默吞掉（无任何回复）→ 表现"QQ 不接话、像被指令了一下"。改为【仅同内容防刷屏】——3 秒内相同内容才拦截，不同消息立即并发处理（v1.166 并发池），QQ 恢复"有来有回、连续对话"
- **过程推送配额保护**：QQ 主动消息有每日配额，长任务多轮工具调用逐条烧会提前耗尽→ 中途推送全失败、用户看到"机器人突然不说话"。方案：单次任务过程推送（💭/🔧/✅）默认最多 5 条（可配 `qq_progress_budget`），超预算后静默、把配额留给最终回复（必达）。微信无此限制不受影响

"""
if anchor in s and "## v1.181" not in s:
    s = s.replace(anchor, entry + anchor, 1)
    io.open(p, "w", encoding="utf-8").write(s)
    print(f"✅ {p} -> 插入 v1.181 条目")
else:
    print(f"⚠️ {p} 跳过（anchor 未找到或已插入）")

# 2) Database.kt 版本日志（在 v1.180 条目之前插入）
p = "src/main/kotlin/com/qitong/gateway/db/Database.kt"
s = io.open(p, encoding="utf-8").read()
anchor = 'Triple("v1.180"'
if anchor in s and 'Triple("v1.181"' not in s:
    new_entry = '            Triple("v1.181", "QQ 对话自然化（移除静默冷却 + 过程推送配额保护）", "让 QQ 像微信一样自然接话、长任务反馈不中断：①移除 3 秒静默吞消息——AI 路径原 onCooldown 3s 会把用户 3 秒内连发的不同消息静默吞掉（无任何回复），表现 QQ 不接话、像被指令了一下；改为仅同内容防刷屏——3 秒内相同内容才拦截，不同消息立即并发处理（v1.166 并发池），QQ 恢复有来有回连续对话；②过程推送配额保护——QQ 主动消息有每日配额，长任务多轮工具调用逐条烧会提前耗尽配额导致中途推送全失败；单次任务过程推送（💭/🔧/✅）默认最多 5 条（可配 qq_progress_budget），超预算后静默、把配额留给最终回复（必达），微信无此限制不受影响。"),\n            '
    s = s.replace(anchor, new_entry + anchor, 1)
    io.open(p, "w", encoding="utf-8").write(s)
    print(f"✅ {p} -> 插入 v1.181 日志条目")
else:
    print(f"⚠️ {p} 跳过（anchor 未找到或已插入）")

print("done")