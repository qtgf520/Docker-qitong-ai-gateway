# 🚀 綦桐AI网关 Docker 版 — AI 开发代理自用指南

> ⚡ **本文件用途**：给 AI 开发代理（自己）看的操作手册。**只存操作方法、流程、踩坑教训，绝不存任何敏感信息**（token/密码/密钥/证书，一个都不写）。
> ⚡ **最高优先级**：每次迭代必须先读本文件再动手。违反流程 = 回滚重来。

---

## 0. 每次开发前的强制启动序列（缺一不可）

1. **🧠 查记忆**：`query_memory` 加载相关上下文（最近版本干了啥、有啥坑）
2. **📖 读本指南**：确认项目结构、版本号、部署方式没变
3. **💾 做备份**：`mkdir -p backup_YYYYMMDD_本次主题 && cp 要改的文件 $D/`
   - 改哪个文件就备份哪个文件（榜样式：`cp xxx.kt backup_日期_主题/`）
   - **对照备份法（原版 APP 指南精髓）**：备份文件是「已知能编译通过」的参照物。改代码时**打开备份文件对照结构**（花括号/函数边界/缩进），照着备份逻辑改现有文件——这样不会出现花括号错乱、函数被吃
   - 7 天前的旧备份自动清理：`find . -maxdepth 1 -name 'backup_*' -mtime +7 -exec rm -rf {} +`
4. **✅ 核状态**：`git status -s` 确认工作区干净或知道自己改到哪
5. **🔢 升版本号**：**先升版本号，再改代码，再编译部署**（顺序不能反！）

> 📌 **从原版 DEV_GUIDE_v18 吸收的开发铁律（Docker 版适用部分）**
> 1. **改前先备份单个文件**（对照备份法）
> 2. **编译报错先恢复 .bak**，禁止 `git checkout`/`git restore`（会丢本地修改）
> 3. **改代码优先用工具编辑**（edit_file/python），避免手动替换格式错乱
> 4. **合并外部代码时先对比再合**：解压 → diff 共有文件 → 确认无冲突 → 只吸收我们缺的优化 → 编译验证（v44 教训：网友版无新功能，先 diff 再动）
> 5. **Git 提交前清理**：删 .bak、临时脚本、backup_*；git status 查敏感文件

---

## 1. 项目概览

| 项 | 值 |
|---|---|
| 工作区根路径 | `/data/user/0/com.ai.assistance.operit/files/workspace/docker-qitong-111` |
| 类型 | Kotlin(JVM) + Ktor 3.1 + SQLite，单 fatJar 跑 Docker |
| 源码位置 | `src/main/kotlin/com/qitong/gateway/` |
| 前端 | Kotlin 内联 HTML/JS（WebUi.kt 页面+CSS，AdminJs1~6.kt 前端逻辑） |
| 构建工具 | 系统 gradle（9.1.0，**工作区没有 gradlew**，用 `gradle` 命令） |
| 产物 | `build/libs/qitong-gateway-all.jar`（fatJar） |
| 端口 | 18080 Web 后台 / 18889 网关 API |
| 服务器 | 103.23.149.10，部署目录 `/opt/qitong-gateway/` |
| GitHub | qtgf520/Docker-qitong-ai-gateway（本地 remote 已嵌 token，直接 push） |
| 版本号格式 | `v1.N`（N 从 1 开始每次开发 +1，绝不重复；2026-10-06 起弃用旧 3.18.22-N，全部迁移为 1.N） |

---

## 2. 版本号规则（用户最在意，忘一次骂一次）

### 硬规则
- **每次功能改动/修复/文档调整，必须先升版本号再部署**。顺序：升号 → 改码 → 编译 → 验证 → 部署
- 当前基准版本见 `src/main/kotlin/com/qitong/gateway/model/Models.kt` 的 `val version`
- **版本号格式（2026-10-06 起强制）**：`1.N`（N 从 1 开始递增：1.1、1.2、…、1.99、1.100…）
  - 旧格式 `3.18.22-N` 已全部迁移为 `1.N`（3.18.22-1→1.1、3.18.22-2→1.2、…、3.18.22-99→1.99）
  - **3.18.22 前缀已彻底移除**，现在版本号就是 `1.N`（如 v1.99）
  - 以后每次升版本号：`1.99` → `1.100`（即 N 部分 +1）
