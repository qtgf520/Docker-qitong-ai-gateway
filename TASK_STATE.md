# TASK_STATE · P0-A 记忆自动检索

> 记录时间：2026-11-22 01:08
> 状态：接入完成，待编译验证

## 已完成
- [x] `kb/MemoryRetriever.kt`（object MemoryRetriever，retrieve(db,userId,query)）
  - KBEngine.search BM25 检索 topK=3，score>=0.35 保留，跨库合并去重取前 3
  - 【相关记忆】段落注入，总长<=600 token（KBEngine.estimateTokens 截断）
  - ConcurrentHashMap 缓存 key=userId+归一化query，TTL 5 分钟
  - 全逻辑 runCatching 包裹，异常返回空串；日志 `[MemoryRetriever] hits=N top=0.xx cost=Xms`
- [x] 三渠道接入
  - `http/AdminApi.kt` chat：记忆注入块后（1006-1015 行），messages 无【相关记忆】才追加
  - `qq/QqBotManager.kt`：sysFull 拼接（2033-2038 行），memoryRetrSection 拼入 system
  - `weixin/WeixinBotManager.kt`：baseSys 追加（708-714 行），无【相关记忆】才注入
  - 三处均：section 为空跳过、runCatching 吞异常、不影响正常回复
- [x] `kb/KBEngine.kt` chunk 缓存：ConcurrentHashMap<Long, Pair<Long, List<...>>>，按 kbId 缓存 60 秒（17-21 行字段 + 115-123 行 search 命中/重读）
- [x] fatJar 编译：**BUILD SUCCESSFUL in 1m 2s，0 错误**
  - 产物：`build/libs/qitong-gateway-1.231-all.jar`（34M，2026-11-21 17:16 构建）
- [x] 本地冒烟（PID 23079，WEB_PORT=18094 / GATEWAY_PORT=18894 / 临时库 /tmp/smoke_data）
  - `18094/api/status` → `{"code":0,"msg":"ok","data":{"status":"ok","version":"1.231","running":true,...}}`
  - `18094/` → HTTP 200 size=8009（Web 后台正常）
  - `18894/health` → `{"status":"ok","version":"1.231","port":18894,"running":true,...}`
  - 启动日志：`[PipelineSetup] 管道装配完成 chat=4Stages qq=4Stages wx=4Stages` + `🚀 启动完成`
  - ⚠️ SocketException(SIOCGIFHWADDR) 为沙箱获取网卡 MAC 权限限制，不影响功能

## 进行中
- 本地冒烟实例保持运行（不 kill），供下一步 MemoryRetriever 命中日志断言

## 下一步
- 本地实例发一条聊天请求，断言启动日志出现 `[MemoryRetriever] hits=N top=0.xx cost=Xms`
- 验证后交付：git diff --stat + 验收证据 + 风险说明

## 阻塞
- 无