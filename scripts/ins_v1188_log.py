#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""v1.188 更新日志插入（README.md + Database.kt）"""
import io

# 1) README.md 顶部插入 v1.188 条目
p = "README.md"
s = io.open(p, encoding="utf-8").read()
anchor = "## v1.187"
entry = """## v1.188
**Agent 卡死根治（网络工具短超时 + 循环看门狗）** —— 让智能体像真 Agent 一样"边想边干边讲、永不卡住"：

- **根因① 网络工具无限超时**：`web_search` 每次 `new OkHttpClient()`（readTimeout=0 无限超时）、`web_browse` 用 UpstreamClient（60s 长连接）→ 目标站挂起时线程永久阻塞，外层 `withTimeout(35s)` 取消不了同步阻塞 IO → Agent 循环等结果等不到 → **卡死**
- **修复①**：新增 `WebToolClient`（connect 8s / read 15s / callTimeout 20s，不重试），web_search / web_browse / image_gen 全部改用它 → 任何网络工具 20s 内必返回，配合 35s withTimeout 兜底，Agent 永不卡死
- **根因② 循环无总耗时护栏**：QQ/微信 Agent 循环最多 20 轮但轮内可长时间阻塞，卡住就无限等
- **修复②**：QQ/微信 Agent 循环新增**看门狗**——单任务 90s 强制中断并提示"任务处理超时已自动结束"，配合后台面板 90s 无进展标记，哪里卡住都能兜底收尾

"""
if anchor in s and "## v1.188" not in s:
    s = s.replace(anchor, entry + anchor, 1)
    io.open(p, "w", encoding="utf-8").write(s)
    print(f"✅ {p} -> 插入 v1.188 条目")
else:
    print(f"⚠️ {p} 跳过")

# 2) Database.kt 版本日志
p = "src/main/kotlin/com/qitong/gateway/db/Database.kt"
s = io.open(p, encoding="utf-8").read()
anchor = 'Triple("v1.187"'
if anchor in s and 'Triple("v1.188"' not in s:
    new_entry = '            Triple("v1.188", "Agent 卡死根治（网络工具短超时 + 循环看门狗）", "让智能体像真 Agent 一样边想边干边讲、永不卡住：①根因1——web_search 每次 new OkHttpClient()（readTimeout=0 无限超时）、web_browse 用 UpstreamClient（60s 长连接），目标站挂起时线程永久阻塞，外层 withTimeout(35s) 取消不了同步阻塞 IO，Agent 循环等结果等不到卡死；新增 WebToolClient（connect 8s/read 15s/callTimeout 20s 不重试），web_search/web_browse/image_gen 全部改用它，任何网络工具 20s 内必返回配合 35s withTimeout 兜底；②根因2——QQ/微信 Agent 循环最多 20 轮但轮内可长时间阻塞，卡住就无限等；QQ/微信循环新增看门狗，单任务 90s 强制中断并提示任务处理超时已自动结束，配合后台面板 90s 无进展标记，哪里卡住都能兜底收尾。"),\n            '
    s = s.replace(anchor, new_entry + anchor, 1)
    io.open(p, "w", encoding="utf-8").write(s)
    print(f"✅ {p} -> 插入 v1.188 日志条目")
else:
    print(f"⚠️ {p} 跳过")

print("done")