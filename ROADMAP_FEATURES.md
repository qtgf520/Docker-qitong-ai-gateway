# 綦桐AI网关 · 功能增强规划（借鉴自研，UI 全自绘）

> 本文件只列「可迁移的能力清单与实现思路」，用于网关后续迭代 roadmap。不提及来源。
> 原则：**能力照搬、代码自研、UI 原创**，更新日志与对外文案绝不提借鉴对象。

## 一、现状（v1.123 网关已有能力）
- 多服务商管理（OpenAI Compatible / Ollama / Custom）
- 多模型管理（启停 / 授权 / 多模态标注 / 单价）
- 故障转移（强制池 + 健康排序 + ★ v1.123 按模态优先）
- 内置聊天（结构化流水渲染 / Markdown 增强 / 一键复制）
- 用量统计、密钥管理、路由规则、QQ/微信/元宝机器人、技能/工作流/MCP
- 首页性能监控（10s 实时）、异常告警（钉钉/邮箱基础版）

## 二、待补功能清单（按价值排序，均已有成熟参考实现可借鉴）

### P0 高价值
1. **计划任务（Cron）**：定时执行 Shell/HTTP/数据库备份/监控
   - 表：cronjob(id,name,schedule,type,target,status,last_run,next_run)
   - 实现：Java 自带 ScheduledExecutorService + cron 表达式解析
   - 用途：定时测速、定时备份数据库、定时巡检模型可用性
2. **系统监控（Monitor）**：CPU/内存/磁盘/网络/进程核心指标 + 历史曲线
   - 表：monitor(ts,cpu,mem,disk,net_in,net_out)
   - 实现：oshi 库或 /proc 解析，每 60s 采集，保留 7 天
3. **备份/快照（Backup/Snapshot）**：一键备份 SQLite + 配置 + 恢复
   - sqlite .backup + 配置 json 导出，支持保留 N 份自动清理
4. **告警规则（Alert）**：阈值告警（CPU>90% / 模型失败率 / 余额不足）
   - 通知渠道：钉钉 Webhook / 邮箱 / 企微机器人（网关已有部分）
   - 表：alert_rule(name,metric,op,threshold,channel,enabled)

### P1 运营增强
5. **操作日志（OpLog）**：登录/配置变更/模型操作审计（已部分有，补全分页+筛选）
6. **文件管理（File）**：受限目录文件浏览/上传/下载/编辑（部署目录专用）
7. **通知频道统一化**：钉钉/邮箱/企微/飞书 四合一配置中心
8. **账号安全**：两步验证、登录 IP 白名单、异常登录告警

### P2 生态接入
9. **API 密钥多角色**：只读密钥/管理密钥/受限密钥（3 级）
10. **模型健康巡检**：定时自动测速 + 自动禁用连续失败的模型
11. **SQLite 备份到对象存储**：支持 S3/OSS 兼容端点
12. **灰度发布**：模型新版本按比例分流（10% 用户走新模型）

## 三、OpenCode 接入网关（已跑通 ✅）
- 环境已装 `opencode-ai@1.18.30`（npm 全局）
- opencode 自定义 provider 配置（全局 `~/.config/opencode/opencode.json`）：
  ```json
  "provider": { "qitong-gateway": {
    "npm": "@ai-sdk/openai-compatible",
    "options": { "baseURL": "http://127.0.0.1:18889/v1", "apiKey": "{env:QITONG_GATEWAY_KEY}" },
    "models": { "qtai-sj": { "name": "qtai-sj（自动切换）" } }
  }}
  ```
- 设定环境变量 `QITONG_GATEWAY_KEY` 后再用：`opencode run '任务' -m qitong-gateway/qtai-sj`
- 效果：opencode 挂载网关 → qtai-sj 自动故障转移 → 走 156 模型池中健康模型
- ✅ 已实测：opencode 经网关成功生成 Kotlin 类并落盘

## 四、使用方式备忘
- `opencode run '写xxx' -m opencode/nemotron-3.5-lightning-free`（免费模型，无需 key）
- `opencode run '写xxx' -m qitong-gateway/qtai-sj`（走网关自动切换，需 QITONG_GATEWAY_KEY）
- `opencode`（TUI 交互模式）
- 配置：`~/.config/opencode/opencode.json`
- 版本：1.18.30（2026-10-13 实测）

## 五、合规红线
- ✅ 借鉴能力、**绝不**复制源码/文件头/注释
- ✅ 对外（README/Release/群通知/前端）**零来源名**
- ✅ 密钥/token 绝不入工作区仓库、绝不进开源包