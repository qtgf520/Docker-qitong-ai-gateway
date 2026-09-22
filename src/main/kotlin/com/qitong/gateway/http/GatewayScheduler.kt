package com.qitong.gateway.http

import com.qitong.gateway.db.Database
import com.qitong.gateway.model.AiModel
import com.qitong.gateway.model.Provider
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 网关调度器 —— 健康检查、测速排行榜、智能故障转移
 * 对齐原APP GatewayScheduler.kt
 */
object GatewayScheduler {

    private const val HEALTH_CHECK_TIMEOUT = 5000L
    private const val CACHE_TTL = 60_000L
    private const val BEST_MODEL_TTL = 300_000L
    private const val MODEL_HISTORY_TTL = 30 * 60 * 1000L

    /** 测速排行榜（providerId::modelId 形式） */
    @Volatile
    var pipelineSortedModelKeys: List<String> = emptyList()

    data class ModelHealth(
        val modelId: String,
        val providerId: Long,
        val latencyMs: Long = Long.MAX_VALUE,
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

    fun markModelSuccess(modelId: String, providerId: Long, latencyMs: Long) {
        val key = routeKey(providerId, modelId)
        synchronized(healthCache) {
            val existing = healthCache[key]
            val successCount = (existing?.successCount ?: 0) + 1
            healthCache[key] = ModelHealth(
                modelId = modelId,
                providerId = providerId,
                latencyMs = latencyMs,
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

    /** 串行测速所有启用模型（间隔500ms），返回测速结果 */
    suspend fun refreshHealthCache(database: Database): List<ModelHealth> {
        val now = System.currentTimeMillis()
        if (now - cacheTime < CACHE_TTL) {
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
                if (!provider.isEnabled) continue

                val start = System.currentTimeMillis()
                val resolvedUrl = provider.resolvedBaseUrl.trimEnd('/')
                val testBody = """{"model":"${model.modelId}","messages":[{"role":"user","content":"hi"}],"max_tokens":1,"stream":false}"""
                val request = okhttp3.Request.Builder()
                    .url("$resolvedUrl" + (provider.chatPath?.let { if (it.startsWith("/")) it else "/$it" } ?: "/v1/chat/completions"))
                    .post(testBody.toRequestBody(DEFAULT_CT))
                    .apply { if (!provider.apiKey.isNullOrBlank()) header("Authorization", "Bearer ${provider.apiKey}") }
                    .build()
                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(HEALTH_CHECK_TIMEOUT, TimeUnit.MILLISECONDS)
                    .readTimeout(HEALTH_CHECK_TIMEOUT, TimeUnit.MILLISECONDS)
                    .build()
                val response = runCatching { client.newCall(request).execute() }.getOrNull()
                val latency = System.currentTimeMillis() - start
                if (response?.isSuccessful == true) {
                    synchronized(healthCache) {
                        healthCache[key] = ModelHealth(model.modelId, model.providerId, latency, now, true)
                    }
                    if (latency < bestModelLatency || bestModelKey == null) {
                        bestModelKey = key
                        bestModelLatency = latency
                        bestModelSetTime = now
                    }
                    results.add(ModelHealth(model.modelId, model.providerId, latency, now, true))
                } else {
                    synchronized(healthCache) {
                        healthCache[key] = ModelHealth(model.modelId, model.providerId, Long.MAX_VALUE, now, false)
                    }
                    results.add(ModelHealth(model.modelId, model.providerId, Long.MAX_VALUE, now, false))
                }
                response?.close()
            } catch (_: Exception) {
                synchronized(healthCache) {
                    healthCache[key] = ModelHealth(model.modelId, model.providerId, Long.MAX_VALUE, now, false)
                }
                results.add(ModelHealth(model.modelId, model.providerId, Long.MAX_VALUE, now, false))
            }
            delay(500) // 串行间隔，防限流
        }
        return results
    }

    /** 构建测速排行榜 key 列表 */
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