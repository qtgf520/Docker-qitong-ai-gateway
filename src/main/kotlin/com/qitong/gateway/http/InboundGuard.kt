package com.qitong.gateway.http

/**
 * 入站消息幂等：同一渠道同一条 msgId 只处理一次。
 * 用途：长轮询重连/多线程重叠时同一消息被重复投递 → 只回一次，不再"发两遍"。
 */
object InboundGuard {
    private const val TTL_MS = 600_000L
    private val seen = java.util.concurrent.ConcurrentHashMap<String, Long>()

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
