#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""v1.187 更新日志插入（README.md + Database.kt）"""
import io

# 1) README.md 顶部插入 v1.187 条目
p = "README.md"
s = io.open(p, encoding="utf-8").read()
anchor = "## v1.186"
entry = """## v1.187
**节点域名体系（自定义接入域名，首页隐藏 IP 优先显示节点）** —— 让网关接入地址告别裸 IP：

- **节点域名管理**：新增 `node_domains` 表 + `/api/nodes` CRUD——域名 / 端口（默认 80 无需填写，443/8443 自动 https）/ 国家 / 说明 / 启停 / 设为默认 / 删除 / 编辑
- **自动获取**：`POST /api/nodes/auto-detect` 一键把当前访问域名加入节点（首次安装引导：无节点时首页顶部提示"添加绑定域名"，支持自动获取或手动填写）
- **首页接入实例**：有节点时默认显示节点域名（隐藏 IP），多个节点全部列出可复制，带 ☆默认 / 国家 / 说明 / ·当前标记；无节点才显示 IP 兜底

"""
if anchor in s and "## v1.187" not in s:
    s = s.replace(anchor, entry + anchor, 1)
    io.open(p, "w", encoding="utf-8").write(s)
    print(f"✅ {p} -> 插入 v1.187 条目")
else:
    print(f"⚠️ {p} 跳过")

# 2) Database.kt 版本日志
p = "src/main/kotlin/com/qitong/gateway/db/Database.kt"
s = io.open(p, encoding="utf-8").read()
anchor = 'Triple("v1.186"'
if anchor in s and 'Triple("v1.187"' not in s:
    new_entry = '            Triple("v1.187", "节点域名体系（自定义接入域名，首页隐藏IP优先显示节点）", "新增 node_domains 表 + /api/nodes CRUD：域名/端口（默认80无需填写，443/8443自动https）/国家/说明/启停/设为默认/删除/编辑；POST /api/nodes/auto-detect 一键把当前访问域名加入节点（首次安装引导：无节点时首页顶部提示添加绑定域名，支持自动获取或手动填写）；首页接入实例有节点时默认显示节点域名（隐藏IP），多个节点全部列出可复制，带☆默认/国家/说明/当前标记，无节点才显示IP兜底。"),\n            '
    s = s.replace(anchor, new_entry + anchor, 1)
    io.open(p, "w", encoding="utf-8").write(s)
    print(f"✅ {p} -> 插入 v1.187 日志条目")
else:
    print(f"⚠️ {p} 跳过")

print("done")