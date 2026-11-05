#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""v1.190 更新日志插入（README.md + Database.kt）"""
import io

# 1) README.md 顶部插入 v1.190 条目
p = "README.md"
s = io.open(p, encoding="utf-8").read()
anchor = "## v1.189"
entry = """## v1.190
**后台实时沙盒状态完善（模型回复/任务完成实时刷新）** —— 面板不再一直"等待模型回复"：

- **模型回复实时上报**：QQ/微信每次模型回复到达（含首次+多轮工具循环内），后台实时刷新为"模型已回复：内容摘要"
- **任务完成实时收尾**：QQ/微信 Agent 循环跑完（全部收尾路径：closeBody/清洗/普通返回），后台立即标记"✅ 任务完成"——不再一直 running
- **面板展示**：复用 currentAction/toolResult 字段，"等模型回复"→"模型已回复：…"→"✅ 任务完成"，全程实时可见

"""
if anchor in s and "## v1.190" not in s:
    s = s.replace(anchor, entry + anchor, 1)
    io.open(p, "w", encoding="utf-8").write(s)
    print(f"✅ {p} -> 插入 v1.190 条目")
else:
    print(f"⚠️ {p} 跳过")

# 2) Database.kt 版本日志
p = "src/main/kotlin/com/qitong/gateway/db/Database.kt"
s = io.open(p, encoding="utf-8").read()
anchor = 'Triple("v1.189"'
if anchor in s and 'Triple("v1.190"' not in s:
    new_entry = '            Triple("v1.190", "后台实时沙盒状态完善（模型回复/任务完成实时刷新）", "面板不再一直等待模型回复：①模型回复实时上报——QQ/微信每次模型回复到达（含首次+多轮工具循环内）后台实时刷新为模型已回复内容摘要；②任务完成实时收尾——QQ/微信 Agent 循环跑完（全部收尾路径 closeBody/清洗/普通返回）后台立即标记任务完成，不再一直 running；③面板展示——复用 currentAction/toolResult 字段，等模型回复→模型已回复→任务完成全程实时可见。"),\n            '
    s = s.replace(anchor, new_entry + anchor, 1)
    io.open(p, "w", encoding="utf-8").write(s)
    print(f"✅ {p} -> 插入 v1.190 日志条目")
else:
    print(f"⚠️ {p} 跳过")

print("done")