- 版本号分散在 **7 处**，全部要同步改：
  1. `model/Models.kt:188` — `val version`
  2. `Main.kt:103` — 文件头注释
  3. `Main.kt:115` — 启动横幅
  4. `Main.kt:192` — /health 接口
  5. `Main.kt:1708` — 其他版本输出
  6. `Main.kt:2460` — 其他版本输出
  7. `WebUi.kt:12` — `private const val VER`
  - 外加 `README.md` 更新日志（CHANGELOG 顶部插新条目）+ `build.gradle.kts:10` `version`（jar 产物名）
- **批量升级命令（新格式）**：
  ```bash
  # 从 1.99 升到 1.100：
  grep -rl '1.99' src/main/kotlin/ README.md build.gradle.kts | xargs sed -i 's/1.99/1.100/g'
  ```
  ⚠️ 会连 `.bak`/备份文件一起改，无妨，最后清理备份即可。
  ⚠️ **注意**：批量替换必须用「精确旧版本号」替换（如 1.99 → 1.100），不要用通配/前缀模糊替换，避免误改历史版本号（如把 v1.9 改成 v1.100）。
- CHANGELOG 条目格式：`### v1.N（日期）· 一句话主题` + 每条改动一行 `- xxx`
- **更新日志三处同步（每次发版必做，用户靠「关于网关」页看更新了啥）**：
  1. `README.md` CHANGELOG 顶部插新条目（最新在上，旧在下）
  2. `db/Database.kt` 的 `seedUpdateLogsIfEmpty()` 列表最上方加同名条目（v1.100 起每次启动自动补齐/更新，与 README 一致）
  3. GitHub Release body 写同一版本说明
  - ⚠️ **禁止只改一处**：README/关于页/Release 三处必须全同步，用户三个入口看到的一致
  - ⚠️ 批量升版本号后必查：sed 会把 changelog 顶部版本号也改了，要确认条目内容与新版本匹配（v1.99→v1.100 踩过：内容还是旧版描述）
- **发布版规则（每次发版必做，用户靠它收更新通知）**：
  1. 部署验证通过后先推代码：`git push origin master`
  2. 打 tag 推送：`git tag v1.N` + `git push origin v1.N`
  3. **同步创建 GitHub Release**（API 自动建，无需附文件）：POST https://api.github.com/repos/qtgf520/Docker-qitong-ai-gateway/releases
     ```json
     {"tag_name":"v1.N","name":"v1.N","body":"綦桐AI网关 Docker 版 v1.N\n\n<版本一句话说明>","draft":false,"prerelease":false}
     ```
     认证：`Authorization: Bearer <remote中的token>`（remote URL 里 `用户名:token@`，取冒号后到 @ 前的部分）
     ⚠️ tag 统一带 `v` 前缀（v1.N），不要建无前缀的 `1.N`（踩过坑：无前缀会重复+难管理）
  4. 这样 GitHub Releases 页会全版本可见，Watch 用户收到更新通知

---

## 3. 开发修改流程（对照备份法）

1. 先 `grep`/`grep_context` 定位相关代码，**理解上下文再动手**（禁止盲改）
2. 备份要改的文件
3. 改动尽量用 `edit_file` 工具；但**涉及多行删除/复杂转义时，用 python/sed 比 edit_file 稳**
4. **多段行号删除的坑**：必须**从后往前删**（先删行号大的段），正序删除会因行号漂移误删相邻代码！
   ```python
   # 正确姿势：从后往前
   for (start,end) in [(大段),(中段),(小段)]:
       del lines[start-1:end]
   ```
5. 删代码前用 `grep -n` 确认边界行内容，删后立即 `read_file_part` 验证上下文没被切断
6. 删错就从 `.bak`/backup 恢复：`cp backup_xxx/文件.kt 文件.kt`（**禁止 git checkout 恢复，会丢本地其他修改**）

---

## 4. 编译

```bash
cd /data/user/0/com.ai.assistance.operit/files/workspace/docker-qitong-111
gradle fatJar --rerun-tasks   # ★ 必须 --rerun-tasks！
```

