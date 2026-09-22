package com.qitong.gateway.http

import com.qitong.gateway.auth.AuthManager
import com.qitong.gateway.db.Database
import com.qitong.gateway.model.*
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respondText
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Web 后台管理 API —— 服务商/模型/测速/密钥/路由规则/用户/统计
 * 所有接口需登录鉴权（Authorization: Bearer <token> 或 Cookie qt_session）
 */
object AdminApi {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val strictJson = Json { ignoreUnknownKeys = true }

    // ============ 通用响应 ============

    suspend fun ok(call: ApplicationCall, data: Any? = null, msg: String = "ok") {
        respondJson(call, buildJsonObject {
            put("code", JsonPrimitive(0))
            put("msg", JsonPrimitive(msg))
            if (data != null) put("data", encodeElement(data))
        }, 200)
    }

    suspend fun fail(call: ApplicationCall, msg: String, status: Int = 400, code: Int = -1) {
        respondJson(call, buildJsonObject {
            put("code", JsonPrimitive(code))
            put("msg", JsonPrimitive(msg))
        }, status)
    }

    suspend fun respondJson(call: ApplicationCall, body: JsonObject, status: Int) {
        try {
            call.respondText(body.toString(), ContentType.Application.Json.withCharset(Charsets.UTF_8), HttpStatusCode.fromValue(status))
        } catch (_: Exception) {}
    }

    private fun encodeElement(data: Any?): JsonElement = when (data) {
        null -> JsonNull
        is String -> JsonPrimitive(data)
        is Int -> JsonPrimitive(data)
        is Long -> JsonPrimitive(data)
        is Boolean -> JsonPrimitive(data)
        is Double -> JsonPrimitive(data)
        is Float -> JsonPrimitive(data)
        is List<*> -> JsonArray(data.map { encodeElement(it) })
        is Map<*, *> -> buildJsonObject {
            data.forEach { (k, v) -> put(k?.toString() ?: "", encodeElement(v)) }
        }
        is Provider -> encodeElement(providerToMap(data))
        is AiModel -> encodeElement(modelToMap(data))
        is ApiKeyEntry -> encodeElement(apiKeyToMap(data))
        is RoutingRule -> encodeElement(ruleToMap(data))
        is User -> encodeElement(mapOf(
            "id" to data.id,
            "username" to data.username,
            "role" to data.role,
            "displayName" to data.displayName,
            "createdAt" to data.createdAt,
            "lastLoginAt" to data.lastLoginAt,
            "quotaLimit" to data.quotaLimit,
            "quotaUsed" to data.quotaUsed,
            "bindModels" to data.bindModels
        ))
        is Persona -> encodeElement(mapOf(
            "id" to data.id,
            "userId" to data.userId,
            "name" to data.name,
            "age" to data.age,
            "personality" to data.personality,
            "tone" to data.tone,
            "background" to data.background,
            "openness" to data.openness,
            "conscientiousness" to data.conscientiousness,
            "extraversion" to data.extraversion,
            "agreeableness" to data.agreeableness,
            "neuroticism" to data.neuroticism,
            "memoryEnabled" to data.memoryEnabled,
            "updatedAt" to data.updatedAt
        ))
        is Conversation -> encodeElement(mapOf(
            "id" to data.id,
            "title" to data.title,
            "createdAt" to data.createdAt,
            "updatedAt" to data.updatedAt
        ))
        is ChatMessage -> encodeElement(mapOf(
            "id" to data.id,
            "conversationId" to data.conversationId,
            "role" to data.role,
            "content" to data.content,
            "modelId" to data.modelId,
            "createdAt" to data.createdAt
        ))
        is TokenUsage -> encodeElement(mapOf(
            "id" to data.id,
            "modelKey" to data.modelKey,
            "modelName" to data.modelName,
            "providerId" to data.providerId,
            "promptTokens" to data.promptTokens,
            "completionTokens" to data.completionTokens,
            "totalTokens" to data.totalTokens,
            "uploadBytes" to data.uploadBytes,
            "downloadBytes" to data.downloadBytes,
            "apiKeyLabel" to data.apiKeyLabel,
            "createdAt" to data.createdAt
        ))
        else -> JsonPrimitive(data.toString())
    }

