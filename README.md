# ⚡ 綦桐AI网关 · Docker 服务器版

> **QiTong AI Gateway · Docker Server**
> 把「綦桐AI网关」安卓版完整功能搬到服务器，一键 Docker 部署！

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Docker](https://img.shields.io/badge/Docker-✅%20Compose-2496ED)](compose.yaml)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.3.10-purple)](build.gradle.kts)
[![Ktor](https://img.shields.io/badge/Ktor-3.1.0-orange)](build.gradle.kts)

---

## 🚀 简介

**綦桐AI网关 Docker版** 是安卓版綦桐AI网关的服务器版实现，把手机上的本地 AI API 网关完整移植为可在任意服务器/VPS 上运行的 Docker 服务。

- 统一管理多个 AI 服务商（OpenAI / DeepSeek / Claude / Ollama / 中转）和模型
- 对外提供 **OpenAI / Claude / Gemini 三大格式** 的兼容 API（16+ 接口）
- 内置 **Web 管理后台**：服务商、模型、测速排行、强制故障池、API密钥、路由规则、用量统计、用户管理、个人人格配置
- **智能故障转移**：模型失败自动切换池内下一个可用模型
- **qtai-sj 虚拟模型**：自动选最快可用模型，全网关可用
- 深色科技风 UI，一比一对齐安卓版主题（靛蓝 #4F46E5 / 青 #06B6D4 / 深底 #0F172A）
- 手机/电脑全自适应：移动端汉堡菜单 + 底部导航，桌面端侧边导航

## ✨ 功能特性

| 功能 | 说明 |
|:-----|:------|
| 🔑 **API密钥管理** | 独立密钥管理，每把钥匙可控制模型权限、qtai-sj 访问、启停、编辑 |
| 📡 **完整API适配** | OpenAI / Claude / Gemini 三大格式，16 个接口全兼容 |
| 🚀 **qtai-sj 测速** | 虚拟模型自动选最快，带健康缓存 |
| 🧠 **人格系统** | 自定义名字/年龄/性格/语气/背景 + 大五人格维度滑块 + 记忆开关 |
| 🎯 **强制故障池** | 多模型绑定，故障自动切换，池灯显示当前活跃模型 |
| 🔄 **智能故障转移** | 自动测速（5分钟~4小时间隔）+ 失败切换 |
| 🔌 **多服务商管理** | OpenAI / DeepSeek / Claude / Ollama / Custom，CRUD 齐全 |
| 🌐 **代理支持** | HTTP/HTTPS/SOCKS5 代理配置（UpstreamClient） |
| 💬 **内置聊天** | 完整会话管理，对话气泡，调真实模型 |
| 📊 **用量统计** | 按模型统计 tokens/流量/调用次数 |
| 📋 **地址显示** | 首页展示网关地址（IP:端口），一键复制 |
| 👥 **用户管理** | 用户增删改、角色、额度限制、绑定模型、重置密码 |
| 🌐 **Web后台** | 深色科技风，桌面+移动全自适应 |

## 🐳 快速部署

### 方式一：Docker Compose（推荐）

```bash
# 1. 克隆
git clone https://github.com/qtgf520/Docker-qitong-ai-gateway.git
cd Docker-qitong-ai-gateway

# 2. 构建镜像（本地已编译 jar 则跳过）
docker compose build --no-cache

# 3. 启动
docker compose up -d

# 4. 查看状态
docker compose ps
```

启动后：
- 🌐 **Web后台**: `http://服务器IP:18080`
- ⚡ **网关API**: `http://服务器IP:18889/v1`

默认管理员：首次启动自动创建（登录后请在「设置 → 修改密码」中修改）

### 方式二：直接运行 jar

```bash
# 需要 JDK 17+
java -jar qitong-gateway.jar
# 环境变量：WEB_PORT=18080 GATEWAY_PORT=18889 DB_PATH=/data/qitong/gateway.db
```

### start.sh 一键管理

```bash
./start.sh up      # 构建并启动
./start.sh down    # 停止
./start.sh logs    # 查看日志
./start.sh status  # 查看状态
```

## 🔌 API 接口

### 网关接口（OpenAI 兼容，`http://IP:18889/v1`）

| 接口 | 方法 | 说明 |
|:-----|:-----|:-----|
| `/v1/models` | GET | 模型列表（含 qtai-sj） |
| `/v1/chat/completions` | POST | 对话补全（流式 SSE） |
| `/v1/completions` | POST | 文本补全 |
| `/v1/messages` | POST | Claude 消息格式 |
| `/v1/embeddings` | POST | 嵌入向量 |
| `/v1/rerank` | POST | 重排序 |
| `/v1/moderations` | POST | 内容审核 |
| `/v1/audio/speech` | POST | 文本转语音 |
| `/v1/images/generations` | POST | 图像生成 |
| `/v1/videos` | POST | 视频生成（同步） |
| `/v1/video/generations` | POST | 视频任务（异步） |
| `/v1beta/models/{model}:generateContent` | POST | Gemini 格式 |
| `/v1/realtime` | WS | 实时语音 |
| `/health` | GET | 健康检查 |

> 所有接口支持 `model: "qtai-sj"` 自动解析为当前活跃模型；支持密钥验证（Authorization: Bearer xxxx）。

### 后台接口（`http://IP:18080/api`）

服务商、模型、测速、密钥、路由规则、用量、用户、人格、聊天、配置、网关启停... 共 30+ 接口，详见源码 `Main.kt`。

## 🏗️ 技术栈

- **语言**：Kotlin 100% (JVM)
- **服务端**：Ktor Server 3.1.0 (Netty)
- **HTTP**：OkHttp 4.12.0
- **数据库**：SQLite (JDBC)
- **序列化**：Kotlinx Serialization
- **密码**：BCrypt
- **构建**：Gradle 9.1.0 / JDK 17
- **部署**：Docker + Docker Compose

## 📁 项目结构

```
├── src/main/kotlin/com/qitong/gateway/
│   ├── Main.kt              # 入口 + 全部路由
│   ├── WebUi.kt             # Web UI（深色科技风）
│   ├── AdminJs1.kt          # 后台JS：框架+首页+服务商
│   ├── AdminJs2.kt          # 后台JS：模型+测速+聊天
│   ├── AdminJs3.kt          # 后台JS：密钥+规则+用量+设置+用户
│   ├── auth/AuthManager.kt  # 注册/登录/改密
│   ├── db/Database.kt       # SQLite 数据层
│   ├── http/                # 网关代理+调度+规则
│   ├── model/Models.kt      # 数据模型
│   └── network/             # 上游客户端+SOCKS5
├── build.gradle.kts
├── compose.yaml
├── Dockerfile
└── start.sh
```

## 🛡️ 安全注意

- 首次登录后**立即修改默认管理员密码**
- 如需对外提供 API，请在后台开启「API密钥校验」并生成密钥
- 生产环境建议通过 nginx 反向代理 + HTTPS 对外暴露

## 📄 License

[Apache License 2.0](LICENSE)

```
Copyright 2026 綦桐 (qtgf520)
```

> 此项目为安卓版「綦桐AI网关」的服务器版移植，开源仅供学习交流。

## 📮 联系方式

- 官方QQ群：1007488535 💬
- GitHub: [qtgf520/Docker-qitong-ai-gateway](https://github.com/qtgf520/Docker-qitong-ai-gateway)

---

## 📝 更新日志（CHANGELOG）

### v3.18.22-16（2026-09-24）· 全模型故障转移 + 个人中心 + 绑定邮箱
- 🔄 **全模型故障转移**：选择任意模型（DeepSeek/GLM等）失败时，自动跳转排行榜其他能跑的模型（不只 qtai-sj）；除非全部不可用才返回
- 💬 **中文友好提示**：全部失败时返回"所有上游模型均不可用，已自动尝试：xxx。请稍后重试或检查服务商配置"，绝不空白断开
- 👤 **个人中心**（用户+管理员各有自己的）：个人资料 / 昵称 / 余额 / 累计充值 / 邀请码 / 注册时间
- 📧 **绑定邮箱**：个人中心可绑定邮箱 + 开启邮件提醒开关（后期提醒用）
- 👥 **用户中心（管理员）**：用户管理表格新增邮箱列，管理员可看到每个用户的绑定邮箱
- 📱 底部导航新增"我的"，侧边栏新增"个人中心"

### v3.18.22-15（2026-09-24）· 点灯强制切换 + 用户级隔离 + 通知系统
- 💡 **点灯=强制切换**：排行榜点池灯/池卡片点击 → 强制切换到此模型（置为池首位 + 活跃模型），qtai-sj 优先走它；池灯绿色=当前活跃、黄色=池内备用
- 🔒 **用户级隔离**：每个 key 属主的强制池/活跃模型完全独立，互不串；管理员=全局池，用户=自己的池；qtai-sj 按用户 key 解析对应活跃模型
- 🎫 **工单/注册通知**：新用户注册、新工单提交自动通知管理员
- 🔔 **通知系统**：设置页新增「通知设置」卡（仅管理员可见）——钉钉 Webhook（对齐 dingtalk-notification.php）+ 邮箱 SMTP（支持 SSL），含发送测试按钮
- 🔄 **技能增强**：查网关状态/切换模型/上个/下个/清强制全部按用户隔离执行；技能结果返回真实活跃模型

### v3.18.22-14（2026-09-23）· 流式修复 + 聊天外键修复
- ⚡ 修复 qtai-sj 流式返回空白（SSE 字节直通，不再逐行抠 usage）
- 💬 修复内置聊天 conversationId=0 外键约束 500

### v3.18.22-13（2026-09-23）· 公告/工单/操作日志/记忆配置/权限管控
- 📢 **公告系统**：管理员发布/编辑/置顶/删除公告，首页顶部展示公告（所有用户可见）
- 🎫 **工单中心**：用户提交工单，管理员可查看全部+回复+关闭，聊天式对话界面
- 📜 **全部操作日志**：记录登录/发布公告/提交工单等关键操作（仅管理员可见）
- 🧠 **记忆配置**：对齐原APP MemoryConfig——记忆开关/保存模式/共情力/思考深度/口头禅/禁用词/专业领域/沟通风格 + **模型独立记忆开关**
- 🔒 **权限管控**：网关设置卡仅管理员可见；普通用户隐藏 admin-only 菜单；操作日志/公告管理/用户管理仅管理员
- 📥 **Git/URL 导入技能**：自定义技能支持从 Git raw / 任意 JSON URL 一键导入
- 🧠 **记忆注入聊天**：内置聊天自动注入用户历史记忆（按配置启用，模型独立时按当前模型过滤）
- ℹ️ **关于我们**：对齐原APP（应用信息/核心功能/联系方式）

### v3.18.22-12（2026-09-23）· 测速逐个动态显示 + 传输明细
- ⚡ **测速页面**：默认显示全部已启用模型（待测速态）→ 逐个测速逐个显示（TTFT/TPS/总耗时实时刷新）
- 📊 **传输明细**：每次 API 调用一条记录（模型/Prompt/输出/总Token/上行/下行/费用/密钥/时间），对齐原APP TokenUsage
- 🔧 修复流式传输字节为-1、token全为0的统计缺陷

### v3.18.22-11（2026-09-23）· 备份导入导出 + 自定义技能UI
- 💾 数据备份导入导出（APP↔线上JSON互传）
- 🧰 自定义技能 UI（添加/删除/触发词）
- ⚡ 测速排行动态显示 + 5分钟倒计时

### v3.18.22-10（2026-09-22）· 最终版
- 服务商/模型列表显示属主用户名
- MCP 控制接口（status/setbrain/setactive/speedtest/toggle/forced）

### v3.18.22-9（2026-09-22）· 人格技能系统
- SkillRegistry 技能池 30+ 技能 + SkillExecutor 执行器
- 聊天注入技能提示 → AI 回复带【指令】→ 自动执行

### v3.18.22-8（2026-09-22）· 多语言 + 远程控制
- 15种语言切换（按用户独立）
- 远程控制：喊人格名直接触发大脑绑定模型

### v3.18.22-7（2026-09-22）· 大脑记忆 + qtai-sj绑定
- brain_memory 表按用户隔离 + 设置页记忆管理
- qtai-sj 大脑绑定（按用户）

### v3.18.22-6（2026-09-22）· 内置聊天修复
- 会话按用户隔离 + 可见模型过滤 + 人格注入

### v3.18.22-5（2026-09-22）· 免登录 + 分销 + 手动扣款
- sessions 表 7 天免登录 + 分销系统（邀请码+返佣）+ 管理员手动扣款

### v3.18.22-4（2026-09-22）· 多租户核心隔离
- 用户级启停/强制池/自动测速 + 排行榜可见性过滤 + Key属主显示

### v3.18.22-3（2026-09-22）· 商业化
- 余额/充值/扣款 + 模型定价 + Key脱敏私有化

### v3.18.22-2（2026-09-22）· 多租户权限管控
- 8 个权限位细粒度授权

### v3.18.22-1（2026-09-22）· 三指标测速 + 强制池 + 模型启停
- TTFT/TPS/总耗时测速器 + 强制故障池 + 模型启停

### v3.18.22（2026-09-22）· 首个 Docker 版
- Ktor 双端口（网关18889 + 后台18080）
- 深色科技风 UI（对齐安卓版主题）
- 服务商/模型/测速/密钥/路由/用量/用户管理

---

**⭐ 如果这个项目对你有帮助，请给个 Star 支持！**