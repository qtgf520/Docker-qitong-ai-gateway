package com.qitong.gateway.http

/** ★ v1.256 过程消息去重：同一目标 15 秒内相同/近似进度只发一次（防 💭 刷屏与重复投递） */
object ProcessGuard {
    private const val TTL_MS = 15_000L
    private data class K(val target: String, val hash: Int)
    private val seen = java.util.concurrent.ConcurrentHashMap<K, Long>()

    fun allow(target: String, content: String): Boolean {
        if (content.isBlank()) return false
        val now = System.currentTimeMillis()
        if (seen.size > 8000) seen.entries.removeIf { now - it.value > TTL_MS }
        val norm = content.replace(Regex("\\s+"), "").take(80).hashCode()
        val k = K(target, norm)
        val prev = seen.putIfAbsent(k, now)
        if (prev == null) return true
        if (now - prev > TTL_MS) { seen[k] = now; return true }
        return false
    }
}
