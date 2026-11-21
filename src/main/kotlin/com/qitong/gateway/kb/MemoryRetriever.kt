package com.qitong.gateway.kb

import com.qitong.gateway.db.Database
import java.util.concurrent.ConcurrentHashMap

/**
 * ★ v1.232 记忆自动检索（P0-A：补齐「记忆不会查」缺口）
 *
 * 设计：
 * - 三渠道（chat/qq/weixin）在消息进入 Agent 前调用 retrieve()，把命中的知识库/记忆
 *   以【相关记忆】段落注入系统提示，让 AI 每轮自动回忆相关上下文。
 * - 检索源：知识库 BM25（KBEngine.search，topK=3，score>=0.35 才保留），跨库合并按分降序取 3。
 * - 缓存：进程内 ConcurrentHashMap，key=userId+归一化 query，TTL 5 分钟，避免同问重复全表扫。
 * - 容错：全部逻辑 runCatching 包裹，任何异常返回空串，绝不因为记忆检索失败影响消息回复。
 * - 观测：日志打印 [MemoryRetriever] hits=N top=0.xx cost=Xms，供面板判断命中情况。
 */
object MemoryRetriever {

    /** 注入结果缓存：key -> Pair(过期时间戳, 结果) */
    private val cache = ConcurrentHashMap<String, Pair<Long, String>>()

    /** 缓存 TTL（毫秒）= 5 分钟 */
    private const val CACHE_TTL_MS = 5 * 60 * 1000L

    /** 单条检索 topK */
    private const val TOP_K = 3

    /** 保留最低分（BM25 归一化前若低于此分视为不相关） */
    private const val MIN_SCORE = 0.35

    /** 注入总长上限（token） */
    private const val MAX_TOKENS = 600

    /**
     * 检索并组装记忆注入段。
     *
     * @param db 数据库实例
     * @param userId 网关用户 ID（0=未绑定用户，仍走公共知识库）
     * @param query 当前用户消息原文（用于关键词检索）
     * @return 拼好的【相关记忆】段落（无命中/异常时返回空串）
     */
    fun retrieve(db: Database, userId: Long, query: String): String {
        return try {
            val t0 = System.currentTimeMillis()
            val q = query.trim()
            if (q.isBlank()) return ""

            // 1) 缓存查询：未过期直接复用
            val cacheKey = "$userId|" + normalize(q)
            cache[cacheKey]?.let { (expireAt, cached) ->
                if (System.currentTimeMillis() < expireAt) {
                    if (cached.isNotBlank()) {
                        println("[MemoryRetriever] hits=${cached.split("·").size - 1} top=cache cost=${System.currentTimeMillis() - t0}ms")
                    }
                    return cached
                }
                cache.remove(cacheKey)
            }

            // 2) 检索：对用户可见知识库逐个 BM25 检索，合并结果
            val kbIds = runCatching {
                db.getKbBases(userId).mapNotNull { (it["id"] as? Number)?.toLong() }
            }.getOrDefault(emptyList())

            // 用户库可能为空时至少覆盖公共库（ownerId=0 已在 getKbBases 内包含）
            val hits = mutableListOf<Pair<Double, String>>()
            for (kid in kbIds) {
                runCatching {
                    val res = KBEngine.search(db, kid, q, TOP_K)
                    res.forEach { r ->
                        val score = (r["score"] as? Number)?.toDouble() ?: 0.0
                        val content = (r["content"] as? String ?: "").trim()
                        if (score >= MIN_SCORE && content.isNotBlank()) {
                            hits.add(score to content)
                        }
                    }
                }
            }

            // 3) 排序取前 3，拼装【相关记忆】段落，总长<=600 token
            hits.sortByDescending { it.first }
            val top3 = hits.take(3)
            val sb = StringBuilder()
            top3.forEach { (score, content) ->
                sb.append("\n· ").append(content.take(200))
            }
            var section = if (top3.isEmpty()) "" else "\n\n【相关记忆】" + sb.toString()
            // token 截断（KBEngine.estimateTokens 估算中英文混合）
            while (section.isNotBlank() && KBEngine.estimateTokens(section) > MAX_TOKENS) {
                section = section.take(section.length - 30)
            }

            // 4) 写缓存（即使无命中也缓存 30s 防抖，避免空库反复全扫）
            cache[cacheKey] = (System.currentTimeMillis() + CACHE_TTL_MS) to section

            // 5) 观测日志
            val topScore = top3.firstOrNull()?.first ?: 0.0
            if (top3.isNotEmpty()) {
                println("[MemoryRetriever] hits=${top3.size} top=${"%.2f".format(topScore)} cost=${System.currentTimeMillis() - t0}ms")
            }
            section
        } catch (e: Exception) {
            // 容错：记忆检索失败绝不阻断消息回复
            println("[MemoryRetriever] 检索异常已跳过: ${e.message}")
            ""
        }
    }

    /** 归一化 query：小写 + 去空白，用于缓存 key */
    private fun normalize(q: String): String =
        q.lowercase().replace(Regex("\\s+"), " ").trim()
}