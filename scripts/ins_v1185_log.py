#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""v1.185 更新日志插入（README.md + Database.kt）"""
import io

# 1) README.md 顶部插入 v1.185 条目（在 v1.184 条目之前）
p = "README.md"
s = io.open(p, encoding="utf-8").read()
anchor = "## v1.184"
entry = """## v1.185
**进度模式一句话控制 + 智能体全量管理权限** —— 聊天更简洁、网关全部设置一句话搞定：

**① 进度模式（隐藏/显示步骤，按用户+渠道隔离）**
- 对 QQ/微信说「隐藏步骤/别发步骤/只回结果/简洁模式」→ 开启简洁模式：干活过程不再推送，只回最终结果
- 说「显示步骤/打开步骤/完整模式」→ 恢复实时过程推送（💭→🔧→✅）
- 按绑定用户存储（progress_mode），QQ/微信各自生效

**② 智能体管理权限补齐（沙盒新增 3 函数）**
- `provider_sync`：同步服务商模型（管理员）
- `model_hide` / `model_unhide`：按当前用户隐藏/恢复模型（用户级，自己管自己可见的）
- 已注册 KNOWN_FNS / 权限表 / 知识库三处同步，qtai-sj 可直接自主调用

"""
if anchor in s and "## v1.185" not in s:
    s = s.replace(anchor, entry + anchor, 1)
    io.open(p, "w", encoding="utf-8").write(s)
    print(f"✅ {p} -> 插入 v1.185 条目")
else:
    print(f"⚠️ {p} 跳过（anchor 未找到或已插入）")

# 2) Database.kt 版本日志（在 v1.184 条目之前插入）
p = "src/main/kotlin/com/qitong/gateway/db/Database.kt"
s = io.open(p, encoding="utf-8").read()
anchor = 'Triple("v1.184"'
if anchor in s and 'Triple("v1.185"' not in s:
    new_entry = '            Triple("v1.185", "进度模式一句话控制 + 智能体全量管理权限", "①进度模式——对 QQ/微信说「隐藏步骤/别发步骤/只回结果/简洁模式」开启简洁模式：干活过程不再推送只回最终结果；说「显示步骤/打开步骤/完整模式」恢复实时过程推送；按绑定用户存储 progress_mode，QQ/微信各自生效，msg语法兼容多种口语表达；②智能体管理权限补齐——沙盒新增 provider_sync（同步服务商模型，管理员）、model_hide/model_unhide（按当前用户隐藏/恢复模型，用户级），已注册 KNOWN_FNS/权限表/KNOWLEDGE_JSON 三处同步，qtai-sj 可直接自主调用，网关全部设置一句话搞定。"),\n            '
    s = s.replace(anchor, new_entry + anchor, 1)
    io.open(p, "w", encoding="utf-8").write(s)
    print(f"✅ {p} -> 插入 v1.185 日志条目")
else:
    print(f"⚠️ {p} 跳过（anchor 未找到或已插入）")

print("done")