### 血泪教训
- **`--rerun-tasks` 必加**：不加的话 gradle 缓存会导致 AdminJs/WebUi 前端改动**不进 jar**，线上还是旧 UI，用户会炸
- 编译成功标志：`BUILD SUCCESSFUL`
- 产物：`build/libs/qitong-gateway-all.jar`
- 编译前确认 `version` 已升、无 `git status` 未提交的临时测试文件

---

## 5. 部署（Docker 服务器）

### 流程（本地构建 → scp 传 jar → 服务器换 jar → 重建容器）

**① 本地 scp 上传 jar**（用 hk_server_key 私钥，免密）：
```bash
cd 工作区
JAR=$(ls build/libs/qitong-gateway-*-all.jar | head -1)
scp -i /root/.ssh/hk_server_key -o StrictHostKeyChecking=no -o ConnectTimeout=10 \
  "$JAR" root@103.23.149.10:/opt/qitong-gateway/qitong-gateway.jar.new
```

**② 服务器换 jar + 重建容器**（用 linux_ssh 通道执行，通道已配置免密）：
```bash
cd /opt/qitong-gateway
cp qitong-gateway.jar qitong-gateway.jar.bak_v旧版_$(date +%Y%m%d_%H%M%S)
mv qitong-gateway.jar.new qitong-gateway.jar
docker compose up -d --build    # ★ 必须 --build，否则不 COPY 新 jar！
```

### 血泪教训
- **`docker compose up -d --force-recreate` 不会重新 COPY jar**！必须 `--build`（或 `update --force` + `up -d --build`）
- 服务器 `/opt/qitong-gateway/` 是源码+jar 混合目录，但**服务器无 git 仓库、无 gradle**，只能靠换 jar 部署，别在服务器上拉代码构建
- 部署后等 10~15 秒（容器启动+健康检查），再验证 /health

---

## 6. 验证（告诉用户"好了"之前必须做）

### ① 后端接口验证（服务器上）
```bash
curl -s http://localhost:18889/health
# 期望 {"status":"ok","version":"N",...}
curl -s http://localhost:18080/ | grep -o 'N' | head -1
docker ps --filter name=qitong-ai-gateway --format '{{.Status}}'   # 要 (healthy)
```

### ② 前端 UI 验证（★ 静态 HTML 检查，不用登录！）
```bash
# admin 页面是内联 JS，直接抓下来 grep 关键字符串即可验证前端改动是否进 jar
curl -s -m 30 http://103.23.149.10:18080/admin -o /tmp/check.html
grep -o 'N' /tmp/check.html | head -1          # 版本号
grep -c '要删除的类名' /tmp/check.html                  # 应为 0（清理验证）
grep -c '要新增的类名' /tmp/check.html                  # 应 >0（新增验证）
grep -c 'data-page="skills"' /tmp/check.html           # 菜单入口应 =1
```

### ③ 血泪教训
- **❌ 禁止反复用登录接口试密码/跑 token 验证**（会触发登录失败告警，用户骂"你在乱跑token"）
- ✅ 前端验证一律走**静态 HTML grep**，又快又安全
- playwright 可用但浏览器二进制在 `/root/.cache/ms-playwright/chromium-1228/chrome-linux/chrome`，登录选择器是 `#lgUser/#lgPass/.btn[onclick="doLogin()"]`

---

## 7. Git 操作

```bash
cd 工作区
# 提交前清理测试脚本 + 备份
rm -f qt_*.js                    # 临时 playwright 脚本
find . -name '*.bak' -delete     # 单文件备份
git status -s                    # 确认无敏感文件（jks/token/apk）
# 提交（本地）
git add -A
git -c user.name='qtgf520' -c user.email='qtgf520@github.com' commit -m 'feat/fix(分类): 说明'
# 推送 GitHub（remote 已嵌 token，直接推）
git push origin master
```

