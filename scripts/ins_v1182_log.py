#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""v1.182 更新日志插入（README.md + Database.kt）"""
import io

# 1) README.md 顶部插入 v1.182 条目（在 v1.181 条目之前）
p = "README.md"
s = io.open(p, encoding="utf-8").read()
anchor = "## v1.181"
entry = """## v1.182
**工具执行事件接入（TOOL_EXECUTED 发布 → 首页系统动态实时可见）** —— 兑现事件总线完整价值：

- **三渠道统一发布**：在沙盒统一执行入口 `executeSuspend` 发布 `tool.executed` 事件（内置聊天/QQ/微信所有工具执行全覆盖，一次接入），携带 fn/args/result/userId/channel
- **订阅→系统动态**：订阅事件并把**重要工具执行**（modify 类操作 + 失败/异常）写入首页「⚡系统动态」实时可见；高频只读查询自动过滤不刷屏
- **可扩展**：任意新增插件/审计/统计订阅 `tool.executed` 即可联动，无需改核心执行链路

"""
if anchor in s and "## v1.182" not in s:
    s = s.replace(anchor, entry + anchor, 1)
    io.open(p, "w", encoding="utf-8").write(s)
    print(f"✅ {p} -> 插入 v1.182 条目")
else:
    print(f"⚠️ {p} 跳过（anchor 未找到或已插入）")

# 2) Database.kt 版本日志（在 v1.181 条目之前插入）
p = "src/main/kotlin/com/qitong/gateway/db/Database.kt"
s = io.open(p, encoding="utf-8").read()
anchor = 'Triple("v1.181"'
if anchor in s and 'Triple("v1.182"' not in s:
    new_entry = '            Triple("v1.182", "工具执行事件接入（TOOL_EXECUTED 发布 → 首页系统动态实时可见）", "兑现事件总线完整价值：①三渠道统一发布——在沙盒统一执行入口 executeSuspend 发布 tool.executed 事件（内置聊天/QQ/微信所有工具执行全覆盖，一次接入），携带 fn/args/result/userId/channel；②订阅→系统动态——订阅事件并把重要工具执行（modify 类操作 + 失败/异常）写入首页系统动态实时可见，高频只读查询自动过滤不刷屏；③可扩展——任意新增插件/审计/统计订阅 tool.executed 即可联动，无需改核心执行链路。"),\n            '
    s = s.replace(anchor, new_entry + anchor, 1)
    io.open(p, "w", encoding="utf-8").write(s)
    print(f"✅ {p} -> 插入 v1.182 日志条目")
else:
    print(f"⚠️ {p} 跳过（anchor 未找到或已插入）")

print("done")