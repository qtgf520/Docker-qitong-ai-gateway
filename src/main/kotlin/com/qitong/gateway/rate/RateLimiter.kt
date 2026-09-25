package com.qitong.gateway.rate

import com.qitong.gateway.db.Database
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * API 限流器 —— 防刷防滥用（复用现有架构，轻量内存实现）
 * 1) QPS 限流：每秒每 Key 最多 N 次（默认 60，可配 user:{id}:rate_qps）
 * 2) 每日配额：每 Key 每日最多 M 次调用（默认 10000，可配 user:{id}:rate_daily）
 * 管理员 / 本地请求不限流
 */
object RateLimiter {

    private class Bucket {
        val window = LongArray(60)  // 每秒计数（环形秒窗）
        var lastSec = 0L
        val daily = AtomicLong(0)
        var lastDay = 0L
    }

    private val buckets = ConcurrentHashMap<String, Bucket>()

    private fun bucket(key: String): Bucket {
        val now = System.currentTimeMillis() / 1000
        return buckets.computeIfAbsent(key) {
            Bucket().apply { lastSec = now; lastDay = now / 86400 }
        }.also { b ->
            // 秒窗滚动
            if (now != b.lastSec) {
                val diff = (now - b.lastSec).coerceIn(0, 59).toInt()
                for (i in 0 until diff) {
                    val idx = ((b.lastSec + 1 + i) % 60).toInt()
                    b.window[idx] = 0
                }
                b.lastSec = now
            }
            // 日滚动
            val day = now / 86400
            if (day != b.lastDay) { b.daily.set(0); b.lastDay = day }
        }
    }

    private fun qpsInWindow(b: Bucket): Long {
        val now = System.currentTimeMillis() / 1000
        var sum = 0L
        // 当前秒 + 之前59秒
        for (i in 0 until 60) {
            val sec = now - i
            sum += b.window[(sec % 60).toInt()]
        }
        return sum
    }

    private fun record(b: Bucket) {
        val now = System.currentTimeMillis() / 1000
        b.window[(now % 60).toInt()]++
        b.daily.incrementAndGet()
    }

    /**
     * 检查是否允许请求
     * @return null=放行；非null=拒绝原因（已含提示文案）
     */
    fun check(database: Database, apiKey: String, ownerId: Long, isAdmin: Boolean): String? {
        if (isAdmin) return null
        if (ownerId <= 0) return null

        // 读取用户限流配置（未配置用默认值）
        val qpsLimit = database.getUserConfig(ownerId, "rate_qps", "").toIntOrNull() ?: 60
        val dailyLimit = database.getUserConfig(ownerId, "rate_daily", "").toIntOrNull() ?: 10000
        if (qpsLimit <= 0 && dailyLimit <= 0) return null  // 配置为0=不限

        val b = bucket(apiKey)
        // QPS 检查（60秒滑窗）
        if (qpsLimit > 0) {
            val qps = qpsInWindow(b)
            if (qps >= qpsLimit) return "请求过于频繁（每秒限 $qpsLimit 次），请稍后再试"
        }
        // 每日检查
        if (dailyLimit > 0 && b.daily.get() >= dailyLimit) {
            return "今日调用已达上限（$dailyLimit 次），请明天再试或联系管理员提升配额"
        }
        // 通过 → 记录
        record(b)
        return null
    }

    /** 重置某 Key 限流（删除时用） */
    fun reset(apiKey: String) {
        buckets.remove(apiKey)
    }
}