- 提交信息规范：`feat(ui): ...` / `fix(settings): ...` / `docs: ...`
- 推送成功标志：`e95f416..09f51f2 master -> master`
- ⚠️ 敏感文件（*.jks/*.keystore/*.idsig/*.apk/*.aab）**禁止 commit**，提交前 git status 检查

### bundle 中转（备用，一般不用）
本地无外网直推时：`git bundle create /tmp/b.bundle --all` → scp 到服务器 `/tmp/` → 服务器 `git clone /tmp/b.bundle /tmp/qitong-git` → 但服务器**没有 GitHub token**，所以**直接用本地 git push 更省事**。

---

## 8. 踩坑记录（血泪教训汇总，必须反复看）

| # | 坑 | 后果 | 正确做法 |
|---|---|---|---|
| 1 | **版本号忘了升** | 用户发现线上没变，狂骂 | 先升号再部署，7 处全同步 |
| 2 | **gradle 不加 --rerun-tasks** | 前端改动没进 jar，线上旧 UI | fatJar 必加 --rerun-tasks |
| 3 | **docker compose 只 up -d --force-recreate** | 不 COPY 新 jar，白部署 | 必须 `--build` |
| 4 | **python 正序删多段** | 行号漂移误删相邻代码 | 从后往前删，删后验证上下文 |
| 5 | **反复登录试密码验证 UI** | 触发登录失败告警+乱跑 token | 静态 HTML grep 验证 |
| 6 | **JSON.stringify 裸引号传对象** | onclick 属性提前闭合，弹窗打不开 | 传 ID，函数内重新拉取；引号用 " |
| 7 | **edit_file 与终端转义不一致** | 替换"没变化"或误转义 | 复杂改动用 python/sed 文件级操作 |
| 8 | **heredoc 在 terminal 里被 bash 吃掉** | node -e 等带引号脚本报 syntax error | 写成 .js 文件再 node 执行 |
| 9 | **QQ 机器人 Token 鉴权** | 官方 2026-07 已废弃 token，登录不上 | AppID+AppSecret 换 AccessToken |
| 10 | **强制故障池已在池中不移位** | 点灯无效"啥也没变" | 无条件 remove+add(0) 置首位 |
| 11 | **聊天页做成后台侧边栏样式** | 用户要的是聊天页本身全屏 | 左会话列表+右消息区+顶部菜单 |
| 12 | **删了侧边栏"技能"菜单入口** | 用户说的是管理页内容不是菜单，狂骂 | 管理页内容可删，**菜单入口绝不删** |
| 13 | **QQ"创建终端"被 AI 当闲聊** | 返回创建成功但终端里没有 | 关键词硬编码接入 TerminalManager |
| 14 | **浏览器缓存看不到新 UI** | 用户报"线上没有"实际是缓存 | 让用户 Ctrl+F5 强刷 |
| 15 | **服务器 /tmp/qitong-v17-clone 会消失** | 服务器重启后临时目录没了 | 不依赖服务器临时 git，直接本地 push |
| 16 | **全局 volatile 变量存请求上下文** | 万人并发流式响应期间 label 被其他请求覆盖→按密钥统计串号/不同步 | 请求上下文（apiKeyLabel 等）必须参数传递，禁止全局变量 |
| 17 | **记忆只对绑定账号用户生效** | 微信未绑定用户"没记忆"（QQ 有微信没有） | 未绑定用户按 openid 走独立记忆通道（tags=wx:{openid}，对齐 QQ） |
| 18 | **扣费金额 toFixed(2) 显示** | 小额扣费（<¥0.005）显示成 ¥0.00→用户以为扣费没记录 | 金额 <0.005 用 toFixed(4) 动态精度显示 |
| 19 | **群聊只有成员级上下文无群级记忆** | 群话题记不住、跨天群聊丢上下文 | 加群级公共记忆（tags=group:{groupOpenid}，注入+沉淀+管理接口） |
| 20 | **机器人停止/新消息被串行锁堵住** | 用户发停止要等旧任务跑完才响应；发新消息被旧任务排队堵住 | 每用户维护 activeCalls（OkHttp Call 可 cancel）+ userJobs（协程 Job 可 cancel）；停止=立即 cancel，新消息=取消旧任务立即接上下文继续干 |
| 21 | **OKHttp 是同步阻塞 API** | askModel 里 execute() 阻塞线程，不能直接抛 CancellationException 中断 | 用 activeCalls[user]?.cancel() 记录并取消正在执行的 Call，模型调用处注册/注销 |
| 22 | **Agent 循环轮数太少（5-8轮）** | 复杂多步任务干不完就停，用户以为"卡了" | 轮数提到 20；单轮模型等待 90s（长思考）；长任务每 30s 推一次进度提示 |
| 23 | **沙盒函数权限表与 KNOWN_FNS 不一致** | 模型调用函数名对不上→功能静默失败 | 加函数必须三处同步：KNOWN_FNS + 权限表 + KNOWLEDGE_JSON + when(fn) 实现 |
| 24 | **页面重划删除卡片后残留 JS 回调** | 等异步回调操作已删 DOM 元素→空指针/白屏 | 删除页面卡片时同步删其渲染回调，或回调用 if(el) 判空 |
| 25 | **heredoc 在 terminal 里引号出错** | python <<'PY' 含双引号嵌套报 syntax error | 复杂脚本用 create_file 写 .py 文件再执行，别用 heredoc 硬塞 |
| 26 | **GitHub API 建 Release 401 Bad credentials** | remote URL 是 `qtgf520:token@` 格式，正则把用户名+token 一起当 token | 解析 remote 用 `https://(?:[^:]+:)?([^@]+)@github` 只取冒号后 token |
| 27 | **版本号漏改 build.gradle.kts** | 只改 Main/Models/WebUi 的版本，jar 文件名还是旧版 | 发版前 grep 全部版本位置：Main.kt(3处)+Models.kt+WebUi VER+build.gradle.kts version |
| 28 | **聊天页 CSS 全屏贴合** | 只改 .chat-layout 高度不够，父容器 .content 有 padding/卡片留白 | `.content:has(#chatLayout){padding:0!important}` + `#view-chat .chat-layout{height:calc(100dvh-52px)}`；移动端减底部导航 116px |

---

## 9. 结构速查

| 文件/目录 | 职责 |
|---|---|
| `WebUi.kt` | 登录页/后台 HTML + **全部 CSS**（聊天全屏样式在这） |
| `AdminJs1.kt` | 核心框架 + 首页 + 服务商 |
| `AdminJs2.kt` | 聊天页前端（会话/消息/标题/模型下拉） |
| `AdminJs3.kt` | 设置页 + 记忆 + 用户管理 |
| `AdminJs4.kt` | 主题切换 |
| `AdminJs5.kt` | QQ 机器人页 |
| `AdminJs6.kt` | 技能库 / 工作流 / MCP 管理 |
| `Main.kt` | 全部路由 + 认证 + 版本号 |
| `http/AdminApi.kt` | 输入校验 + 响应封装 |
| `http/SkillExecutor.kt` | 技能执行（600001 查状态等） |
| `http/TerminalManager.kt` | Web 终端会话（临时） |
| `db/Database.kt` | SQLite 全部表 + CRUD |
| `qq/QqBotManager.kt` | QQ 机器人网关/插件/提醒/游戏 |
| `model/Models.kt` | 配置项 + **版本号** |

---

## 10. 设计原则（UI 偏好，用户原话归纳）

- 主色靛蓝 `#4F46E5`、青 `#06B6D4`、深底 `#0F172A`、卡片 `#1E293B`
- 聊天页参考**全屏聊天应用**：左侧会话列表（带头像）+ 右侧消息区 + 顶部模型/菜单，输入框固定底部
- 管理页内容与菜单入口是两回事：**内容可清理，菜单入口保留**
- 邀请只留个人中心、技能只留独立页（但侧边栏菜单都保留）
- 所有页面数据按当前登录用户隔离（统计/排行榜/密钥/记忆）
- API 出错返回 OpenAI 风格错误体，不要 500
- 测速后台持续跑带进度条，离开页面不中断

---

## 11. 收尾清单（每次交付前逐项打勾）

- [ ] 版本号 7 处 + README 已升且无残留旧号
- [ ] 编译 `BUILD SUCCESSFUL`（带 --rerun-tasks）
- [ ] jar 已 scp 到服务器并 `docker compose up -d --build`
- [ ] /health 版本号正确 + 容器 healthy
- [ ] 前端静态 HTML grep 验证（新增类>0 / 删除类=0 / 菜单入口=1）
- [ ] 服务器备份 tgz 已生成
- [ ] Git 提交 + push 成功（无敏感文件）
- [ ] 测试脚本/备份已清理（7天旧备份自动清）
- [ ] 记忆已存档（extended_memory_tools:create_memory）

> 文档版本：v1（2026-09-29 建立，v1.29 之后）
> 更新规则：每次踩新坑、改流程，第一时间补进本文件并 git 提交。