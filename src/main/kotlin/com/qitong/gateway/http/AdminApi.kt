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
        is JsonElement -> data
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
        "supportsSystemRole" to p.supportsSystemRole, "customId" to p.customId,
        "ownerId" to p.ownerId, "isPublic" to p.isPublic
    )

    private fun modelToMap(m: AiModel) = mapOf(
        "id" to m.id, "providerId" to m.providerId, "modelId" to m.modelId,
        "displayName" to m.displayName, "isDefault" to m.isDefault,
        "syncStatus" to m.syncStatus, "isEnabled" to m.isEnabled,
        "customAlias" to m.customAlias, "useProxy" to m.useProxy,
        "contextWindow" to m.contextWindow, "ownerId" to m.ownerId,
        "isPublic" to m.isPublic, "price" to m.price
    )

    private fun apiKeyToMap(k: ApiKeyEntry) = mapOf(
        "key" to k.key, "label" to k.label, "enabled" to k.enabled,
        "allowedModels" to k.allowedModels, "qtaiSjAccess" to k.qtaiSjAccess,
        "createdAt" to k.createdAt, "ownerId" to k.ownerId
    )

    private fun ruleToMap(r: RoutingRule) = mapOf(
        "id" to r.id, "name" to r.name, "enabled" to r.enabled,
        "priority" to r.priority,
        "pathPattern" to r.pathPattern, "modelPattern" to r.modelPattern,
        "apiKeyPattern" to r.apiKeyPattern, "providerId" to r.providerId,
        "targetModelKey" to r.targetModelKey, "action" to r.action,
        "blockMessage" to r.blockMessage, "createdAt" to r.createdAt,
        "ownerId" to r.ownerId
    )

    // ============ 权限引擎（多租户 + 细粒度权限位） ============

    /** 全部系统级权限位 */
    object Perm {
        const val P_MANAGE = "provider.manage"   // 管理服务商（含系统级）
        const val M_MANAGE = "model.manage"      // 管理模型（含系统级）
        const val SYS_CONFIG = "system.config"   // 修改网关设置
        const val K_MANAGE = "keys.manage"       // 管理API密钥（含系统级）
        const val R_MANAGE = "rules.manage"      // 管理路由规则（含系统级）
        const val U_MANAGE = "users.manage"      // 用户管理
        const val SYS_SPEED = "system.speedtest" // 全局测速（含强制池）
        const val DATA_EXPORT = "data.export"    // 数据导出
    }

    /** 判断用户是否拥有某系统级权限（admin 天然拥有全部） */
    fun hasPerm(user: User, perm: String): Boolean =
        user.role == "admin" || user.permissions.contains(perm)

    /** 判断用户能否管理该资源（owner=0 表示系统资源） */
    fun canManageResource(user: User, perm: String, ownerId: Long): Boolean {
        if (user.role == "admin") return true
        return if (ownerId == 0L) hasPerm(user, perm)
        else ownerId == user.id
    }

    /** 解析 JSON 数组字段（如 bindModels/permissions） */
    private fun parseStringList(element: JsonElement?): List<String> = try {
        strictJson.decodeFromString<List<String>>(element.toString())
    } catch (_: Exception) { emptyList() }

    // ============ 服务商 CRUD（多租户） ============

    /** 获取可见服务商：admin=全部；普通用户=系统+公用+自己私有（key 一律脱敏，除非是自己的） */
    fun getVisibleProviders(database: Database, user: User): List<Map<String, Any?>> {
        if (user.role == "admin") {
            return database.getProviders().map { p ->
                providerToMap(p).toMutableMap().apply {
                    // 显示属主用户名
                    val owner = if (p.ownerId > 0) database.getUserById(p.ownerId) else null
                    this["ownerName"] = owner?.username ?: (if (p.ownerId == 0L) "系统" else "未知")
                }
            }
        }
        // 普通用户：自己私有 + 公用 + 有权限的系统服务商
        return database.getVisibleProviders(user.id)
            .filter { it.isPublic || it.ownerId == user.id || it.ownerId == 0L && hasPerm(user, Perm.P_MANAGE) }
            .map { p ->
                providerToMap(p).toMutableMap().apply {
                    // 安全脱敏：只有 owner 自己能看到完整 key，其余一律 ****
                    if (p.ownerId != user.id) this["apiKey"] = "****"
                    val owner = if (p.ownerId > 0) database.getUserById(p.ownerId) else null
                    this["ownerName"] = owner?.username ?: (if (p.ownerId == 0L) "系统" else "未知")
                }
            }
    }

    fun saveProvider(database: Database, body: JsonObject, user: User): Long {
        val id = body["id"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0
        // owner 判定：新建设为当前用户私有（除非用户拥有 provider.manage 且显式声明 ownerId=0 才建系统资源）
        val ownerId = if (id > 0) {
            database.getProviderById(id)?.ownerId ?: user.id
        } else if (hasPerm(user, Perm.P_MANAGE) && body["ownerId"]?.jsonPrimitive?.content?.toLongOrNull() == 0L) {
            0L
        } else {
            user.id
        }
        // 权限校验：非 admin 只能管理自己 owner 的资源；系统资源需权限位
        if (!canManageResource(user, Perm.P_MANAGE, ownerId)) return -1
        // key 保持：编辑时若前端提交空或 **** 则保留原key（防止覆盖）
        val existing = if (id > 0) database.getProviderById(id) else null
        var apiKey = body["apiKey"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        if (apiKey == null || apiKey == "****") apiKey = existing?.apiKey
        val p = Provider(
            id = id,
            name = body["name"]?.jsonPrimitive?.content ?: "",
            type = body["type"]?.jsonPrimitive?.content ?: "OpenAI Compatible",
            baseUrl = body["baseUrl"]?.jsonPrimitive?.content ?: "",
            port = body["port"]?.jsonPrimitive?.content ?: "",
            apiKey = apiKey,
            isEnabled = body["isEnabled"]?.let { parseBool(it) } ?: true,
            orderIndex = body["orderIndex"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            chatPath = body["chatPath"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
            supportsSystemRole = body["supportsSystemRole"]?.let { parseBool(it) } ?: false,
            customId = body["customId"]?.jsonPrimitive?.content ?: "",
            ownerId = ownerId,
            isPublic = body["isPublic"]?.let { parseBool(it) } ?: false
        )
        return if (id > 0) {
            database.updateProvider(p); id
        } else {
            database.addProvider(p)
        }
    }

    fun deleteProvider(database: Database, id: Long, user: User): Boolean {
        val existing = database.getProviderById(id) ?: return false
        if (!canManageResource(user, Perm.P_MANAGE, existing.ownerId)) return false
        database.deleteModelsByProvider(id)
        database.deleteProvider(id)
        return true
    }

    private fun parseBool(e: JsonElement): Boolean = when {
        e is JsonPrimitive && e.isString -> e.content == "true" || e.content == "1"
        e is JsonPrimitive -> e.content.toBooleanStrictOrNull() ?: (e.content == "1")
        else -> false
    }

    // ============ 模型 CRUD（多租户） ============

    /** 获取可见模型：admin=全部；普通用户=系统公用+公用+自己私有（含属主用户名） */
    fun getVisibleModels(database: Database, user: User): List<Map<String, Any?>> {
        if (user.role == "admin") {
            return database.getModels().map { m ->
                modelToMap(m).toMutableMap().apply {
                    val owner = if (m.ownerId > 0) database.getUserById(m.ownerId) else null
                    this["ownerName"] = owner?.username ?: (if (m.ownerId == 0L) "系统" else "未知")
                }
            }
        }
        val providerIds = database.getVisibleProviders(user.id)
            .filter { it.isPublic || it.ownerId == user.id || it.ownerId == 0L && hasPerm(user, Perm.P_MANAGE) }
            .map { it.id }
        return database.getVisibleModels(user.id)
            .filter { m -> m.ownerId == user.id || m.isPublic || m.ownerId == 0L && m.isPublic || m.providerId in providerIds }
            .map { m ->
                modelToMap(m).toMutableMap().apply {
                    val owner = if (m.ownerId > 0) database.getUserById(m.ownerId) else null
                    this["ownerName"] = owner?.username ?: (if (m.ownerId == 0L) "系统" else "未知")
                }
            }
    }

    fun saveModel(database: Database, body: JsonObject, user: User): Long {
        val id = body["id"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0
        // owner 判定：新建模型归属=其服务商归属（模型从属于服务商）
        val ownerId = if (id > 0) {
            database.getModelById(id)?.ownerId ?: user.id
        } else {
            val providerId = body["providerId"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0
            database.getProviderById(providerId)?.ownerId ?: run {
                if (hasPerm(user, Perm.M_MANAGE) && body["ownerId"]?.jsonPrimitive?.content?.toLongOrNull() == 0L) 0L else user.id
            }
        }
        if (!canManageResource(user, Perm.M_MANAGE, ownerId)) return -1
        // 价格：默认0=按价格表；自定义值>0
        val price = body["price"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
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
            contextWindow = body["contextWindow"]?.jsonPrimitive?.content?.toIntOrNull() ?: 4096,
            ownerId = ownerId,
            isPublic = body["isPublic"]?.let { parseBool(it) } ?: false,
            price = price
        )
        return if (id > 0) {
            database.updateModel(m); id
        } else {
            database.addModel(m)
        }
    }

    fun deleteModel(database: Database, id: Long, user: User): Boolean {
        val existing = database.getModelById(id) ?: return false
        if (!canManageResource(user, Perm.M_MANAGE, existing.ownerId)) return false
        database.deleteModel(id)
        return true
    }

    /** 切换模型启停（对齐原APP toggleModel） */
    fun toggleModel(database: Database, id: Long, user: User): Boolean? {
        val model = database.getModelById(id) ?: return null
        if (!canManageResource(user, Perm.M_MANAGE, model.ownerId)) return false
        database.updateModel(model.copy(isEnabled = !model.isEnabled))
        return model.isEnabled
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

    /** 批量测速（三指标：TTFT/TPS/总耗时，串行测试全部启用模型） */
    suspend fun speedTest(database: Database): List<Map<String, Any?>> {
        val results = GatewayScheduler.refreshHealthCache(database, force = true)
        GatewayScheduler.buildPipelineSortedModels(database)
        return results.map {
            mapOf(
                "modelId" to it.modelId,
                "providerId" to it.providerId,
                "ttftMs" to it.ttftMs,
                "tps" to it.tps,
                "totalMs" to it.totalMs,
                "latencyMs" to (if (it.isHealthy) it.totalMs else -1),
                "isHealthy" to it.isHealthy,
                "successCount" to it.successCount
            )
        }
    }

    /**
     * 单模型测速（对齐原APP：测一个测一个，前端逐个调用逐个显示）
     * 测完立即写入 healthCache + 更新排行榜
     */
    suspend fun speedTestOneModel(database: Database, providerId: Long, modelId: String): Map<String, Any?> {
        val model = database.getModelByKey(providerId, modelId)
            ?: return mapOf("modelId" to modelId, "providerId" to providerId,
                "isHealthy" to false, "ttftMs" to -1L, "tps" to 0.0, "totalMs" to -1L,
                "displayName" to modelId, "msg" to "模型不存在")
        val provider = database.getProviderById(providerId)
            ?: return mapOf("modelId" to modelId, "providerId" to providerId,
                "isHealthy" to false, "ttftMs" to -1L, "tps" to 0.0, "totalMs" to -1L,
                "displayName" to model.displayName, "msg" to "服务商不存在")
        // 测速：服务商或模型未启用则直接失败
        val h = if (provider.isEnabled && model.isEnabled) GatewayScheduler.measureModel(model, provider)
            else GatewayScheduler.ModelHealth(model.modelId, model.providerId, Long.MAX_VALUE, -1, 0.0, -1, 0, System.currentTimeMillis(), false)
        // 立即写入健康缓存（排行榜即时刷新）
        synchronized(GatewayScheduler.healthCache) {
            GatewayScheduler.healthCache[GatewayScheduler.routeKey(model.providerId, model.modelId)] = h
        }
        // 通过→自动启用；失败→保持原状态
        if (h.isHealthy && !model.isEnabled) database.updateModel(model.copy(isEnabled = true))
        return mapOf(
            "id" to model.id,
            "modelId" to model.modelId,
            "providerId" to model.providerId,
            "displayName" to model.displayName,
            "ttftMs" to h.ttftMs,
            "tps" to h.tps,
            "totalMs" to h.totalMs,
            "latencyMs" to (if (h.isHealthy) h.totalMs else -1),
            "isHealthy" to h.isHealthy,
            "enabled" to model.isEnabled,
            "msg" to (if (h.isHealthy) "测速通过" else "测速失败")
        )
    }

    /** 待测速模型列表（当前全部已启用模型，用于测速页默认渲染"待测速"状态） */
    fun getSpeedTestModels(database: Database): List<Map<String, Any?>> =
        database.getEnabledModels().map { m ->
            val p = database.getProviderById(m.providerId)
            mapOf(
                "id" to m.id,
                "modelId" to m.modelId,
                "providerId" to m.providerId,
                "displayName" to m.displayName.ifBlank { m.modelId },
                "providerName" to (p?.name ?: "P${m.providerId}"),
                "enabled" to m.isEnabled,
                "customAlias" to m.customAlias
            )
        }

    /** 传输明细（对齐原APP TokenUsage：每一次调用一条，含上传/下载字节与 token） */
    fun getUsageRecent(database: Database, limit: Int = 200): List<Map<String, Any?>> =
        database.getTokenUsageRecent(limit).map { t ->
            mapOf(
                "id" to t.id,
                "modelKey" to t.modelKey,
                "modelName" to t.modelName,
                "providerId" to t.providerId,
                "promptTokens" to t.promptTokens,
                "completionTokens" to t.completionTokens,
                "totalTokens" to t.totalTokens,
                "uploadBytes" to t.uploadBytes,
                "downloadBytes" to t.downloadBytes,
                "apiKeyLabel" to t.apiKeyLabel,
                "userId" to t.userId,
                "cost" to t.cost,
                "createdAt" to t.createdAt
            )
        }

    /**
     * 批量测速（对齐原APP batchTestAllModels）：
     * 逐个串行测试全部模型，通过的自动启用(isEnabled=true)，失败的可选自动关闭(isEnabled=false)
     * 三指标：TTFT/TPS/总耗时
     */
    suspend fun batchSpeedTest(database: Database, autoClose: Boolean): List<Map<String, Any?>> {
        val allModels = database.getModels()
        val results = mutableListOf<Map<String, Any?>>()
        for (model in allModels) {
            val provider = database.getProviderById(model.providerId)
            var h: GatewayScheduler.ModelHealth? = null
            if (provider != null && provider.isEnabled) {
                h = GatewayScheduler.measureModel(model, provider)
            } else {
                h = GatewayScheduler.ModelHealth(model.modelId, model.providerId, Long.MAX_VALUE, -1, 0.0, -1, 0, System.currentTimeMillis(), false)
            }
            val ok = h.isHealthy
            // 通过→自动启用；失败→可选自动关闭
            if (ok && !model.isEnabled) {
                database.updateModel(model.copy(isEnabled = true))
            } else if (!ok && autoClose && model.isEnabled) {
                database.updateModel(model.copy(isEnabled = false))
            }
            results.add(mapOf(
                "id" to model.id,
                "modelId" to model.modelId,
                "providerId" to model.providerId,
                "displayName" to model.displayName,
                "ttftMs" to h.ttftMs,
                "tps" to h.tps,
                "totalMs" to h.totalMs,
                "latencyMs" to (if (ok) h.totalMs else -1),
                "isHealthy" to ok,
                "enabled" to (if (ok) true else if (autoClose) false else model.isEnabled)
            ))
        }
        // 刷新健康缓存和排行榜
        GatewayScheduler.refreshHealthCache(database, force = true)
        GatewayScheduler.buildPipelineSortedModels(database)
        return results
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
        put("totalCost", JsonPrimitive(usageSummary.sumOf { (it["cost"] as? Number)?.toDouble() ?: 0.0 }))
        put("totalUpload", JsonPrimitive(GatewayProxy.totalUploadBytes))
        put("totalDownload", JsonPrimitive(GatewayProxy.totalDownloadBytes))
        put("uptime", JsonPrimitive((System.currentTimeMillis() - GatewayProxy.startTime) / 1000))
        put("pipelineSorted", JsonArray(GatewayScheduler.pipelineSortedModelKeys.map { JsonPrimitive(it) }))
    }

    // ============ 密钥（多租户） ============

    fun getVisibleApiKeys(database: Database, user: User): List<Map<String, Any?>> {
        if (user.role == "admin") {
            return database.getApiKeys().map { k ->
                apiKeyToMap(k).toMutableMap().apply {
                    // 显示属主用户名
                    val owner = if (k.ownerId > 0) database.getUserById(k.ownerId) else null
                    this["ownerName"] = owner?.username ?: (if (k.ownerId == 0L) "系统" else "未知")
                }
            }
        }
        return database.getApiKeysByOwner(user.id).map { k ->
            apiKeyToMap(k).toMutableMap().apply {
                this["ownerName"] = user.username
            }
        }
    }

    fun addApiKey(database: Database, body: JsonObject, user: User): Boolean {
        val key = body["key"]?.jsonPrimitive?.content ?: return false
        // 系统密钥需 keys.manage 权限；否则归属当前用户
        val ownerId = if (hasPerm(user, Perm.K_MANAGE) && body["ownerId"]?.jsonPrimitive?.content?.toLongOrNull() == 0L) 0L else user.id
        val e = ApiKeyEntry(
            key = key,
            label = body["label"]?.jsonPrimitive?.content ?: "",
            enabled = body["enabled"]?.let { parseBool(it) } ?: true,
            allowedModels = body["allowedModels"]?.let {
                try { strictJson.decodeFromString<List<String>>(it.toString()) } catch (_: Exception) { emptyList() }
            } ?: emptyList(),
            qtaiSjAccess = body["qtaiSjAccess"]?.let { parseBool(it) } ?: true,
            ownerId = ownerId
        )
        return database.addApiKey(e)
    }

    fun deleteApiKey(database: Database, key: String, user: User): Boolean {
        val existing = database.getApiKeys().firstOrNull { it.key == key } ?: return false
        if (!canManageResource(user, Perm.K_MANAGE, existing.ownerId)) return false
        database.deleteApiKey(key)
        return true
    }

    fun updateApiKey(database: Database, body: JsonObject, user: User): Boolean {
        val key = body["key"]?.jsonPrimitive?.content ?: return false
        val existing = database.getApiKeys().firstOrNull { it.key == key } ?: return false
        if (!canManageResource(user, Perm.K_MANAGE, existing.ownerId)) return false
        val e = ApiKeyEntry(
            key = key,
            label = body["label"]?.jsonPrimitive?.content ?: existing.label,
            enabled = body["enabled"]?.let { parseBool(it) } ?: existing.enabled,
            allowedModels = body["allowedModels"]?.let {
                try { strictJson.decodeFromString<List<String>>(it.toString()) } catch (_: Exception) { existing.allowedModels }
            } ?: existing.allowedModels,
            qtaiSjAccess = body["qtaiSjAccess"]?.let { parseBool(it) } ?: existing.qtaiSjAccess,
            ownerId = existing.ownerId
        )
        return database.updateApiKey(e)
    }

    // ============ 路由规则（多租户） ============

    fun getVisibleRules(database: Database, user: User): List<Map<String, Any?>> {
        if (user.role == "admin") return database.getRoutingRules().map { ruleToMap(it) }
        return database.getVisibleRoutingRules(user.id).map { ruleToMap(it) }
    }

    fun saveRule(database: Database, body: JsonObject, user: User): Long {
        val id = body["id"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0
        val ownerId = if (id > 0) {
            database.getRoutingRules().firstOrNull { it.id == id }?.ownerId ?: user.id
        } else if (hasPerm(user, Perm.R_MANAGE) && body["ownerId"]?.jsonPrimitive?.content?.toLongOrNull() == 0L) {
            0L
        } else {
            user.id
        }
        if (!canManageResource(user, Perm.R_MANAGE, ownerId)) return -1
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
            blockMessage = body["blockMessage"]?.jsonPrimitive?.content ?: "",
            ownerId = ownerId
        )
        return if (id > 0) {
            // 更新逻辑：删除重建
            database.deleteRoutingRule(id)
            database.addRoutingRule(r.copy(id = 0))
        } else {
            database.addRoutingRule(r)
        }
    }

    fun deleteRule(database: Database, id: Long, user: User): Boolean {
        val existing = database.getRoutingRules().firstOrNull { it.id == id } ?: return false
        if (!canManageResource(user, Perm.R_MANAGE, existing.ownerId)) return false
        database.deleteRoutingRule(id)
        return true
    }

    // ============ 用户管理 ============

    fun getUsers(database: Database): List<Map<String, Any?>> = database.getUsers().map {
        mapOf(
            "id" to it.id, "username" to it.username, "role" to it.role,
            "displayName" to it.displayName, "createdAt" to it.createdAt, "lastLoginAt" to it.lastLoginAt,
            "quotaLimit" to it.quotaLimit, "quotaUsed" to it.quotaUsed, "bindModels" to it.bindModels,
            "permissions" to it.permissions, "balance" to it.balance, "totalRecharge" to it.totalRecharge,
            "inviteCode" to it.inviteCode, "inviterId" to it.inviterId, "commissionRate" to it.commissionRate
        )
    }

    // ============ 聊天 ============

    fun getConversations(database: Database): List<Map<String, Any?>> = database.getConversations().map {
        mapOf("id" to it.id, "title" to it.title, "createdAt" to it.createdAt, "updatedAt" to it.updatedAt)
    }

    /** 按用户获取会话（多租户隔离） */
    fun getConversationsForUser(database: Database, userId: Long): List<Map<String, Any?>> = database.getConversationsByUser(userId).map {
        mapOf("id" to it.id, "title" to it.title, "createdAt" to it.createdAt, "updatedAt" to it.updatedAt)
    }

    fun createConversation(database: Database, title: String = "新对话", userId: Long? = null): Long = database.addConversation(title, userId)

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

    /** 内置聊天：调用网关转发并保存消息（按用户隔离 + 人格注入） */
    suspend fun chat(database: Database, body: JsonObject, user: User?): Map<String, Any?> {
        val userId = user?.id ?: 0
        val conversationId = body["conversationId"]?.jsonPrimitive?.content?.toLongOrNull()
            ?: database.addConversation("新对话", userId)
        val userContent = body["content"]?.jsonPrimitive?.content ?: ""
        val modelId = body["model"]?.jsonPrimitive?.content ?: ""
        val stream = body["stream"]?.let { parseBool(it) } ?: false

        // 保存用户消息
        database.addMessage(ChatMessage(conversationId = conversationId, role = "user", content = userContent, modelId = modelId))

        // 人格注入 + 远程控制（喊人格名直接触发）
        val persona = if (userId > 0) database.getPersona(userId) else null
        val personaName = persona?.name?.trim() ?: ""
        // 远程控制：消息以人格名开头（如 "綦小桐，你好"）→ 强制使用大脑绑定模型
        var effectiveModel = modelId.ifBlank { "qtai-sj" }
        if (personaName.isNotBlank() && userContent.startsWith(personaName)) {
            effectiveModel = "qtai-sj"  // 强制走大脑
        }

        // 构造 OpenAI 请求体（注入用户人格）
        val messages = database.getMessagesByConversation(conversationId).map {
            buildJsonObject {
                put("role", JsonPrimitive(it.role))
                put("content", JsonPrimitive(it.content))
            }
        }.toMutableList()
        if (persona != null && (persona.name.isNotBlank() || persona.background.isNotBlank())) {
            val sb = StringBuilder()
            if (persona.name.isNotBlank()) sb.append("你的名字是${persona.name}。")
            if (persona.age.isNotBlank()) sb.append("年龄${persona.age}。")
            if (persona.personality.isNotBlank()) sb.append("性格：${persona.personality}。")
            if (persona.tone.isNotBlank()) sb.append("语气风格：${persona.tone}。")
            if (persona.background.isNotBlank()) sb.append("背景：${persona.background}")
            val sysMsg = buildJsonObject {
                put("role", JsonPrimitive("system"))
                put("content", JsonPrimitive(sb.toString()))
            }
            messages.add(0, sysMsg)
        }
        // 注入大脑记忆（对齐原APP：记忆系统启用时注入；模型独立记忆开启时只取当前模型相关记忆）
        if (userId > 0 && persona?.memoryEnabled != false) {
            val memCfg = database.getMemoryConfig(userId)
            val memEnabled = memCfg["enabled"] as? Boolean ?: true
            val modelIndependent = memCfg["modelIndependent"] as? Boolean ?: false
            if (memEnabled) {
                val allMems = database.getMemories(userId, 30)
                val filtered = if (modelIndependent && effectiveModel.isNotBlank() && effectiveModel != "qtai-sj") {
                    allMems.filter { m -> (m["modelId"] as? String ?: "").isBlank() || (m["modelId"] as? String ?: "") == effectiveModel }
                } else allMems
                if (filtered.isNotEmpty()) {
                    val msb = StringBuilder("\n\n## 🧠 用户历史记忆（共${filtered.size}条，按用户独立存储）\n")
                    msb.append("以下是该用户过去与你的对话记忆，回复时自然运用这些记忆：\n")
                    filtered.take(15).forEach { m ->
                        val title = m["title"] as? String ?: ""
                        val content = m["content"] as? String ?: ""
                        if (content.isNotBlank()) msb.append("  • ${if (title.isNotBlank()) "$title：" else ""}$content\n")
                    }
                    messages.add(buildJsonObject {
                        put("role", JsonPrimitive("system"))
                        put("content", JsonPrimitive(msb.toString()))
                    })
                }
            }
        }
        // 注入技能池提示（让大脑能用技能）
        val skillPrompt = SkillRegistry.buildSkillPrompt()
        if (persona != null && persona.memoryEnabled) {
            messages.add(buildJsonObject {
                put("role", JsonPrimitive("system"))
                put("content", JsonPrimitive(skillPrompt))
            })
        }
        // 注入用户自定义技能（按用户隔离，用户自己添加的技能，直接按描述执行）
        if (userId > 0) {
            val customRaw = database.getUserConfig(userId, "custom_skills", "[]")
            val customArr = try {
                kotlinx.serialization.json.Json.decodeFromString<JsonArray>(customRaw)
            } catch (_: Exception) { JsonArray(emptyList()) }
            if (customArr.isNotEmpty()) {
                val csb = StringBuilder("\n\n## 🧰 用户自定义技能（共${customArr.size}个）\n")
                csb.append("以下是用户自己定义的技能，当用户的消息命中触发词或描述意图时，请直接按执行描述完成操作：\n")
                customArr.forEach { item ->
                    val obj = item.jsonObject
                    val name = obj["name"]?.jsonPrimitive?.content ?: ""
                    val desc = obj["description"]?.jsonPrimitive?.content ?: ""
                    val trigs = obj["triggers"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty().joinToString("、")
                    if (name.isNotBlank()) csb.append("  • 技能【$name】" + (if (trigs.isNotBlank()) " （触发词：$trigs）" else "") + "\n")
                    if (desc.isNotBlank()) csb.append("    执行：$desc\n")
                }
                messages.add(buildJsonObject {
                    put("role", JsonPrimitive("system"))
                    put("content", JsonPrimitive(csb.toString()))
                })
            }
        }
        val requestBody = buildJsonObject {
            put("model", JsonPrimitive(effectiveModel))
            put("messages", JsonArray(messages))
            put("stream", JsonPrimitive(stream))
            put("max_tokens", JsonPrimitive(4096))
        }
        val skillResults = mutableListOf<Map<String, String>>()

        // 调用上游（可见模型 = 公用 + 自己的；大脑绑定优先）
        val proxy = GatewayProxy(database)
        val allModels = database.getEnabledModels()
        val visibleModels = if (user?.role == "admin") allModels
            else if (userId > 0) allModels.filter { it.isPublic || it.ownerId == userId }
            else allModels.filter { it.isPublic }
        // 大脑绑定：若用户绑定了大脑模型，且消息喊了人格名 → 优先该模型
        val brainKey = if (userId > 0) database.getUserConfig(userId, "qtai_brain", "") else ""
        val attemptModels = if (personaName.isNotBlank() && userContent.startsWith(personaName) && brainKey.isNotBlank()) {
            // 直接解析大脑绑定模型
            val brainModels = visibleModels.filter { "${it.providerId}::${it.modelId}" == brainKey || it.modelId == brainKey }
            if (brainModels.isNotEmpty()) brainModels else proxy.buildAttemptModels(visibleModels, effectiveModel, null, userId)
        } else {
            proxy.buildAttemptModels(visibleModels, proxy.resolveModelId(effectiveModel, visibleModels), null, userId)
        }
        if (attemptModels.isEmpty()) return mapOf("conversationId" to conversationId, "error" to "No available model", "reply" to "没有可用模型，请先测速或添加服务商")

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

        // 技能指令执行：从回复中提取【指令:编码】并执行
        if (lastResult != null) {
            val instructions = SkillRegistry.extractInstructions(lastResult!!)
            for ((code, param) in instructions) {
                val skill = SkillRegistry.getSkillByCode(code)
                if (skill != null) {
                    try {
                        val result = SkillExecutor.execute(database, code, param, userId)
                        skillResults.add(mapOf("code" to code, "name" to skill.name, "result" to result))
                    } catch (_: Exception) {
                        skillResults.add(mapOf("code" to code, "name" to skill.name, "result" to "技能执行失败"))
                    }
                }
            }
        }

        return mapOf(
            "conversationId" to conversationId,
            "reply" to (lastResult ?: "所有上游模型均不可用，请检查服务商配置"),
            "skills" to skillResults
        )
    }

    private val JSON_CT = "application/json".toMediaType()
}