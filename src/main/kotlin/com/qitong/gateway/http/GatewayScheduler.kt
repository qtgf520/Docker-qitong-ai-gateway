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
        val successCount: Int = 0
    )

    val healthCache = mutableMapOf<String, ModelHealth>()
    private var cacheTime: Long = 0

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

                val h = measureModel(model, provider)
                synchronized(healthCache) {
                    val existing = healthCache[key]
                    healthCache[key] = h.copy(
                        successCount = if (h.isHealthy) (existing?.successCount ?: 0) + 1 else 0
                    )
                }
                if (h.isHealthy && h.latencyMs < bestModelLatency || bestModelKey == null) {
                    bestModelKey = key
                    bestModelLatency = h.latencyMs
                    bestModelSetTime = now
                }
                results.add(h)
            } catch (_: Exception) {
                synchronized(healthCache) {
                    healthCache[key] = ModelHealth(model.modelId, model.providerId, Long.MAX_VALUE, lastCheckTime = now, isHealthy = false)
                }
                results.add(healthCache[key]!!)
            }
            delay(500) // 串行间隔，防限流
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

    private val DEFAULT_CT = "application/json".toMediaType()
}