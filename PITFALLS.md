# 🕳 綦桐AI网关 · 历史踩坑避坑指南（PITFALLS）

> ⚡ **用途**：把历史上出现过的所有错误/坑/血泪教训集中归档，**每次开发前扫一眼**，遇到相似症状直接对号入座，少走弯路。
> ⚡ **与 DEV_GUIDE.md 的关系**：DEV_GUIDE 是操作流程铁律（版本号/备份/部署/验证），本文件是**错误症状→根因→解法**的对照字典，两者互补。
> ⚡ **更新规则**：每次踩新坑立即补进本文件（按分类加条目），并同步考虑是否补进 DEV_GUIDE 踩坑表。

---

## 📋 目录

1. [编译 / Kotlin 坑](#-编译--kotlin-坑)
2. [前端 JS / UI 坑](#-前端-js--ui-坑)
3. [部署 / Docker / 服务器坑](#-部署--docker--服务器坑)
4. [Git / 发布 / 开源包坑](#-git--发布--开源包坑)
5. [网关核心逻辑坑（路由/故障转移/计费）](#-网关核心逻辑坑路由故障转移计费)
6. [机器人（QQ/微信/qtai-sj）坑](#-机器人qq微信qtai-sj坑)
7. [记忆 / 上下文坑](#-记忆--上下文坑)
8. [工具调用 / Operit 环境坑](#-工具调用--operit-环境坑)
9. [吉利屋 PHP / 跨项目坑](#-吉利屋-php--跨项目坑)
10. [Android APP / Kotlin 移动端坑](#-android-app--kotlin-移动端坑)

---

## 🛠 编译 / Kotlin 坑

| # | 症状 | 根因 | 正确做法 |
|---|------|------|----------|
| C1 | **编译报 Missing '}' + Unclosed comment，连带大量 Unresolved reference（看似其他文件坏了）** | Kotlin 块注释**支持嵌套**，注释里写 `/*`（如路径描述 `/v1/images/*`）会开启内层注释永不闭合，导致 resolveModelId 等后续代码全部错位 | 注释里**严禁出现 `/*` 组合**，路径描述改写成 `images接口`；改注释后编译报奇怪 Missing '}' 先 grep 注释里的 `/*`（v1.161 排查 40+ 分钟的血泪） |
| C2 | **Kotlin private 类被 public 函数暴露** | public fun 参数用了 private class → 编译报 exposes its private-in-class parameter type | 内部辅助函数全部 private，仅留对外入口 public（v1.105 限流统计） |
| C3 | **编译成功但线上是旧代码** | gradle 缓存没重编 | fatJar 必加 `--rerun-tasks`（DEV_GUIDE 踩坑2） |
| C4 | **python 正序删多段代码** | 行号漂移误删相邻代码 | 从后往前删（先删行号大的段），删后 read_file_part 验证上下文 |
| C5 | **改代码用 sed 锚点脚本批量替换** | 锚点对不上导致误改（v1.159/1.160 README changelog 被误改） | 直接用编辑工具/edit_file，复杂多行改动用 python 文件级替换（DEV_GUIDE 踩坑7） |
| C6 | **版本号漏改某处** | 只改部分文件，jar 文件名/前端显示旧版 | 发版前 grep 全部版本位置：Main.kt(3处)+Models.kt+WebUi VER+build.gradle.kts version+README+Database seedUpdateLogs（DEV_GUIDE 踩坑27） |

---

## 🎨 前端 JS / UI 坑

| # | 症状 | 根因 | 正确做法 |
|---|------|------|----------|
| F1 | **onclick 引号嵌套炸 SyntaxError** | JSON.stringify+unescapeHtml 多层引号地狱 | 全局存储数组 + onclick 只传索引（`_msgCopyStore.push(content)` + `copyChatMsg(i)`）（踩坑32） |
| F2 | **JSON.stringify 裸引号传对象** | onclick 属性提前闭合，弹窗打不开 | 传 ID，函数内重新拉取；引号用 "（踩坑6） |
| F3 | **页面重划删除卡片后残留 JS 回调** | 异步回调操作已删 DOM 元素 → 空指针/白屏 | 删除页面卡片时同步删其渲染回调，或回调用 if(el) 判空（踩坑24） |
| F4 | **前端序列化 data class 输出 toString** | content_blocks 返回 `ContentBlock(...)` 而非 JSON | encodeElement 不认识自定义 data class；必须提供 toMap() 递归转换（踩坑29） |
| F5 | **Markdown 任务清单被无序列表吞** | `- [x]` 被 md-ul 正则先匹配吃掉 | 任务清单正则在无序/有序列表**之前**处理（踩坑33） |
| F6 | **AdminJs 改完 node --check 报错** | 嵌套回调漏括号（loaders.memory 改三视图时 meRes 回调没闭合） | 改前端 AdminJs 后必须提取 JS 做 node --check 全量语法校验（v1.161 教训，AdminJs5 白屏两次教训） |
| F7 | **聊天页 CSS 高度不够** | 只改 .chat-layout 高度不够，父容器有 padding/卡片留白 | `.content:has(#chatLayout){padding:0!important}` + 高度 calc；移动端减底部导航（踩坑28） |
| F8 | **删页面没删干净** | 删菜单+视图+loader 三件套后残留引用导致切页报错 | 删后用 grep 复查 `loaders.xxx\|view-xxx\|data-page="xxx"` 应为 0（踩坑38） |
| F9 | **页面数据没按用户隔离** | 管理员/普通用户看到别人数据 | 所有页面数据按当前登录用户隔离（统计/排行榜/密钥/记忆），管理员可看全部但优先自己的（v1.105/v1.161） |

---

## 🚀 部署 / Docker / 服务器坑

| # | 症状 | 根因 | 正确做法 |
|---|------|------|----------|
| D1 | **docker compose 重建后还是旧版** | `up -d --force-recreate` 不重新 COPY jar | 必须 `docker compose up -d --build`（踩坑3） |
| D2 | **服务器 sshd 间歇性握手限流** | 22 端口 open 但 ssh 卡在 connect，频繁重试加重 | 等 60~180s 自动恢复；加大 ConnectTimeout；部署用 gzip 管道（`gzip -c jar | ssh 'cat > g.gz && gunzip && mv'`）最抗抖动（v1.141+ 血泪） |
| D3 | **scp 大文件被 Connection closed** | 服务器限流/连接不稳定 | 用 gzip 管道备用方案（v1.160 实测最稳） |
| D4 | **部署后验证要等启动** | 容器启动+健康检查需要时间 | 部署后等 10~15 秒再验证 /health |
| D5 | **服务器 /tmp 临时目录会消失** | 服务器重启后临时文件没了 | 不依赖服务器临时 git/文件，直接本地 push/本地备份（踩坑15） |
| D6 | **本地库没模型** | 本地空库 qtai-sj 返回 No available model | 从正式库导出 providers+models JSON，本地按 id 映射重插（踩坑36） |
| D7 | **模型同步走代理通道失败** | syncProviderModels 固定强制走代理客户端 | 直连优先+代理兜底双通道，SyncResult 返回真实错误（v1.148） |

---

## 📦 Git / 发布 / 开源包坑

| # | 症状 | 根因 | 正确做法 |
|---|------|------|----------|
| G1 | **GitHub API 建 Release 401** | remote URL `qtgf520:token@` 正则把用户名+token 一起当 token | 解析用 `https://(?:[^:]+:)?([^@]+)@github` 只取冒号后 token（踩坑26） |
| G2 | **失败补丁脚本被 git add 误提交** | 补丁脚本 assert 失败但文件还在工作区 | git add 前 rm -f qt_*.py 临时脚本；误提交用 git rm --cached + commit --amend（踩坑34） |
| G3 | **开源包带出敏感信息** | DEV_GUIDE 样例写了真实密码/git 私钥文件名 | DEV_GUIDE 任何样例只写占位符；发版前 grep 全包敏感词清零（踩坑43） |
| G4 | **注释残留外部项目名** | 借鉴代码后注释留着来源名 | 落地后全局 grep 已知来源名清单清零，统一自研描述（踩坑30） |
| G5 | **tag 重复/无前缀** | 建了无 v 前缀的 tag | tag 统一带 `v` 前缀（v1.N），发布后同步 GitHub Release（DEV_GUIDE 发布版规则） |
| G6 | **内网 git push 要密码** | gitdev 仓库用 GIT_SSH_COMMAND 不带密码卡在 password | `SSHPASS=<内网git密码> GIT_SSH_COMMAND='sshpass -e ssh -o StrictHostKeyChecking=no' git push ...`（踩坑31） |
| G7 | **内网 git 仓库路径记错** | 误以为在 /home/git/ | 正确路径 `ssh://gitdev@qitong.pve.jili5.cn:6062/home/gitdev/docker-qitong-111.git`（踩坑42） |

---

## ⚙️ 网关核心逻辑坑（路由/故障转移/计费）

| # | 症状 | 根因 | 正确做法 |
|---|------|------|----------|
| N1 | **具体模型请求被强制池接管** | v1.159 把强制池逻辑应用到所有请求 | 只有原始请求是 qtai-sj 才整体走强制池；具体模型=请求模型置首+测速排行候选（v1.160） |
| N2 | **故障转移 body 里 model 名没换** | forwardToUpstream 的 finalBody 只认 qtai-sj 才替换 | 只要 targetModel.modelId != bodyModel 就 replaceModelInBody（v1.160 核心根因） |
| N3 | **上游超时过长导致卡顿/断连** | connectTimeout 60s/readTimeout 120s 太久 | 缩短：connect 8s、流式 read 60s、非流式 read 20s/callTimeout 40s（v1.160） |
| N4 | **请求不存在的模型卡 30s** | 逐个试坏模型 connect 8s+read 20s | 候选先只放健康模型（测速排行），primary 不健康不置首（v1.160） |
| N5 | **流式 readTimeout(0)=无限等待卡死** | 上游挂起不吐数据客户端无限等 | 流式 readTimeout 300s 兜底 + 每次上游尝试 withTimeoutOrNull 320s（踩坑39） |
| N6 | **静态路由（模态路由）不生效** | detectModality 只扫 messages，图片/视频/语音/嵌入请求体无 messages | 按 path 识别模态 + 静态路由支持 providerId::modelId 精确匹配 + 无 model 字段自动注入（v1.161） |
| N7 | **强制故障池已在池中不移位** | 点灯无效"啥也没变" | 无条件 remove+add(0) 置首位（踩坑10） |
| N8 | **额度版用户白嫖** | 付费预检只覆盖余额版用户 | 预检条件 `quotaLimit<=0 || quotaUsed>=quotaLimit`，区分额度用尽/余额不足两套文案（v1.135） |
| N9 | **充值/扣款权限漏洞** | 沙盒 user_recharge 的 isAdmin 参数仅展示用 | 执行时查 userId 角色，敏感操作必须有管理员/代理身份（v1.110） |
| N10 | **扣费金额显示 ¥0.00** | 小额扣费 toFixed(2) 显示 | 金额 <0.005 用 toFixed(4) 动态精度（踩坑18） |
| N11 | **sanitizeRequestBody 拼接坏 JSON** | bodyStr2+sb 两个 JSON 拼接 | 直接对同一 sb 变量做所有替换，返回单个完整 JSON（踩坑41） |
| N12 | **渠道分组/模型映射/表达式计费三件套没生效** | 新增功能没进调用链 | 加功能必须全链路同步：getVisibleModels + buildAttemptModels + 前端（v1.157 经验） |

---

## 🤖 机器人（QQ/微信/qtai-sj）坑

| # | 症状 | 根因 | 正确做法 |
|---|------|------|----------|
| B1 | **用户发"停止"后永远收不到回复** | QQ 停止信号不清理（stopSignals 永久 true） | 停止处理后 stopSignals.remove(openid)（v1.134） |
| B2 | **微信长文被截断/尾巴丢失** | splitMessage 分段函数 cur.append(自身) 重复追加后 cur.clear() | 去掉重复 append，确保 clear() 前所有内容已推送（v1.133） |
| B3 | **微信工具执行崩掉整个 AI** | SandboxEngine.execute 抛异常无保护 | 单工具 try/catch，返回「工具执行异常（fn）：msg」（v1.134） |
| B4 | **微信 hist 重复记录用户消息** | 循环内记一次、循环外又 add 一次 | 只记一次（userTextLogged 标记）（v1.134） |
| B5 | **QQ 多工具并行一个崩全崩** | executor.submit 内 execute 无异常保护 | 每个 submit 内部 try/catch（v1.134） |
| B6 | **Agent 循环轮数太少** | 5-8 轮复杂任务干不完就停 | 轮数 20；单轮等待 90s；长任务每 30s 推进度（踩坑22） |
| B7 | **微信长轮询首轮成功不置 ONLINE** | `if (backoffMs > 2000L)` 才置 ONLINE | 长轮询无异常返回即置 ONLINE（踩坑40） |
| B8 | **机器人掉线一直登不上** | 无自动重连/退避不对 | 连续失败10次固定60s周期，连上复位，掉线自动恢复（v1.150） |
| B9 | **元宝 Bot 掉线** | 只发协议层 ping，服务端不认 | 必须每 5s 发应用层 ConnMsg cmd=ping；收到 Push 必须回 PushAck（v1.119c） |
| B10 | **微信多 bot 记忆串号** | 记忆/上下文只用 openid 隔离 | tags 加 botId 维度（wx:{botId}:{openid}），history key 也带 botId（v1.161） |
| B11 | **机器人回复慢** | 单轮超时太久 | 单轮 60s 快速失败触发故障转移（v1.156/v1.161） |
| B12 | **QQ"创建终端"被 AI 当闲聊** | 关键词没硬编码接入 | 关键词硬编码接入 TerminalManager（踩坑13） |

---

## 🧠 记忆 / 上下文坑

| # | 症状 | 根因 | 正确做法 |
|---|------|------|----------|
| M1 | **记忆只对绑定账号用户生效** | 微信未绑定用户没记忆 | 未绑定用户按 openid 走独立记忆通道（tags=wx:{openid}，对齐 QQ）（踩坑17） |
| M2 | **群聊记不住跨天上下文** | 只有成员级上下文无群级记忆 | 加群级公共记忆（tags=group:{groupOpenid}，注入+沉淀+管理接口）（踩坑19） |
| M3 | **管理员记忆页显示全部记忆串了** | getAllMemories 无过滤 | 管理员默认只显示自己的账号记忆；可选全部来源/按用户查（uid/openid/botId）（v1.161） |
| M4 | **记忆刷页面就没了** | 只存内存 | gateway_logs 表持久化，先渲染最近10条历史再实时增量（v1.159） |
| M5 | **记忆配置不生效** | persona/memory_config 未联动 | 默认启用（未建人格也注入），qtai-sj 同样有记忆（v47 经验） |

---

## 🔧 工具调用 / Operit 环境坑

| # | 症状 | 根因 | 正确做法 |
|---|------|------|----------|
| T1 | **报 path/command required** | DSML 包装格式（<||DSML||> invoke/parameter/calls）Operit 渲染失败，参数丢失 | 用标准 `<tool name="..."><param name="...">值</param></tool>` 格式，单条短命令、命令前不堆文字（2026-10-16 复现修复） |
| T2 | **工具调用输出被截断** | 单次输出过长（大段正文+工具调用混在一起）触发 Operit 截断保护 | 每次只发一条短工具调用，不在前面堆大段文字（v1.135 自检定位） |
| T3 | **heredoc 在 terminal 里被 bash 吃掉** | node -e 等带引号脚本报 syntax error | 写成 .js/.py 文件再执行（踩坑8/25） |
| T4 | **grep 命令挂起无返回** | 特定路径/命令偶发卡住 | 用最简单的命令直接执行，不加多余参数；换执行方式（v1.133 教训） |
| T5 | **工具调用参数格式乱** | package_proxy 的 tool_name/params 位置错 | tool_name 放外层、params 只放命令；关屏用 super_admin:shell input keyevent（v1.161 后经验） |

---

## 🌐 吉利屋 PHP / 跨项目坑

| # | 症状 | 根因 | 正确做法 |
|---|------|------|----------|
| P1 | **聊天消息自己跑左边** | JS 严格比较 `msg.user_id === currentUserId` 类型不一致（字符串 vs 数字）恒 false | 类型强转 + 用户名兜底（2026-09-21 liaotian.php） |
| P2 | **PHP 8.3 能跑但线上 5.6 报错** | 老代码用 each/mysql_*/短标签 | 加 compat56.php 垫片层；本地 8.3 能跑≠5.6 能跑，上传前确认兼容 |
| P3 | **线上文件上传前没备份** | 改坏了无法回滚 | 上传前拉取线上当前文件与本地比对，FTP 备份原版到 /tmp |
| P4 | **页面卡出/输入框不显示** | 超大内嵌资源/jQuery 双版本冲突 | head 334KB→28KB 提取 CSS；删除 jQuery 双版本之一 |
| P5 | **手机访问 127.0.0.1 是手机自身** | 网络隔离（Termux 页面空白根因） | 判网络隔离而非 JS 报错；run-as com.termux 可绕过权限直接操作 |

---

## 📱 Android APP / Kotlin 移动端坑

| # | 症状 | 根因 | 正确做法 |
|---|------|------|----------|
| A1 | **聊天中用户发的字不显示** | isEditing/正常态文本渲染被错误嵌入 if(others) 块内（花括号错位） | isEditing 块移到 others 块外面与附件并列（Kai2 v3.2.3-3） |
| A2 | **思考区空白不逐字显示** | visibleChars 以 remember(fullText, animate) 为 key，fullText 每次流式更新重置为 0；alreadyTyped 只初始化一次 | 移除 alreadyTyped 门控，visibleChars 以 animate 为唯一 key（Kai2 v3.2.6） |
| A3 | **流式动画/长列表卡顿** | 每次重组全量重建 | 动画 key 最小化，remember 粒度精确 |

---

## 🎯 高频铁律（每次必做，血泪最多）

1. **先升版本号再改代码再部署**（顺序不能反，7 处全同步）
2. **改前必备份**（单文件 .bak + 当日目录 backup_日期_主题），编译报错先恢复 .bak，**禁止 git checkout 恢复**（会丢本地修改）
3. **编译必带 --rerun-tasks，部署必带 --build**
4. **Kotlin 注释里绝不写 `/*`**
5. **前端 AdminJs 改后必 node --check**
6. **工具调用用标准 <tool>/<param> 格式，单条短命令**
7. **借鉴外部代码落地后 grep 来源名清零，绝不暴露借鉴来源**
8. **发版后开源包落盘 Download + unzip -t 双重验证 + 敏感词扫描**
9. **记忆存档（extended_memory_tools:create_memory）每次发版必做**
10. **DEV_GUIDE/PITFALLS 每次踩新坑立即补充并 git 提交**

---

> 文档版本：v1（2026-10-25 建立，汇总 DEV_GUIDE 44 条踩坑 + 历史记忆全部血泪）
> 更新规则：每次踩新坑、改流程，第一时间补进本文件并 git 提交。
