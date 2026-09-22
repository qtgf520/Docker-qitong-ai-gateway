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

**⭐ 如果这个项目对你有帮助，请给个 Star 支持！**