    private fun providerToMap(p: Provider) = mapOf(
        "id" to p.id, "name" to p.name, "type" to p.type, "baseUrl" to p.baseUrl,
        "port" to p.port, "apiKey" to (p.apiKey ?: ""), "isEnabled" to p.isEnabled,
        "orderIndex" to p.orderIndex, "chatPath" to (p.chatPath ?: ""),
        "supportsSystemRole" to p.supportsSystemRole, "customId" to p.customId
    )

    private fun modelToMap(m: AiModel) = mapOf(
        "id" to m.id, "providerId" to m.providerId, "modelId" to m.modelId,
        "displayName" to m.displayName, "isDefault" to m.isDefault,
        "syncStatus" to m.syncStatus, "isEnabled" to m.isEnabled,
        "customAlias" to m.customAlias, "useProxy" to m.useProxy,
        "contextWindow" to m.contextWindow
    )

    private fun apiKeyToMap(k: ApiKeyEntry) = mapOf(
        "key" to k.key, "label" to k.label, "enabled" to k.enabled,
        "allowedModels" to k.allowedModels, "qtaiSjAccess" to k.qtaiSjAccess,
        "createdAt" to k.createdAt
    )

    private fun ruleToMap(r: RoutingRule) = mapOf(
        "id" to r.id, "name" to r.name, "enabled" to r.enabled, "priority" to r.priority,
        "pathPattern" to r.pathPattern, "modelPattern" to r.modelPattern,
        "apiKeyPattern" to r.apiKeyPattern, "providerId" to r.providerId,
        "targetModelKey" to r.targetModelKey, "action" to r.action,
        "blockMessage" to r.blockMessage, "createdAt" to r.createdAt
    )

    // ============ 服务商 CRUD ============

    fun getProviders(database: Database): List<Map<String, Any?>> = database.getProviders().map { providerToMap(it) }

