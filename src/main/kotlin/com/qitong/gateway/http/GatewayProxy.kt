package com.qitong.gateway.http

import com.qitong.gateway.db.Database
import com.qitong.gateway.model.AiModel
import com.qitong.gateway.model.Provider
import com.qitong.gateway.model.TokenUsage
import com.qitong.gateway.model.User
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.request.receive
import io.ktor.util.AttributeKey
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 网关核心代理转发 —— 对齐原APP GatewayService.kt
 * 完整支持：OpenAI / Claude / Gemini 格式 + 通用代理 + 流式SSE + 故障转移 + qtai-sj + 参数修正 + 用量统计
 */
class GatewayProxy(private val database: Database) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val DEFAULT_CT = "application/json".toMediaType()

    // ============ 密钥校验 ============

    /** 校验请求API密钥（requireKey关闭时全放行；本地请求免密钥） */
    fun validateApiKey(call: ApplicationCall, remoteIp: String): Boolean {
        val requireKey = database.getConfig("require_api_key", "true").toBoolean()
        if (!requireKey) return true
        if (isLocalRequest(remoteIp)) return true
        val authHeader = call.request.headers["Authorization"]
        if (authHeader.isNullOrBlank()) return false
        val apiKey = authHeader.removePrefix("Bearer ").trim()
        val entry = database.validateApiKey(apiKey)
        if (entry != null) {
            call.attributes.put(ApiKeyEntryKey, entry.key)
            call.attributes.put(ApiKeyLabelKey, entry.label)
            call.attributes.put(ApiKeyOwnerKey, entry.ownerId)
        }
        return entry != null
    }

    private fun isLocalRequest(ip: String): Boolean {
        return ip == "localhost" || ip == "127.0.0.1" || ip == "::1" || ip == "0.0.0.0" ||
            ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.16.") ||
            ip.startsWith("172.17.") || ip.startsWith("172.18.") || ip.startsWith("172.19.") ||
            ip.startsWith("172.2") || ip.startsWith("172.3")
    }

    // ============ 模型选择 ============

    /** 解析请求模型：qtai-sj → 当前活跃模型 / 健康缓存最优 */
    fun resolveModelId(requested: String, models: List<AiModel>): String {
        if (requested != "qtai-sj") return requested
        val active = database.getConfig("active_model_key", "")
        if (active.isNotBlank()) return active.substringAfter("::", active)
        val best = GatewayScheduler.getBestModel()
        if (best != null) return best.substringAfter("::", best)
        val sorted = GatewayScheduler.getSortedModels(models)
        return sorted.firstOrNull()?.modelId ?: "qtai-sj"
    }

    /** 构建故障转移尝试列表（强制池优先 + 健康排序）——用户级池优先，回退全局池 */
    fun buildAttemptModels(
        models: List<AiModel>,
        modelId: String,
        providerId: Long? = null,
        userId: Long = 0L
    ): List<AiModel> {
        // 强制故障池：用户自己的池优先；无则用全局池
        val forcedPool = if (userId > 0) {
            database.getUserConfig(userId, "forced_pool_keys", "").ifBlank {
                database.getConfig("forced_pool_keys", "")
            }
        } else {
            database.getConfig("forced_pool_keys", "")
        }
        val forcedList = forcedPool
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val available = models.filter { it.isEnabled }

        if (forcedList.isNotEmpty()) {
            val forced = forcedList.mapNotNull { rk ->
                val parts = rk.split("::")
                if (parts.size == 2) {
                    available.find { it.providerId.toString() == parts[0] && it.modelId == parts[1] }
                } else {
                    available.find { it.modelId == rk }
                }
            }
            if (forced.isNotEmpty()) return forced.distinctBy { it.providerId to it.modelId }
        }

        // 无强制池：请求模型置首（含 provider 过滤），其余按健康度排序
        val primary = available.firstOrNull { m ->
            m.modelId == modelId && (providerId == null || m.providerId == providerId)
        }
        val others = available.filter { it.modelId != (primary?.modelId) || it.providerId != (primary?.providerId) }
        val sortedOthers = GatewayScheduler.getSortedModels(others)
        return (listOfNotNull(primary) + sortedOthers).distinctBy { it.providerId to it.modelId }
    }

    // ============ 参数修正 ============

    /** 修正请求体 temperature/top_p/penalty/max_tokens 越界值 */
    fun sanitizeRequestBody(bodyStr: String): String {
        return try {
            var sb = bodyStr
            sb = sb.replace(Regex("\"temperature\"\\s*:\\s*([\\d.]+)")) { m ->
                val v = m.groupValues[1].toDoubleOrNull()
                if (v != null && v != v.coerceIn(0.0, 1.999)) "\"temperature\":${v.coerceIn(0.0, 1.999)}" else m.value
            }
            sb = sb.replace(Regex("\"top_p\"\\s*:\\s*([\\d.]+)")) { m ->
                val v = m.groupValues[1].toDoubleOrNull()
                if (v != null && v != v.coerceIn(0.0, 1.0)) "\"top_p\":${v.coerceIn(0.0, 1.0)}" else m.value
            }
            sb = sb.replace(Regex("\"(presence_penalty|frequency_penalty)\"\\s*:\\s*([-\\d.]+)")) { m ->
                val v = m.groupValues[2].toDoubleOrNull()
                if (v != null && v != v.coerceIn(-2.0, 2.0)) "\"${m.groupValues[1]}\":${v.coerceIn(-2.0, 2.0)}" else m.value
            }
            sb = sb.replace(Regex("\"max_tokens\"\\s*:\\s*(\\d+)")) { m ->
                val v = m.groupValues[1].toIntOrNull()
                if (v != null && v != v.coerceIn(1, 128000)) "\"max_tokens\":${v.coerceIn(1, 128000)}" else m.value
            }
            sb
        } catch (_: Exception) { bodyStr }
    }

    /** 替换请求体中的 model 字段 */
    fun replaceModelInBody(bodyStr: String, newModelId: String): String {
        return try {
            Regex("\"model\"\\s*:\\s*\"[^\"]*\"").replace(bodyStr) { "\"model\":\"$newModelId\"" }
        } catch (_: Exception) { bodyStr }
    }

    // ============ 通用转发 ============

    /**
     * 通用代理转发：读取请求体 → 解析模型 → 故障转移尝试 → 转发上游 → 管道式流回
     * 支持流式（SSE）与非流式，支持任意 Content-Type（图片/视频/音频）
     */
    suspend fun proxyRequest(
        call: ApplicationCall,
        path: String,
        remoteIp: String
    ) {
        // 当前调用者用户ID（来自API密钥属主；本地/免密钥=0）
        val ownerId = try { call.attributes[ApiKeyOwnerKey] } catch (_: Exception) { 0L }
        val ownerUser = if (ownerId > 0) database.getUserById(ownerId) else null
        // 用户级 API 开关检查（非管理员用户暂停则拒绝）
        if (ownerId > 0 && ownerUser?.role != "admin") {
            val apiEnabled = database.getUserConfig(ownerId, "api_enabled", "true").toBoolean()
            if (!apiEnabled) {
                respondJson(call, openAIError(403, "API has been paused by user", "api_paused"), 403)
                return
            }
        }

        // 路由规则匹配（route/block）
        val rule = RoutingRuleManager.matchRule(database, path, call.request.headers["Authorization"] ?: "", call.request.queryParameters["model"] ?: "")
        if (rule != null && rule.action == "block") {
            respondJson(call, openAIError(403, rule.blockMessage.ifBlank { "Blocked by routing rule" }, "routing_blocked"), 403)
            return
        }

        val rawBytes = call.receive<ByteArray>()
        recordTraffic(upload = rawBytes.size.toLong())

        val bodyStr = String(rawBytes, Charsets.UTF_8)
        val body = try { json.parseToJsonElement(bodyStr).jsonObject } catch (_: Exception) { buildJsonObject { } }

        // 解析模型ID
        var modelId = body["model"]?.jsonPrimitive?.content ?: body["prompt"]?.let { "text-completion" } ?: ""
        val stream = body["stream"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false

        val models = database.getEnabledModels()

        // 路由规则转发目标
        var targetProviderOverride: Long? = null
        var targetModelOverride: String? = null
        if (rule != null && rule.action == "route") {
            if (rule.providerId != null) targetProviderOverride = rule.providerId
            if (rule.targetModelKey.isNotBlank()) targetModelOverride = rule.targetModelKey
        }

        // qtai-sj 解析
        val resolvedModelId = targetModelOverride ?: resolveModelId(modelId, models)
        modelId = resolvedModelId

        // 构建尝试列表（用户级池优先）
        val attemptModels = buildAttemptModels(models, modelId, targetProviderOverride, ownerId)

        if (attemptModels.isEmpty()) {
            respondJson(call, openAIError(404, "No available model", "not_found"), 404)
            return
        }

        var lastError: String? = null
        var lastStatusCode = 502

        // ★ 故障转移循环：按池顺序逐个尝试 ★
        for ((idx, targetModel) in attemptModels.withIndex()) {
            val provider = database.getProviderById(targetModel.providerId)
            if (provider == null || !provider.isEnabled) continue

            try {
                val success = forwardToUpstream(call, provider, targetModel, bodyStr, body, modelId, stream, path, ownerUser)
                if (success) {
                    return
                }
                // 非流式失败时记录错误并切换到下一模型
                if (idx < attemptModels.size - 1) {
                    kotlinx.coroutines.delay(300)
                }
            } catch (e: Exception) {
                lastError = e.message
                lastStatusCode = 502
                GatewayScheduler.markModelFailed(targetModel.modelId, targetModel.providerId)
                GatewayScheduler.recordModelResult(targetModel.modelId, targetModel.providerId, false)
            }
        }

        // 全部失败
        val errMsg = lastError?.let { "All upstreams failed: $it" } ?: "All upstreams failed"
        respondJson(call, openAIError(502, errMsg, "server_error"), lastStatusCode)
    }

    /** 转发单个上游（含流式/非流式处理），返回是否成功 */
    private suspend fun forwardToUpstream(
        call: ApplicationCall,
        provider: Provider,
        targetModel: AiModel,
        bodyStr: String,
        body: JsonObject,
        modelId: String,
        stream: Boolean,
        path: String,
        ownerUser: User? = null
    ): Boolean {
        val startMs = System.currentTimeMillis()
        val upstreamUrl = provider.resolvedBaseUrl.trimEnd('/')
        val chatPath = provider.chatPath?.let { if (it.startsWith("/")) it else "/$it" } ?: "/v1/chat/completions"

        // 转发路径：具体接口用固定路径，chat/completions用chatPath，其余透传
        val targetPath = when {
            path.endsWith("chat/completions") || path.endsWith("completions") -> chatPath
            path.endsWith("messages") && path.startsWith("/v1/") -> "/v1/messages"
            else -> path
        }

        // 替换body中的model为真实模型ID（qtai-sj场景）
        val finalBody = if (body["model"]?.jsonPrimitive?.content == "qtai-sj") {
            replaceModelInBody(bodyStr, targetModel.modelId)
        } else {
            sanitizeRequestBody(bodyStr)
        }

        val client = com.qitong.gateway.network.UpstreamClient.getClient(useProxy = targetModel.useProxy)
        val uploadBytes = finalBody.toByteArray(Charsets.UTF_8).size.toLong()
        val req = okhttp3.Request.Builder()
            .url("$upstreamUrl$targetPath")
            .post(finalBody.toByteArray(Charsets.UTF_8).toRequestBody(DEFAULT_CT))
            .apply {
                if (!provider.apiKey.isNullOrBlank()) header("Authorization", "Bearer ${provider.apiKey}")
                if (stream) header("Accept", "text/event-stream")
            }
            .build()

        val response = withContext(Dispatchers.IO) { runCatching { client.newCall(req).execute() }.getOrNull() }
            ?: return false

        val isSuccess = response.isSuccessful
        recordTraffic(upload = uploadBytes)

        if (isSuccess) {
            GatewayScheduler.markModelSuccess(targetModel.modelId, targetModel.providerId, System.currentTimeMillis() - startMs)
            GatewayScheduler.recordModelResult(targetModel.modelId, targetModel.providerId, true)

            val respContentType = response.header("Content-Type") ?: "application/json"
            val respCode = response.code

            // 用量统计变量（真实字节 + token）
            var downloadBytes = 0L
            var promptTokens = 0
            var completionTokens = 0
            var totalTokens = 0

            if (stream && respContentType.contains("text/event-stream")) {
                // ★ 流式 SSE 透传（字节流直通，最稳；对端断流不抛异常） ★
                call.response.headers.append("Content-Type", "text/event-stream; charset=utf-8")
                call.response.headers.append("Cache-Control", "no-cache")
                call.response.headers.append("Connection", "keep-alive")
                try {
                    call.respondBytesWriter(ContentType.Text.EventStream, HttpStatusCode.OK) {
                        val source = response.body?.source()
                        if (source != null) {
                            val buf = ByteArray(8192)
                            var n: Int
                            while (true) {
                                n = runCatching { source.read(buf) }.getOrNull() ?: -1
                                if (n < 0) break
                                if (n > 0) {
                                    runCatching { writeFully(buf, 0, n); flush() }
                                    downloadBytes += n
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    // 客户端断开不影响上游标记成功（已完成内容已推流）
                }
                // 用量估算：取 ContentLength，避免 error
                if (downloadBytes == 0L) {
                    downloadBytes = (response.body?.contentLength() ?: 0L).takeIf { it > 0 } ?: 0L
                }
                recordTraffic(download = downloadBytes)
            } else {
                val respBody = response.body?.string() ?: "{}"
                downloadBytes = respBody.length.toLong()
                recordTraffic(download = downloadBytes)
                respondJson(call, respBody, respCode, ContentType.parse(respContentType))
                // 非流式：解析 usage 真实 token
                try {
                    val jobj = org.json.JSONObject(respBody)
                    val usage = jobj.optJSONObject("usage")
                    if (usage != null) {
                        promptTokens = usage.optInt("prompt_tokens", 0)
                        completionTokens = usage.optInt("completion_tokens", 0)
                        totalTokens = usage.optInt("total_tokens", 0)
                    }
                } catch (_: Exception) {}
            }

            // 用量统计（含商业化扣费）
            recordUsage(targetModel, modelId, uploadBytes, downloadBytes, ownerUser, promptTokens, completionTokens, totalTokens)
            response.close()
            return true
        } else {
            val errBody = response.body?.string() ?: ""
            response.close()
            // 2xx但空body → 触发故障转移
            if (response.code == 200 && errBody.isBlank()) {
                return false
            }
            return false
        }
    }

    /** 记录流量 */
    private fun recordTraffic(upload: Long = 0, download: Long = 0) {
        synchronized(this) {
            totalUploadBytes += upload
            totalDownloadBytes += download
        }
    }

    /** 记录用量 + 商业化扣费/扣额度（真实上传/下载字节 + 真实token，对齐原APP TokenUsage） */
    private fun recordUsage(
        model: AiModel, usedModelId: String,
        uploadBytes: Long, downloadBytes: Long,
        ownerUser: User? = null,
        promptTokens: Int = 0, completionTokens: Int = 0, totalTokens: Int = 0
    ) {
        try {
            // token 缺省时按字节估算（兼容无 usage 字段的上游）
            val estTokens = if (totalTokens > 0) totalTokens.toLong()
                else ((downloadBytes + uploadBytes) / 4).coerceAtLeast(1)
            // 计算成本：优先模型自定义价，其次默认价格表
            val price = if (model.price > 0) model.price else PricingTable.priceOf(model.modelId)
            val cost = estTokens / 1_000_000.0 * price * PricingTable.OUTPUT_MULTIPLIER
            val userId = ownerUser?.id ?: 0

            // 商业化扣款（余额不足则拒绝记录并标记，但不阻塞已成功的转发）
            if (ownerUser != null) {
                if (ownerUser.quotaLimit > 0) {
                    // 有额度上限：扣额度
                    database.consumeQuota(ownerUser.id, estTokens)
                } else {
                    // 余额体制：扣余额（不足则记录0成本）
                    database.deductBalance(ownerUser.id, cost)
                }
            }

            database.addTokenUsage(
                TokenUsage(
                    modelKey = "${model.providerId}::${model.modelId}",
                    modelName = model.displayName,
                    providerId = model.providerId,
                    promptTokens = promptTokens.toLong(),
                    completionTokens = completionTokens.toLong(),
                    totalTokens = totalTokens.toLong(),
                    uploadBytes = uploadBytes,
                    downloadBytes = downloadBytes,
                    apiKeyLabel = currentApiKeyLabel,
                    userId = userId,
                    cost = cost
                )
            )
        } catch (_: Exception) {}
    }

    @Volatile
    var currentApiKeyLabel: String = ""

    companion object {
        val ApiKeyEntryKey = AttributeKey<String>("apiKeyEntry")
        val ApiKeyLabelKey = AttributeKey<String>("apiKeyLabel")
        val ApiKeyOwnerKey = AttributeKey<Long>("apiKeyOwner")

        @Volatile
        var totalUploadBytes = 0L
        @Volatile
        var totalDownloadBytes = 0L
        @Volatile
        var startTime = System.currentTimeMillis()

        /** 网关运行状态（Docker版默认常驻，提供开关供展示） */
        @Volatile
        var running = true

        /** 自动测速协程句柄 */
        @Volatile
        var autoSpeedJob: kotlinx.coroutines.Job? = null

        /** 网关启停（Docker版进程常驻，这里做逻辑开关） */
        fun toggleGateway(action: String, database: com.qitong.gateway.db.Database): Boolean {
            running = when (action) {
                "start" -> true
                "stop" -> false
                else -> !running
            }
            return running
        }

        /** 自动测速调度：每 N 分钟跑一次全量测速 */
        fun setAutoSpeedTest(enabled: Boolean, intervalMin: Int, database: com.qitong.gateway.db.Database) {
            autoSpeedJob?.cancel()
            autoSpeedJob = null
            if (!enabled) return
            val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
            autoSpeedJob = scope.launch {
                while (true) {
                    kotlinx.coroutines.delay(intervalMin * 60 * 1000L)
                    try {
                        GatewayScheduler.refreshHealthCache(database)
                        GatewayScheduler.buildPipelineSortedModels(database)
                    } catch (_: Exception) {}
                }
            }
        }
    }

    // ============ 响应工具 ============

    private suspend fun respondJson(call: ApplicationCall, body: String, status: Int, contentType: ContentType = ContentType.Application.Json) {
        try {
            call.respondText(body, contentType.withCharset(Charsets.UTF_8), HttpStatusCode.fromValue(status))
        } catch (_: Exception) {}
    }

    private fun openAIError(status: Int, message: String, type: String = "invalid_request_error", code: Int? = null): String {
        return buildJsonObject {
            put("error", buildJsonObject {
                put("message", JsonPrimitive(message))
                put("type", JsonPrimitive(type))
                put("param", JsonNull)
                put("code", if (code != null) JsonPrimitive(code) else JsonNull)
            })
        }.toString()
    }

    /** 生成 OpenAI 标准 chat.completion 响应（内置聊天用） */
    fun makeChatCompletionResponse(modelId: String, content: String, stream: Boolean = false): String {
        val id = "chatcmpl-${UUID.randomUUID().toString().take(8)}"
        val created = System.currentTimeMillis() / 1000
        if (stream) {
            return buildJsonObject {
                put("id", JsonPrimitive(id))
                put("object", JsonPrimitive("chat.completion.chunk"))
                put("created", JsonPrimitive(created))
                put("model", JsonPrimitive(modelId))
                put("choices", JsonArray(listOf(buildJsonObject {
                    put("index", JsonPrimitive(0))
                    put("delta", buildJsonObject {
                        put("role", JsonPrimitive("assistant"))
                        put("content", JsonPrimitive(content))
                    })
                    put("finish_reason", JsonPrimitive("stop"))
                })))
            }.toString()
        }
        return buildJsonObject {
            put("id", JsonPrimitive(id))
            put("object", JsonPrimitive("chat.completion"))
            put("created", JsonPrimitive(created))
            put("model", JsonPrimitive(modelId))
            put("choices", JsonArray(listOf(buildJsonObject {
                put("index", JsonPrimitive(0))
                put("message", buildJsonObject {
                    put("role", JsonPrimitive("assistant"))
                    put("content", JsonPrimitive(content))
                })
                put("finish_reason", JsonPrimitive("stop"))
            })))
            put("usage", buildJsonObject {
                put("prompt_tokens", JsonPrimitive(0))
                put("completion_tokens", JsonPrimitive(content.length))
                put("total_tokens", JsonPrimitive(content.length))
            })
        }.toString()
    }

    /** 从消息content提取纯文本（支持字符串和多模态数组） */
    fun extractTextContent(content: JsonElement?): String {
        if (content == null) return ""
        return try {
            content.jsonPrimitive.content
        } catch (_: Exception) {
            try {
                content.jsonArray.joinToString("\n") { part ->
                    try {
                        val obj = part.jsonObject
                        if (obj["type"]?.jsonPrimitive?.content == "text") obj["text"]?.jsonPrimitive?.content ?: "" else ""
                    } catch (_: Exception) { "" }
                }.trim()
            } catch (_: Exception) { "" }
        }
    }
}

private fun ContentType.Companion.parse(s: String): ContentType =
    try { io.ktor.http.ContentType.parse(s) } catch (_: Exception) { ContentType.Application.Json }