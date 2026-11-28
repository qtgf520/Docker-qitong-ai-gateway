package com.qitong.gateway.http

import com.qitong.gateway.db.Database
import com.qitong.gateway.model.AiModel
import com.qitong.gateway.model.Provider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 网关调度器 —— 健康检查、测速排行榜、智能故障转移
 * 对齐原APP GatewayScheduler.kt + ModelSpeedTester.kt
 *
 * 三指标测速（对齐原APP）：
 *   TTFT : Time To First Token 首字延迟(ms)
 *   TPS  : Tokens Per Second 首字后每秒token数
 *   totalMs: 总耗时(ms)
 *   兼容 SSE 流式 + 非SSE 完整JSON 响应
 */
object GatewayScheduler {

    private const val HEALTH_CHECK_TIMEOUT = 15_000L
    private const val READ_TIMEOUT = 60_000L
    private const val MODEL_TIMEOUT = 30_000L
    private const val CACHE_TTL = 60_000L
    private const val BEST_MODEL_TTL = 300_000L
    private const val MODEL_HISTORY_TTL = 30 * 60 * 1000L

    /** 测速排行榜（providerId::modelId 形式） */
    @Volatile
    var pipelineSortedModelKeys: List<String> = emptyList()

    /** 三指标健康记录 */
    data class ModelHealth(
        val modelId: String,
        val providerId: Long,
        val latencyMs: Long = Long.MAX_VALUE,   // 向后兼容=totalMs
        val ttftMs: Long = -1,                   // 首字延迟
        val tps: Double = 0.0,                   // tokens/s
        val totalMs: Long = -1,                  // 总耗时
        val tokenCount: Int = 0,
        val lastCheckTime: Long = 0,
        val isHealthy: Boolean = true,
        val successCount: Int = 0,
        // ★ v1.270 本次测速实际通过的通道数（文本/图片/语音/视频，0=未测或全失败）
        val channelCount: Int = 0
    )

    val healthCache = mutableMapOf<String, ModelHealth>()
    private var cacheTime: Long = 0

    /** 健康缓存快照（模型页显示测过状态） */
    fun healthCacheSnapshot(): List<Map<String, Any?>> = synchronized(healthCache) {
        healthCache.entries.map { (routeKey, h) ->
            mapOf(
                // ★ v1.267 修复：前端一律用 x.key === key 匹配，快照必须带 key（原缺 → 排行榜全显示「待测速」）
                "key" to routeKey,
                "modelId" to h.modelId,
                "providerId" to h.providerId,
                "isHealthy" to h.isHealthy,
                "ttftMs" to h.ttftMs,
                "tps" to h.tps,
                "totalMs" to h.totalMs,
                "latencyMs" to h.latencyMs,
                "lastCheckTime" to h.lastCheckTime,
                "successCount" to h.successCount,
                // ★ v1.267 冷却中标记（前端显示「冷却中」）
                "coolingDown" to isCoolingDown(h.modelId, h.providerId)
            )
        }
    }

    /** ★ v1.255 故障切换状态快照 */
    fun bestSnapshot(): Map<String, Any?> = mapOf(
        "bestModelKey" to bestModelKey,
        "bestLatencyMs" to (if (bestModelLatency == Long.MAX_VALUE) -1L else bestModelLatency),
        "setTime" to bestModelSetTime,
        "cacheTime" to cacheTime
    )

    @Volatile
    private var bestModelKey: String? = null
    @Volatile
    private var bestModelLatency: Long = Long.MAX_VALUE
    private var bestModelSetTime: Long = 0

    data class ModelHistoryRecord(
        val modelId: String,
        val providerId: Long,
        val lastSuccessTime: Long = 0,
        val successCount: Int = 0,
        val failCount: Int = 0,
        val lastFailTime: Long = 0,
        val isHealthy: Boolean = true
    )

    private val modelHistory = mutableMapOf<String, ModelHistoryRecord>()

    fun routeKey(providerId: Long, modelId: String) = "$providerId::$modelId"

