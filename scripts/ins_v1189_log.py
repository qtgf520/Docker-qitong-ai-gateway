#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""v1.189 更新日志插入（README.md + Database.kt）"""
import io

# 1) README.md 顶部插入 v1.189 条目
p = "README.md"
s = io.open(p, encoding="utf-8").read()
anchor = "## v1.188"
entry = """## v1.189
**双模式智能体规约（闲聊自然陪聊 + 任务自主 Agent，无缝切换人格不变）** —— 让网关智能体能像真人一样"该聊就聊、该干就干"：

- **最高优先级规则注入**：作为 System Prompt 最前段，长对话也不会被冲刷——每轮先自检"闲聊还是任务"
- **模式A 日常陪聊**：聊天/倾诉/唠嗑/生活话题 → 保持人格自然回应，绝不调工具、不列功能清单
- **模式B Agent 自主执行**：修bug/写代码/网关配置/查余额/部署/排查/运维等任务 → 自动 ReAct 循环（拆解→调真实工具→评估→收尾），**完成任务但人格底色不变**（口语化有温度，不机械冰冷）
- **专属场景**：查余额调 user_balance 拿真实数值；机器人对接排查输出适配网关的修复方案

"""
if anchor in s and "## v1.189" not in s:
    s = s.replace(anchor, entry + anchor, 1)
    io.open(p, "w", encoding="utf-8").write(s)
    print(f"✅ {p} -> 插入 v1.189 条目")
else:
    print(f"⚠️ {p} 跳过")

# 2) Database.kt 版本日志
p = "src/main/kotlin/com/qitong/gateway/db/Database.kt"
s = io.open(p, encoding="utf-8").read()
anchor = 'Triple("v1.188"'
if anchor in s and 'Triple("v1.189"' not in s:
    new_entry = '            Triple("v1.189", "双模式智能体规约（闲聊自然陪聊 + 任务自主Agent，无缝切换人格不变）", "让网关智能体能像真人一样该聊就聊、该干就干：①最高优先级规则注入——作为 System Prompt 最前段，长对话也不会被冲刷，每轮先自检闲聊还是任务；②模式A日常陪聊——聊天/倾诉/唠嗑/生活话题保持人格自然回应，绝不调工具不列功能清单；③模式B Agent自主执行——修bug/写代码/网关配置/查余额/部署/排查/运维等任务自动 ReAct 循环（拆解→调真实工具→评估→收尾），完成任务但人格底色不变（口语化有温度不机械冰冷）；④专属场景——查余额调 user_balance 拿真实数值，机器人对接排查输出适配网关的修复方案。"),\n            '
    s = s.replace(anchor, new_entry + anchor, 1)
    io.open(p, "w", encoding="utf-8").write(s)
    print(f"✅ {p} -> 插入 v1.189 日志条目")
else:
    print(f"⚠️ {p} 跳过")

print("done")