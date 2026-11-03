package com.qitong.gateway.http

import com.qitong.gateway.db.Database
import com.qitong.gateway.model.AiModel
import com.qitong.gateway.model.Provider
import com.qitong.gateway.network.UpstreamClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * ★ v1.172 预测式预热管理器（对齐原APP v3.18.24 PredictivePrewarmManager 的纯JVM版）
 *
 * 核心思路：qtai-sj 思考阶段（大脑请求发出时）提前记录当前模型特征，
 * 按三级策略预测下一个可能要切换的模型，并对预测目标发轻量 HEAD 请求，
 * 提前完成 TCP + TLS + HTTP/2 握手，连接放入 OkHttp 共享连接池。
 * 真正发生故障转移时，切换目标的连接已就绪 → 毫秒级完成（切换零感知）。
 *
 * 三级预测策略（对齐原APP）：
 *   ① 强制故障池中当前命中模型的下一个（池序=切换优先）
 *   ② 测速排行榜中当前模型的下一个健康模型（按延迟升序）
 *   ③ 兜底：全局最优模型（getBestModel），再兜底排序首个
 *
 * 设计约束（不破坏现有功能）：
 *   - 预热完全后台异步，不阻塞主请求（GlobalScope + Dispatchers.IO）
 *   - 轻量 HEAD 请求，5s 超时，失败静默（不抛异常、不影响主链路）
 *   - 复用 UpstreamClient 共享连接池（newBuilder 保留 connectionPool）
 *   - 已预热且有 TTL 期限（2 分钟）内不重复预热，避免浪费
 */
object PredictivePrewarm {

    /** 最近一次思考请求的上下文（用于预测下一次切换目标） */
    data class RequestContext(
        val modelId: String = "",
        val providerId: Long = 0L,
        val requestType: String = "text",   // text/vision/audio/video（当前仅记录，预测按模型走）
        val userId: Long = 0L,              // 用户池隔离：>0 用户池，<=0 全局池
        val at: Long = System.currentTimeMillis()
    )

    @Volatile
    private var lastContext: RequestContext? = null

    /** 已预热目标 key（providerId::modelId），避免重复预热 */
    @Volatile
    private var prewarmedKey: String? = null
    @Volatile
    private var prewarmedAt: Long = 0L

    /** 预热连接有效期（2 分钟，超过视为失效需重新预热） */
    private const val PREWARM_TTL = 120_000L

    /** 预热统计（供状态查询/排障，不写库） */
    @Volatile
    var prewarmCount: Int = 0          // 累计触发预热次数（成功建立连接）
    @Volatile
    var prewarmHitCount: Int = 0       // 累计命中次数（故障转移切换到已预热目标）

    // ============ 门户方法 ============

    /** 思考阶段触发：记录当前请求特征 + 后台三级预测预热（非阻塞） */
    fun notifyThinkingStarted(
        database: Database,
        modelId: String,
        providerId: Long = 0L,
        requestType: String = "text",
        userId: Long = 0L
    ) {
        lastContext = RequestContext(modelId, providerId, requestType, userId)
        // 后台异步预热，绝不阻塞主请求
        GlobalScope.launch(Dispatchers.IO) {
            runCatching { prewarm(database) }
        }
    }

    /** 切换完成登记：实际命中模型已切换，清空预热状态并计数 */
    fun notifySwitchCompleted(key: String) {
        if (key.isNotBlank() && prewarmedKey == key) {
            prewarmHitCount++
        }
        prewarmedKey = null
        prewarmedAt = 0L
        lastContext = null
    }

    /** 查询某目标是否已预热且未过期（供排障/展示；故障转移本身无需处理——连接已入池） */
    fun isPrewarmed(providerId: Long, modelId: String): Boolean {
        val k = "$providerId::$modelId"
        return prewarmedKey == k && System.currentTimeMillis() - prewarmedAt < PREWARM_TTL
    }

    /** 当前预热状态（对外展示用） */
    fun snapshot(): Map<String, Any?> = mapOf(
        "prewarmedKey" to (prewarmedKey ?: ""),
        "prewarmedAt" to prewarmedAt,
        "prewarmCount" to prewarmCount,
        "prewarmHitCount" to prewarmHitCount,
        "active" to (prewarmedKey != null)
    )

    // ============ 预热内部实现 ============