    fun saveProvider(database: Database, body: JsonObject): Long {
        val id = body["id"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0
        val p = Provider(
            id = id,
            name = body["name"]?.jsonPrimitive?.content ?: "",
            type = body["type"]?.jsonPrimitive?.content ?: "OpenAI Compatible",
            baseUrl = body["baseUrl"]?.jsonPrimitive?.content ?: "",
            port = body["port"]?.jsonPrimitive?.content ?: "",
            apiKey = body["apiKey"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
            isEnabled = body["isEnabled"]?.let { parseBool(it) } ?: true,
            orderIndex = body["orderIndex"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            chatPath = body["chatPath"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
            supportsSystemRole = body["supportsSystemRole"]?.let { parseBool(it) } ?: false,
            customId = body["customId"]?.jsonPrimitive?.content ?: ""
        )
        return if (id > 0) {
            database.updateProvider(p); id
        } else {
            database.addProvider(p)
        }
    }

    fun deleteProvider(database: Database, id: Long) {
        database.deleteModelsByProvider(id)
        database.deleteProvider(id)
    }

    private fun parseBool(e: JsonElement): Boolean = when {
        e is JsonPrimitive && e.isString -> e.content == "true" || e.content == "1"
        e is JsonPrimitive -> e.content.toBooleanStrictOrNull() ?: (e.content == "1")
        else -> false
    }

    // ============ 模型 CRUD ============

    fun getModels(database: Database): List<Map<String, Any?>> = database.getModels().map { modelToMap(it) }

    fun saveModel(database: Database, body: JsonObject): Long {
        val id = body["id"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0
        val m = AiModel(
            id = id,
            providerId = body["providerId"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0,
            modelId = body["modelId"]?.jsonPrimitive?.content ?: "",
            displayName = body["displayName"]?.jsonPrimitive?.content ?: "",
            isDefault = body["isDefault"]?.let { parseBool(it) } ?: false,
            syncStatus = body["syncStatus"]?.jsonPrimitive?.content ?: "Pending",
            isEnabled = body["isEnabled"]?.let { parseBool(it) } ?: true,
            customAlias = body["customAlias"]?.jsonPrimitive?.content ?: "",
            useProxy = body["useProxy"]?.let { parseBool(it) } ?: true,
            contextWindow = body["contextWindow"]?.jsonPrimitive?.content?.toIntOrNull() ?: 4096
        )
        return if (id > 0) {
            database.updateModel(m); id
        } else {
            database.addModel(m)
        }
    }

    fun deleteModel(database: Database, id: Long) {
        database.deleteModel(id)
    }

    /** 同步服务商模型列表（调上游 /v1/models） */
    suspend fun syncProviderModels(database: Database, providerId: Long): Int {
        val provider = database.getProviderById(providerId) ?: return 0
        val url = provider.resolvedBaseUrl.trimEnd('/') + "/v1/models"
        val client = com.qitong.gateway.network.UpstreamClient.getClient(true)
        val request = okhttp3.Request.Builder().url(url).get()
            .apply { if (!provider.apiKey.isNullOrBlank()) header("Authorization", "Bearer ${provider.apiKey}") }
            .build()
        val response = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { client.newCall(request).execute() }.getOrNull()
        } ?: return 0
        if (!response.isSuccessful) { response.close(); return 0 }
        val bodyStr = response.body?.string() ?: ""
        response.close()
        return try {
            val root = strictJson.parseToJsonElement(bodyStr).jsonObject
            val data = root["data"]?.jsonArray ?: return 0
            var count = 0
            for (item in data) {
                val obj = item.jsonObject
                val modelId = obj["id"]?.jsonPrimitive?.content ?: continue
                val display = obj["display_name"]?.jsonPrimitive?.content ?: obj["id"]?.jsonPrimitive?.content ?: modelId
                if (database.getModelByKey(providerId, modelId) == null) {
                    database.addModel(AiModel(providerId = providerId, modelId = modelId, displayName = display, syncStatus = "Synced"))
                    count++
                }
            }
            count
        } catch (_: Exception) { 0 }
    }

    /** 批量测速 */
    suspend fun speedTest(database: Database): List<Map<String, Any?>> {
        val results = GatewayScheduler.refreshHealthCache(database)
        GatewayScheduler.buildPipelineSortedModels(database)
        return results.map {
            mapOf(
                "modelId" to it.modelId,
                "providerId" to it.providerId,
                "latencyMs" to (if (it.isHealthy) it.latencyMs else -1),
                "isHealthy" to it.isHealthy,
                "successCount" to it.successCount
            )
        }
    }

    // ============ 配置 ============

    fun getAllConfig(database: Database): Map<String, String> = database.getAllConfig()

    fun setConfig(database: Database, key: String, value: String) {
        database.setConfig(key, value)
    }

    fun setConfigs(database: Database, body: JsonObject) {
        body.forEach { (k, v) ->
            database.setConfig(k, when {
                v is JsonPrimitive && v.isString -> v.content
                else -> v.toString()
            })
        }
    }

    // ============ 统计 ============

    fun getStats(database: Database): JsonObject = buildJsonObject {
        val usageSummary = database.getTokenUsageSummary()
        val providers = database.getProviders()
        val models = database.getModels()

        put("providers", JsonPrimitive(providers.size))
        put("models", JsonPrimitive(models.size))
        put("enabledModels", JsonPrimitive(models.count { it.isEnabled }))
        put("conversations", JsonPrimitive(database.getConversations().size))
        put("usage", JsonArray(usageSummary.map { row ->
            buildJsonObject {
                row.forEach { (k, v) -> put(k, encodeElement(v)) }
            }
        }))
        put("totalUpload", JsonPrimitive(GatewayProxy.totalUploadBytes))
        put("totalDownload", JsonPrimitive(GatewayProxy.totalDownloadBytes))
        put("uptime", JsonPrimitive((System.currentTimeMillis() - GatewayProxy.startTime) / 1000))
        put("pipelineSorted", JsonArray(GatewayScheduler.pipelineSortedModelKeys.map { JsonPrimitive(it) }))
    }

    // ============ 密钥 ============

    fun getApiKeys(database: Database): List<Map<String, Any?>> = database.getApiKeys().map { apiKeyToMap(it) }

    fun addApiKey(database: Database, body: JsonObject): Boolean {
        val key = body["key"]?.jsonPrimitive?.content ?: return false
        val e = ApiKeyEntry(
            key = key,
            label = body["label"]?.jsonPrimitive?.content ?: "",
            enabled = body["enabled"]?.let { parseBool(it) } ?: true,
            allowedModels = body["allowedModels"]?.let {
                try { strictJson.decodeFromString<List<String>>(it.toString()) } catch (_: Exception) { emptyList() }
            } ?: emptyList(),
            qtaiSjAccess = body["qtaiSjAccess"]?.let { parseBool(it) } ?: true
        )
        return database.addApiKey(e)
    }

    fun deleteApiKey(database: Database, key: String) {
        database.deleteApiKey(key)
    }

    // ============ 路由规则 ============

    fun getRules(database: Database): List<Map<String, Any?>> = database.getRoutingRules().map { ruleToMap(it) }

    fun saveRule(database: Database, body: JsonObject): Long {
        val id = body["id"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0
        val r = RoutingRule(
            id = id,
            name = body["name"]?.jsonPrimitive?.content ?: "新规则",
            enabled = body["enabled"]?.let { parseBool(it) } ?: true,
            priority = body["priority"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            pathPattern = body["pathPattern"]?.jsonPrimitive?.content ?: "",
            modelPattern = body["modelPattern"]?.jsonPrimitive?.content ?: "",
            apiKeyPattern = body["apiKeyPattern"]?.jsonPrimitive?.content ?: "",
            providerId = body["providerId"]?.jsonPrimitive?.content?.toLongOrNull(),
            targetModelKey = body["targetModelKey"]?.jsonPrimitive?.content ?: "",
            action = body["action"]?.jsonPrimitive?.content ?: "route",
            blockMessage = body["blockMessage"]?.jsonPrimitive?.content ?: ""
        )
        return if (id > 0) {
            // 更新逻辑：删除重建
            database.deleteRoutingRule(id)
            database.addRoutingRule(r.copy(id = 0))
        } else {
            database.addRoutingRule(r)
        }
    }

    fun deleteRule(database: Database, id: Long) {
        database.deleteRoutingRule(id)
    }

    // ============ 用户管理 ============

    fun getUsers(database: Database): List<Map<String, Any?>> = database.getUsers().map {
        mapOf(
            "id" to it.id, "username" to it.username, "role" to it.role,
            "displayName" to it.displayName, "createdAt" to it.createdAt, "lastLoginAt" to it.lastLoginAt,
            "quotaLimit" to it.quotaLimit, "quotaUsed" to it.quotaUsed, "bindModels" to it.bindModels
        )
    }

    // ============ 聊天 ============

    fun getConversations(database: Database): List<Map<String, Any?>> = database.getConversations().map {
        mapOf("id" to it.id, "title" to it.title, "createdAt" to it.createdAt, "updatedAt" to it.updatedAt)
    }

    fun createConversation(database: Database, title: String = "新对话"): Long = database.addConversation(title)

    fun getConversation(database: Database, id: Long): Map<String, Any?> = mapOf(
        "conversation" to (database.getConversationById(id)?.let {
            mapOf("id" to it.id, "title" to it.title, "createdAt" to it.createdAt, "updatedAt" to it.updatedAt)
        }),
        "messages" to database.getMessagesByConversation(id)
    )

    fun deleteConversation(database: Database, id: Long) {
        database.deleteConversation(id)
    }

    fun renameConversation(database: Database, id: Long, title: String) {
        database.updateConversationTitle(id, title)
    }

    /** 内置聊天：调用网关转发并保存消息 */
    suspend fun chat(database: Database, body: JsonObject): Map<String, Any?> {
        val conversationId = body["conversationId"]?.jsonPrimitive?.content?.toLongOrNull()
            ?: database.addConversation("新对话")
        val userContent = body["content"]?.jsonPrimitive?.content ?: ""
        val modelId = body["model"]?.jsonPrimitive?.content ?: ""
        val stream = body["stream"]?.let { parseBool(it) } ?: false

        // 保存用户消息
        database.addMessage(ChatMessage(conversationId = conversationId, role = "user", content = userContent, modelId = modelId))

        // 构造 OpenAI 请求体
        val messages = database.getMessagesByConversation(conversationId).map {
            buildJsonObject {
                put("role", JsonPrimitive(it.role))
                put("content", JsonPrimitive(it.content))
            }
        }
        val requestBody = buildJsonObject {
            put("model", JsonPrimitive(modelId.ifBlank { "qtai-sj" }))
            put("messages", JsonArray(messages))
            put("stream", JsonPrimitive(stream))
            put("max_tokens", JsonPrimitive(4096))
        }

        // 调用上游
        val proxy = GatewayProxy(database)
        val attemptModels = proxy.buildAttemptModels(database.getEnabledModels(), proxy.resolveModelId(modelId.ifBlank { "qtai-sj" }, database.getEnabledModels()))
        if (attemptModels.isEmpty()) return mapOf("conversationId" to conversationId, "error" to "No available model")

        var lastResult: String? = null
        for (targetModel in attemptModels) {
            val provider = database.getProviderById(targetModel.providerId) ?: continue
            if (!provider.isEnabled) continue
            try {
                val upstreamUrl = provider.resolvedBaseUrl.trimEnd('/')
                val chatPath = provider.chatPath?.let { if (it.startsWith("/")) it else "/$it" } ?: "/v1/chat/completions"
                val finalBody = proxy.sanitizeRequestBody(requestBody.toString())
                    .replace("\"model\":\"qtai-sj\"", "\"model\":\"${targetModel.modelId}\"")
                val client = com.qitong.gateway.network.UpstreamClient.getClient(targetModel.useProxy)
                val req = okhttp3.Request.Builder()
                    .url("$upstreamUrl$chatPath")
                    .post(finalBody.toByteArray(Charsets.UTF_8).toRequestBody(JSON_CT))
                    .apply { if (!provider.apiKey.isNullOrBlank()) header("Authorization", "Bearer ${provider.apiKey}") }
                    .build()
                val resp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { client.newCall(req).execute() }.getOrNull()
                } ?: continue
                if (resp.isSuccessful) {
                    val respBody = resp.body?.string() ?: "{}"
                    resp.close()
                    val content = try {
                        strictJson.parseToJsonElement(respBody).jsonObject["choices"]?.jsonArray?.get(0)?.jsonObject
                            ?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content
                    } catch (_: Exception) { null }
                    val answer = content ?: runCatching {
                        strictJson.parseToJsonElement(respBody).jsonObject["choices"]?.jsonArray?.get(0)?.jsonObject
                            ?.get("text")?.jsonPrimitive?.content
                    }.getOrNull() ?: "(空响应)"
                    lastResult = answer
                    database.addMessage(ChatMessage(
                        conversationId = conversationId, role = "assistant", content = answer, modelId = targetModel.modelId
                    ))
                    GatewayScheduler.markModelSuccess(targetModel.modelId, targetModel.providerId, 0)
                    break
                } else {
                    resp.close()
                }
            } catch (_: Exception) {
                GatewayScheduler.markModelFailed(targetModel.modelId, targetModel.providerId)
            }
        }

        return mapOf(
            "conversationId" to conversationId,
            "reply" to (lastResult ?: "所有上游模型均不可用，请检查服务商配置")
        )
    }

    private val JSON_CT = "application/json".toMediaType()
}