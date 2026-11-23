package com.qitong.gateway.http

/**
 * 入站消息幂等：同一渠道同一条 msgId 只处理一次。
 * 用途：长轮询重连/多线程重叠时同一消息被重复投递 → 只回一次，不再"发两遍"。
 */
object InboundGuard {
    private const val TTL_MS = 600_000L
    private val seen = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** ★ v1.256 内容去重：同一用户 5 秒内重复投递同一句话只处理一次（QQ 双事件/重连补投） */
    private val contentSeen = java.util.concurrent.ConcurrentHashMap<String, Long>()
    fun firstContent(channel: String, userKey: String, content: String, windowMs: Long = 5000L): Boolean {
        val norm = content.trim()
        if (norm.isBlank()) return true
        val now = System.currentTimeMillis()
        if (contentSeen.size > 20_000) contentSeen.entries.removeIf { now - it.value > 60_000L }
        val key = channel + ":" + userKey + ":" + norm.take(60)
        val prev = contentSeen.putIfAbsent(key, now)
        if (prev == null) return true
        if (now - prev > windowMs) { contentSeen[key] = now; return true }
        return false
    }

    fun first(channel: String, msgId: String): Boolean {
        if (msgId.isBlank()) return true
        val now = System.currentTimeMillis()
        if (seen.size > 20_000) seen.entries.removeIf { now - it.value > TTL_MS }
        val key = channel + ":" + msgId
        val prev = seen.putIfAbsent(key, now)
        if (prev == null) return true
        if (now - prev > TTL_MS) { seen[key] = now; return true }
        return false
    }
}