    /** 三级预测 + 预热（失败静默） */
    private suspend fun prewarm(database: Database) {
        val ctx = lastContext ?: return
        if (ctx.modelId.isBlank()) return
        val target = predict(database, ctx) ?: return
        val key = "${target.providerId}::${target.modelId}"
        // 已预热且未过期 → 跳过（避免每次思考都重复打 HEAD）
        if (prewarmedKey == key && System.currentTimeMillis() - prewarmedAt < PREWARM_TTL) return
        val provider = database.getProviderById(target.providerId) ?: return
        if (!provider.isEnabled) return
        prewarmConnection(provider, target)
        prewarmedKey = key
        prewarmedAt = System.currentTimeMillis()
        prewarmCount++
    }

    /**
     * 三级预测策略（对齐原APP：①强制池下一个 → ②测速榜下一个 → ③健康最优）
     * 均要求目标模型 启用 + 健康（避免预热一个挂了/禁用的模型白费）
     */
    private fun predict(database: Database, ctx: RequestContext): AiModel? {
        val models = database.getEnabledModels().filter { it.isEnabled }
        if (models.isEmpty()) return null
        val sorted = GatewayScheduler.getSortedModels(models)
            .filter { GatewayScheduler.healthCache[GatewayScheduler.routeKey(it.providerId, it.modelId)]?.isHealthy != false }

        // ① 强制故障池下一个（用户池优先，其次全局池；池序=切换优先）
        val poolNext = runCatching {
            val poolRaw = if (ctx.userId > 0) database.getUserConfig(ctx.userId, "forced_pool_keys", "")
                else database.getConfig("forced_pool_keys", "")
            val pool = poolRaw.split(",").map { it.trim() }.filter { it.isNotBlank() }
            if (pool.isNotEmpty()) {
                val curKey = "${ctx.providerId}::${ctx.modelId}"
                val curIdx = pool.indexOfFirst { it == curKey || it.endsWith("::${ctx.modelId}") }
                val nextKey = if (curIdx in 0 until pool.size - 1) pool[curIdx + 1] else null
                if (nextKey != null) {
                    val parts = nextKey.split("::")
                    sorted.firstOrNull {
                        (parts.size == 2 && it.providerId.toString() == parts[0]) && it.modelId == parts.getOrNull(1)
                    } ?: sorted.firstOrNull { it.modelId == nextKey }
                } else null
            } else null
        }.getOrNull()

        // ② 测速排行榜下一个（当前模型在榜上的下一个健康模型；不在榜取榜首）
        val rankNext = runCatching {
            val keys = GatewayScheduler.pipelineSortedModelKeys
            if (keys.isNotEmpty()) {
                val curKey = "${ctx.providerId}::${ctx.modelId}"
                val curIdx = keys.indexOfFirst { it == curKey || it.endsWith("::${ctx.modelId}") }
                val nextKey = if (curIdx in 0 until keys.size - 1) keys[curIdx + 1] else keys.first()
                val parts = nextKey.split("::")
                sorted.firstOrNull {
                    (parts.size == 2 && it.providerId.toString() == parts[0]) && it.modelId == parts.getOrNull(1)
                } ?: sorted.firstOrNull { it.modelId == nextKey }
            } else null
        }.getOrNull()

        // ③ 兜底：全局最优模型 → 排序首个
        val best = GatewayScheduler.getBestModel()?.let { bestKey ->
            val parts = bestKey.split("::")
            sorted.firstOrNull {
                (parts.size == 2 && it.providerId.toString() == parts[0]) && it.modelId == parts.getOrNull(1)
            } ?: sorted.firstOrNull { it.modelId == bestKey }
        }

        return poolNext ?: rankNext ?: best ?: sorted.firstOrNull()
    }

    /** 对预测目标发轻量 HEAD 请求：完成 TCP+TLS 握手，连接进共享池（5s 超时，失败静默） */
    private fun prewarmConnection(provider: Provider, model: AiModel) {
        runCatching {
            val base = provider.resolvedBaseUrl.trimEnd('/')
            val path = provider.chatPath?.let { if (it.startsWith("/")) it else "/$it" } ?: "/v1/chat/completions"
            val url = "$base$path"
            // newBuilder 保留共享 connectionPool/dispatcher，仅收紧超时（5s 快速失败不阻塞）
            val client = UpstreamClient.getClient(useProxy = model.useProxy)
                .newBuilder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .writeTimeout(5, TimeUnit.SECONDS)
                .callTimeout(5, TimeUnit.SECONDS)
                .build()
            val req = okhttp3.Request.Builder()
                .url(url)
                .method("HEAD", null)
                .apply { if (!provider.apiKey.isNullOrBlank()) header("Authorization", "Bearer ${provider.apiKey}") }
                .build()
            // 只要 execute 完成（无论 2xx/4xx/5xx），TCP+TLS 握手已完成，连接归还共享池
            client.newCall(req).execute().use { }
        }
    }
}