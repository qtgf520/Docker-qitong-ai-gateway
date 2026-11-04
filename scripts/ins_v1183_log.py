#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""v1.183 更新日志插入（README.md + Database.kt）"""
import io

# 1) README.md 顶部插入 v1.183 条目（在 v1.182 条目之前）
p = "README.md"
s = io.open(p, encoding="utf-8").read()
anchor = "## v1.182"
entry = """## v1.183
**记忆沉淀事件接入（MEMORY_SAVED 发布 → 首页系统动态）** —— 事件总线四项事件全部闭环：

- **三渠道统一发布**：在记忆落库统一入口 `addMemory` 发布 `memory.saved` 事件（内置聊天/QQ/微信所有记忆沉淀全覆盖，一次接入），携带 userId/title/content/type/importance/source
- **订阅→系统动态**：高价值长期记忆（importance>=7，如 v1.177 个人信息）实时写入首页「⚡系统动态」——"记住用户"一目了然
- **可扩展**：任意新增插件/记忆分析/用户画像订阅 `memory.saved` 即可联动
- **至此事件总线四项事件全部闭环**：message.received / message.replied / tool.executed / memory.saved

"""
if anchor in s and "## v1.183" not in s:
    s = s.replace(anchor, entry + anchor, 1)
    io.open(p, "w", encoding="utf-8").write(s)
    print(f"✅ {p} -> 插入 v1.183 条目")
else:
    print(f"⚠️ {p} 跳过（anchor 未找到或已插入）")

# 2) Database.kt 版本日志（在 v1.182 条目之前插入）
p = "src/main/kotlin/com/qitong/gateway/db/Database.kt"
s = io.open(p, encoding="utf-8").read()
anchor = 'Triple("v1.182"'
if anchor in s and 'Triple("v1.183"' not in s:
    new_entry = '            Triple("v1.183", "记忆沉淀事件接入（MEMORY_SAVED 发布 → 首页系统动态）", "事件总线四项事件全部闭环：①三渠道统一发布——在记忆落库统一入口 addMemory 发布 memory.saved 事件（内置聊天/QQ/微信所有记忆沉淀全覆盖，一次接入），携带 userId/title/content/type/importance/source；②订阅→系统动态——高价值长期记忆（importance>=7，如 v1.177 个人信息）实时写入首页系统动态，记住用户一目了然；③可扩展——任意新增插件/记忆分析/用户画像订阅 memory.saved 即可联动；至此事件总线四项事件全部闭环：message.received/message.replied/tool.executed/memory.saved。"),\n            '
    s = s.replace(anchor, new_entry + anchor, 1)
    io.open(p, "w", encoding="utf-8").write(s)
    print(f"✅ {p} -> 插入 v1.183 日志条目")
else:
    print(f"⚠️ {p} 跳过（anchor 未找到或已插入）")

print("done")