    /** ★ v1.107 查询单模型健康状态（对外 /v1/models 展示用；未测过默认健康） */
    fun healthOf(providerId: Long, modelId: String): ModelHealth? = synchronized(healthCache) {
        healthCache[routeKey(providerId, modelId)]
    }

    private val sharedClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(HEALTH_CHECK_TIMEOUT, TimeUnit.MILLISECONDS)
            .readTimeout(READ_TIMEOUT, TimeUnit.MILLISECONDS)
            .build()
    }

    // ============ 三指标测速器（对齐原APP ModelSpeedTester.kt） ============

    private const val DEFAULT_PROMPT = "请用一句话介绍你自己。"
    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    /**
     * 测速单个模型（SSE 流式解析 + 非SSE JSON 兼容），返回三指标 ModelHealth
     * 严格对齐原APP ModelSpeedTester.measure()
     */
    suspend fun measureModel(model: AiModel, provider: Provider): ModelHealth = withContext(Dispatchers.IO) {
        val path = provider.chatPath?.let { if (it.startsWith("/")) it else "/$it" } ?: "/v1/chat/completions"
        val url = provider.resolvedBaseUrl.trimEnd('/') + path
        val body = buildPayload(model.modelId, DEFAULT_PROMPT)
        val request = okhttp3.Request.Builder()
            .url(url)
            .post(body.toRequestBody(JSON_TYPE))
            .apply { if (!provider.apiKey.isNullOrBlank()) header("Authorization", "Bearer ${provider.apiKey}") }
            .build()

        val t0 = System.currentTimeMillis()
        var tFirst: Long? = null
        var tEnd: Long = 0L
        var tokenCount = 0
        var httpOk = false

        try {
            val resp = runCatching { sharedClient.newCall(request).execute() }.getOrNull()
            if (resp == null) return@withContext failedHealth(model.modelId, model.providerId)

            resp.use { r ->
                if (!r.isSuccessful) return@withContext failedHealth(model.modelId, model.providerId)
                httpOk = true

                val contentType = r.header("Content-Type", "") ?: ""
                val isSSE = "text/event-stream" in contentType
                val body_ = r.body ?: return@withContext failedHealth(model.modelId, model.providerId)

                if (isSSE) {
                    // ===== SSE 流式解析 =====
                    val source = body_.source()
                    while (!source.exhausted()) {
                        if (System.currentTimeMillis() - t0 > MODEL_TIMEOUT) break
                        val line = source.readUtf8Line() ?: continue
                        if (!line.startsWith("data:")) continue
                        val data = line.removePrefix("data:").trim()
                        if (data == "[DONE]" || data == """{"done":true}""") break
                        val delta = parseDelta(data) ?: continue
                        if (delta.isNotEmpty()) {
                            if (tFirst == null) tFirst = System.currentTimeMillis()
                            tokenCount += estimateTokens(delta)
                            tEnd = System.currentTimeMillis()
                        }
                    }
                    if (tEnd == 0L) tEnd = System.currentTimeMillis()
                } else {
                    // ===== 非SSE：完整JSON =====
                    val fullBody = body_.string()
                    val jsonObj = try { JSONObject(fullBody) } catch (_: Exception) { null }
                    val msg = jsonObj?.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                    val content = msg?.optString("content", "") ?: ""
                    if (content.isNotEmpty()) {
                        tFirst = System.currentTimeMillis()
                        tEnd = tFirst!!
                        tokenCount = estimateTokens(content)
                    }
                }
            }
        } catch (_: Exception) {
            return@withContext failedHealth(model.modelId, model.providerId)
        }

        val ttft = if (tFirst != null) (tFirst!! - t0) else -1
        val total = if (tEnd > 0) tEnd - t0 else -1
        val tps = if (tEnd > 0 && tokenCount > 0) {
            val decodeMs = if (tFirst != null && tEnd > tFirst!!) (tEnd - tFirst!!).toDouble() else 0.0
            if (decodeMs > 0) tokenCount / (decodeMs / 1000.0) else 0.0
        } else 0.0

        if (ttft < 0 && total < 0) {
            failedHealth(model.modelId, model.providerId)
        } else {
            ModelHealth(
                modelId = model.modelId,
                providerId = model.providerId,
                latencyMs = if (total > 0) total else ttft,
                ttftMs = ttft,
                tps = tps,
                totalMs = total,
                tokenCount = tokenCount,
                lastCheckTime = System.currentTimeMillis(),
                isHealthy = httpOk && ttft >= 0
            )
        }
    }

    private fun failedHealth(modelId: String, providerId: Long): ModelHealth =
        ModelHealth(modelId, providerId, Long.MAX_VALUE, -1, 0.0, -1, 0, System.currentTimeMillis(), false)

    private fun parseDelta(jsonStr: String): String? = try {
        val obj = JSONObject(jsonStr)
        val choices = obj.optJSONArray("choices") ?: return null
        val choice = choices.optJSONObject(0) ?: return null
        choice.optJSONObject("delta")?.optString("content", null)
    } catch (_: Exception) { null }

    private fun estimateTokens(text: String): Int {
        val chinese = text.count { it in '\u4e00'..'\u9fa5' }
        val other = text.length - chinese
        return (chinese * 0.65 + other / 4.0).toInt().coerceAtLeast(1)
    }

    private fun buildPayload(modelId: String, prompt: String): String = JSONObject().apply {
        put("model", modelId)
        put("stream", true)
        put("max_tokens", 200)
        put("temperature", 0.7)
        put("messages", org.json.JSONArray().apply {
            put(JSONObject().apply {
                put("role", "user")
                put("content", prompt)
            })
        })
    }.toString()

    /**
     * ★ v1.269 多模态通道探针：用最小请求验证该模型指定模态通道是否真能用。
     * - vision：传 1 张 1x1 base64 PNG，看上游是否接受 image_url 内容块
     * - audio/video：当前上游普遍要求真实文件，保守用「模型能力声明 + 文本通道正常」判定（避免造大请求打爆配额）
     */
    suspend fun probeModality(model: AiModel, provider: Provider, modality: String): Boolean = withContext(Dispatchers.IO) {
        if (modality != "vision") {
            // 音频/视频：无标准轻量探针，按「模型声明支持 + 服务商启用」视为通道可用（不额外烧配额）
            return@withContext model.supportsAudio || model.supportsVideo
        }
        val path = provider.chatPath?.let { if (it.startsWith("/")) it else "/$it" } ?: "/v1/chat/completions"
        val url = provider.resolvedBaseUrl.trimEnd('/') + path
        // 1x1 透明 PNG（最小图片探针）
        val tinyPng = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="
        val body = JSONObject().apply {
            put("model", model.modelId)
            put("stream", false)
            put("max_tokens", 8)
            put("messages", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", org.json.JSONArray().apply {
                        put(JSONObject().apply { put("type", "text"); put("text", "hi") })
                        put(JSONObject().apply {
                            put("type", "image_url")
                            put("image_url", JSONObject().put("url", "data:image/png;base64,$tinyPng"))
                        })
                    })
                })
            })
        }.toString()
        val req = okhttp3.Request.Builder()
            .url(url)
            .post(body.toRequestBody(JSON_TYPE))
            .apply { if (!provider.apiKey.isNullOrBlank()) header("Authorization", "Bearer ${provider.apiKey}") }
            .build()
        runCatching {
            sharedClient.newCall(req).execute().use { r -> r.isSuccessful }
        }.getOrDefault(false)
    }

    // ============ 健康缓存更新 ============

    fun markModelSuccess(modelId: String, providerId: Long, latencyMs: Long) {
        val key = routeKey(providerId, modelId)
        synchronized(healthCache) {
            val existing = healthCache[key]
            val successCount = (existing?.successCount ?: 0) + 1
            healthCache[key] = ModelHealth(
                modelId = modelId,
                providerId = providerId,
                latencyMs = latencyMs,
                totalMs = latencyMs,
                ttftMs = existing?.ttftMs ?: -1,
                tps = existing?.tps ?: 0.0,
                tokenCount = existing?.tokenCount ?: 0,
                lastCheckTime = System.currentTimeMillis(),
                isHealthy = true,
                successCount = successCount
            )
        }
        if (latencyMs < bestModelLatency || bestModelKey == null) {
            bestModelKey = key
            bestModelLatency = latencyMs
            bestModelSetTime = System.currentTimeMillis()
        }
    }

    fun markModelFailed(modelId: String, providerId: Long) {
        val key = routeKey(providerId, modelId)
        synchronized(healthCache) {
            healthCache[key] = ModelHealth(
                modelId = modelId,
                providerId = providerId,
                latencyMs = Long.MAX_VALUE,
                lastCheckTime = System.currentTimeMillis(),
                isHealthy = false
            )
        }
    }

    // ============ ★ v1.265 失败分类与冷却（参考业界网关 channel cooldown 思路，自研实现） ============
    /**
     * 模型冷却表：key -> 冷却截止时间戳(ms)。冷却中的模型在故障转移时排到最后，避免反复撞墙。
     * 不同错误不同冷却时长：429 配额限流=长冷却；5xx 上游故障=中冷却；超时=短冷却。
     */
    private val cooldownUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * 按失败类型设置冷却。在 forwardToUpstream 判定失败时调用。
     * @param httpCode 上游 HTTP 状态码（0/负数=网络异常或超时）
     * @param elapsedMs 本次尝试耗时
     */
    fun markModelCooldown(modelId: String, providerId: Long, httpCode: Int) {
        val key = routeKey(providerId, modelId)
        val now = System.currentTimeMillis()
        val cdMs = when {
            httpCode == 429 -> 3 * 60_000L        // 配额限流：冷却 3 分钟（等配额窗口）
            httpCode == 401 || httpCode == 403 -> 10 * 60_000L // 鉴权/权限错：冷却 10 分钟（需人工修配置）
            httpCode in 500..599 -> 2 * 60_000L   // 上游 5xx：冷却 2 分钟
            httpCode == 0 || httpCode < 0 -> 45_000L // 网络异常/超时：冷却 45 秒
            else -> 0L                             // 其他（400 参数错等）：不冷却（换模型也没用，但可能是模型特有）
        }
        if (cdMs > 0) {
            cooldownUntil[key] = now + cdMs
            println("[Scheduler] 模型冷却 ${providerId}::$modelId code=$httpCode 时长=${cdMs / 1000}s")
        }
    }

    /** 该模型当前是否在冷却中 */
    fun isCoolingDown(modelId: String, providerId: Long): Boolean {
        val key = routeKey(providerId, modelId)
        val until = cooldownUntil[key] ?: return false
        if (System.currentTimeMillis() >= until) { cooldownUntil.remove(key); return false }
        return true
    }

    /** 清理已过期的冷却项（避免内存堆积） */
    fun pruneCooldowns() {
        val now = System.currentTimeMillis()
        cooldownUntil.entries.removeIf { it.value <= now }
    }

    /** 冷却状态快照（前端展示「冷却中」用） */
    fun cooldownSnapshot(): Map<String, Long> {
        pruneCooldowns()
        return cooldownUntil.toMap()
    }

    fun getBestModel(): String? {
        if (bestModelKey != null && System.currentTimeMillis() - bestModelSetTime < BEST_MODEL_TTL) {
            return bestModelKey
        }
        return null
    }

    fun recordModelResult(modelId: String, providerId: Long, success: Boolean) {
        val key = routeKey(providerId, modelId)
        synchronized(modelHistory) {
            val existing = modelHistory[key]
            modelHistory[key] = ModelHistoryRecord(
                modelId = modelId,
                providerId = providerId,
                lastSuccessTime = if (success) System.currentTimeMillis() else (existing?.lastSuccessTime ?: 0),
                successCount = (existing?.successCount ?: 0) + if (success) 1 else 0,
                failCount = (existing?.failCount ?: 0) + if (success) 0 else 1,
                lastFailTime = if (!success) System.currentTimeMillis() else (existing?.lastFailTime ?: 0),
                isHealthy = success
            )
        }
    }

    /** 按健康度排序模型（健康+延迟优先） */
    fun getSortedModels(models: List<AiModel>): List<AiModel> {
        return models.sortedBy { model ->
            val health = synchronized(healthCache) { healthCache[routeKey(model.providerId, model.modelId)] }
            if (health != null && health.isHealthy) health.latencyMs else Long.MAX_VALUE
        }
    }

    /** 智能分层排序 a→b→c→d */
    fun smartSort(models: List<AiModel>): List<AiModel> {
        val now = System.currentTimeMillis()
        val tierA = mutableListOf<AiModel>()
        val tierB = mutableListOf<AiModel>()
        val tierC = mutableListOf<AiModel>()
        val tierD = mutableListOf<AiModel>()

        for (model in models) {
            val key = routeKey(model.providerId, model.modelId)
            val history = synchronized(modelHistory) { modelHistory[key] }
            val isPipelineSuccess = key in pipelineSortedModelKeys
            when {
                isPipelineSuccess && history != null && history.isHealthy -> tierA.add(model)
                history != null && history.successCount > 0 && now - history.lastSuccessTime < MODEL_HISTORY_TTL -> tierB.add(model)
                history != null && history.failCount > 0 -> tierC.add(model)
                else -> tierD.add(model)
            }
        }

        val speedOrder = pipelineSortedModelKeys.withIndex().associate { it.value to it.index }
        return tierA.sortedBy { speedOrder[it.routeKey()] ?: Int.MAX_VALUE } +
            tierB.sortedBy { speedOrder[it.routeKey()] ?: Int.MAX_VALUE } +
            tierC.sortedBy { speedOrder[it.routeKey()] ?: Int.MAX_VALUE } +
            tierD.sortedBy { speedOrder[it.routeKey()] ?: Int.MAX_VALUE }
    }

    private fun AiModel.routeKey() = routeKey(providerId, modelId)

    /**
     * 串行测速所有启用模型（对齐原APP：逐个串行，500ms 间隔防限流）
     * 三指标全部写入 healthCache
     */
    suspend fun refreshHealthCache(database: Database, force: Boolean = false): List<ModelHealth> {
        val now = System.currentTimeMillis()
        if (!force && now - cacheTime < CACHE_TTL) {
            return synchronized(healthCache) { healthCache.values.toList() }
        }

        val models = database.getEnabledModels()
        if (models.isEmpty()) return emptyList()
        cacheTime = now

        val results = mutableListOf<ModelHealth>()
        for (model in models) {
            val key = routeKey(model.providerId, model.modelId)
            try {
                val provider = database.getProviderById(model.providerId) ?: continue
                if (!provider.isEnabled) {
                    synchronized(healthCache) {
                        healthCache[key] = ModelHealth(model.modelId, model.providerId, Long.MAX_VALUE, lastCheckTime = now, isHealthy = false)
                    }
                    results.add(healthCache[key]!!)
                    continue
                }

                // ★ v1.262 测速节流：每个模型之间间隔 300ms，避免全量测速瞬间打爆上游配额导致真实业务请求全 429
                kotlinx.coroutines.delay(300)
                var h = measureModel(model, provider)
                // ★ v1.270 通道判定：文本失败时补测多模态通道（文本/图片/语音/视频任一通即可用）
                var chCount = if (h.isHealthy) 1 else 0
                if (!h.isHealthy && (model.supportsVision || model.supportsAudio || model.supportsVideo)) {
                    if (model.supportsVision && runCatching { probeModality(model, provider, "vision") }.getOrDefault(false)) chCount++
                    if (model.supportsAudio && runCatching { probeModality(model, provider, "audio") }.getOrDefault(false)) chCount++
                    if (model.supportsVideo && runCatching { probeModality(model, provider, "video") }.getOrDefault(false)) chCount++
                    if (chCount > 0) h = h.copy(isHealthy = true)
                } else if (h.isHealthy) {
                    chCount = 1
                }
                h = h.copy(channelCount = chCount)
                synchronized(healthCache) {
                    val existing = healthCache[key]
                    healthCache[key] = h.copy(
                        successCount = if (h.isHealthy) (existing?.successCount ?: 0) + 1 else 0
                    )
                }
                // ★ v1.265 测速历史落库（原表建了从不写 → 无法看趋势/均值；参考业界网关用量分析思路）
                runCatching {
                    database.addSpeedHistory(com.qitong.gateway.model.SpeedHistory(
                        modelKey = key,
                        modelName = model.modelId,
                        providerId = model.providerId,
                        ttftMs = h.ttftMs.toInt(),
                        tps = h.tps,
                        totalMs = h.totalMs.toInt(),
                        success = h.isHealthy,
                        measuredAt = System.currentTimeMillis()
                    ))
                }
                if (h.isHealthy && h.latencyMs < bestModelLatency || bestModelKey == null) {
                    bestModelKey = key
                    bestModelLatency = h.latencyMs
                    bestModelSetTime = now
                }
                results.add(h)
            } catch (_: Exception) {
                synchronized(healthCache) {
                    // ★ v1.262 防误伤：测速偶发失败（429/超时）不立刻判死，保留上次健康状态；
                    //   只有连续失败（successCount 已归零且上次也不健康）才标记不可用，避免测速把可用模型下线导致业务全失败
                    val existing = healthCache[key]
                    val wasHealthy = existing?.isHealthy == true
                    healthCache[key] = ModelHealth(
                        model.modelId, model.providerId, Long.MAX_VALUE,
                        lastCheckTime = now,
                        isHealthy = if (wasHealthy) true else false   // 上次健康则保留健康，避免单次抖动误杀
                    )
                }
                results.add(healthCache[key]!!)
            }
            delay(500) // 串行间隔，防限流
        }
        // ★ v1.166 系统动态持久化：测速完成（每次完整测速跑完记录一次）
        val healthyCount = results.count { it.isHealthy }
        runCatching {
            database.addGatewayLog("speedtest", "测速完成：${healthyCount}/${results.size} 正常")
        }
        return results
    }

    /** 构建测速排行榜 key 列表（仅健康模型，按延迟升序） */
    fun buildPipelineSortedModels(database: Database): List<String> {
        val healthList = synchronized(healthCache) {
            healthCache.entries
                .filter { it.value.isHealthy }
                .sortedBy { it.value.latencyMs }
                .map { it.key }
        }
        pipelineSortedModelKeys = healthList
        return healthList
    }

    /**
     * ★ v1.267 全量排行（首页排行榜用）：返回全部已启用模型 + 测速状态，
     * 健康模型按延迟升序在前，未测/失败的在后 —— 让用户一眼看出"哪些能用、哪些不能用"。
     * 原 buildPipelineSortedModels 只含健康模型，导致测速失败的模型在首页完全消失。
     */
    fun buildFullRankList(database: Database): List<String> {
        val enabled = database.getEnabledModels()
        val snapshot = synchronized(healthCache) { HashMap(healthCache) }
        val healthy = mutableListOf<String>()
        val untested = mutableListOf<String>()
        val failed = mutableListOf<String>()
        for (m in enabled) {
            val k = routeKey(m.providerId, m.modelId)
            val h = snapshot[k]
            when {
                h == null -> untested.add(k)
                h.isHealthy -> healthy.add(k)
                else -> failed.add(k)
            }
        }
        val healthySorted = healthy.sortedBy { snapshot[it]?.latencyMs ?: Long.MAX_VALUE }
        return healthySorted + untested + failed
    }

    private val DEFAULT_CT = "application/json".toMediaType()
}