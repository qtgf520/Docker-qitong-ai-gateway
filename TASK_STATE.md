# TASK_STATE · P0-A/B/C + P1 稳定性三连（v1.235 已上线）+ v1.236（已构建/已推送/待部署）

## ✅ v1.236（对齐 AstrBot 的三件事，代码已 push，线上待部署）
- 模型 -1 永久免费：列表徽章「永久免费」+ 编辑弹窗三个快捷键（-1 / 0 / 1）；计费侧本就 -1→0 不扣费，之前只是显示成"默认价"让人以为没设置成功
- 插件：新增「🔗 从 URL 安装」（GitHub 仓库自动转 archive zip，zip 直链也行，单层目录自动拍平），并修中文插件名被压成 ___ 互相覆盖的 bug
- 插件在 QQ / 微信渠道真正生效（原先只接了 Telegram，等于白装）
- 官方插件市场：market/plugins.json + 3 个可用插件（签到/摸鱼/夸夸），后台市场默认地址指向本站
- 服务商「官方预设」一键填：DeepSeek/智谱/Kimi/百炼/硅基流动/火山/ MiniMax/千帆/混元/OpenRouter/OpenAI/Ollama
- 人格模板：QQ/微信 bot 设置里五个一键人格（小助理/鲸鱼娘/工程师/温柔陪聊/毒舌搭子）

## ✅ P1-1 发送幂等（v1.233，commit 见下）
- `http/SendGuard.kt`：同 (渠道,目标,msgId,内容哈希) 5 分钟内只发一次；QQ dispatch 分段发送前守卫
- 目的：修「同一条消息回两遍」

## ✅ P1-2 长任务不再被 90s 砍断（v1.234）
- 看门狗 90s → `AgentLimits.MAX_SECONDS_QQ/WX = 300s`（chat 600s），超时保留阶段结果并提示「回继续」
- 长任务心跳：超过 60s 每 60s 推「⏳ 还在处理（第N步）」
- QQ 模型单轮 read/call 超时 60s → 120s（慢模型不再被误杀）

## ✅ P1-3 掉线自愈 + 重复消息根因（v1.235）
- `http/InboundGuard.kt`：同渠道同 msgId 只处理一次（QQ dispatch / 微信 handleMessage 入口）
- 微信长轮询单实例化：`WeixinGatewayClient.stop()` 中断旧线程；`stopBot` 等旧线程退出（≤3s）
- 微信看门狗 60s → 30s 巡检 + 连续 2 次异常才重连（避免双连接并发收消息）
- 微信客户端 backoff 保持 2s→60s，成功即重置

> 记录时间：2026-11-22 02:32
> 状态：✅ 全部完成并上线，收尾清理完毕

## ✅ P0-A 记忆自动检索（已上线）
- commit `18dc3c2`（含 P0-A + P0-B 代码 + TASK_STATE 首版）
- `KBEngine.search` BM25 检索 topK=3 + score>=0.35 + 缓存 5min
- 三渠道（chat/qq/weixin）接入，命中【相关记忆】注入
- 线上验证：`[MemoryRetriever] hits=2 top=1.73 cost=18ms`

## ✅ P0-B AgentRunTracker 持久化（已上线）
- commit 同 `18dc3c2`
- `agent_runs` 表 + `upsertRun/listRunsByStatus/markRunsInterrupted`
- 启动时 running→interrupted（本地冒烟验证：`chat-test-crash-1` 重启后变 interrupted 并被查询到）
- `/api/agent/runs?include=interrupted` 支持

## ✅ P0-C Agent 循环统一限额（已上线）
- commit `cd2454d`（feat(agent): P0-C 单轮工具预算/总预算/轮数常量，4 files +94/-6）
- `AgentLimits.kt`：MAX_ROUNDS_QQ=20 / MAX_ROUNDS_WX=20 / MAX_ROUNDS_CHAT=8 / MAX_TOOLS_PER_ROUND=8 / MAX_TOOLS_PER_RUN=24
- 三渠道循环：take(MAX_TOOLS_PER_ROUND) 单轮截断 + skippedCount + runToolCount 总预算跳出 + 轮数常量引用

## ✅ 线上发布记录（服务器 103.23.149.10 /opt/qitong-gateway）
| 版本 | 发布 | 容器 | 备份 jar |
|---|---|---|---|
| P0-A v1.231 | DEPLOY_OK | healthy | qitong-gateway.jar.bak.1795283247 |
| P0-B v1.231 | DEPLOY_OK | healthy | qitong-gateway.jar.bak.1795284299 |
| P0-C v1.231 | DEPLOY_OK | healthy | qitong-gateway.jar.bak.1795285641 |

## ✅ Git
- 远端 master = `cd2454df897e0febbc6ef398f4809cff75d1a9c7`（直连 push 成功 a2ce87d..cd2454d）
- 本地 HEAD：`cd2454d`

## ✅ 清理
- 本地 18094 冒烟实例已 kill（PID 2465 / 1499，剩余 0）
- /tmp/smoke_p0b /tmp/smoke_data 已删除
- 编译/发布/冒烟临时日志已清理

## 下一步
- push 已直连完成无需中转；后续新需求直接开发