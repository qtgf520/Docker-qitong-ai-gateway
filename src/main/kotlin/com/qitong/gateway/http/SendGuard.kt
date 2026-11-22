package com.qitong.gateway.http

/**
 * 发送幂等守卫：同一入站消息(msgId)向同一目标重复发送同一内容时拦截。
 * 只拦"完全相同的文本"，且仅在同一 msgId 上下文内生效，正常的分段/不同内容不受影响。
 */
object SendGuard {
    private const val TTL_MS = 300_000L

    private data class K(val channel: String, val target: String, val msgId: String, val hash: Int)
    private val seen = java.util.concurrent.ConcurrentHashMap<K, Long>()

    fun allow(channel: String, target: String, msgId: String, content: String): Boolean {
        if (content.isBlank() || msgId.isBlank()) return true
        val now = System.currentTimeMillis()
        if (seen.size > 5000) seen.entries.removeIf { now - it.value > TTL_MS }
        val k = K(channel, target, msgId, content.hashCode())
        val prev = seen.putIfAbsent(k, now)
        if (prev == null) return true
        if (now - prev > TTL_MS) { seen[k] = now; return true }
        return false
    }
}
