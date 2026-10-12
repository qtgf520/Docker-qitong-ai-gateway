package com.qitong.gateway.monitor

import java.io.File
import java.lang.management.ManagementFactory
import kotlin.math.roundToInt

/**
 * ★ v1.124 系统监控 —— 纯 JVM 读取 /proc + JMX，零外部依赖
 * 提供：CPU / 内存 / 磁盘 / 网络 / 负载 / JVM 线程，供首页监控与告警使用
 */
object SystemMonitor {

    data class SysStats(
        val cpuPercent: Double,        // CPU 使用率 %
        val memUsedMB: Long,
        val memTotalMB: Long,
        val diskUsedMB: Long,
        val diskTotalMB: Long,
        val netInKB: Long,             // 每秒入流量 KB
        val netOutKB: Long,            // 每秒出流量 KB
        val load1: Double,
        val load5: Double,
        val load15: Double,
        val threads: Int,
        val uptimeSec: Long
    )

    @Volatile private var lastNetRx: Long = 0
    @Volatile private var lastNetTx: Long = 0
    @Volatile private var lastNetTime: Long = System.currentTimeMillis()
    @Volatile private var lastCpuIdle: Long = 0
    @Volatile private var lastCpuTotal: Long = 0

    private fun readCpu(): Pair<Long, Long> {  // (idle, total)
        return try {
            val line = File("/proc/stat").readLines().firstOrNull { it.startsWith("cpu ") } ?: return 0L to 0L
            val parts = line.split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
            if (parts.size < 4) return 0L to 0L
            val idle = parts.getOrElse(3) { 0 } + parts.getOrElse(4) { 0 }
            val total = parts.sum()
            idle to total
        } catch (_: Exception) { 0L to 0L }
    }

    private fun readNet(): Pair<Long, Long> {  // (rx, tx) bytes
        return try {
            var rx = 0L; var tx = 0L
            File("/proc/net/dev").readLines().forEach { line ->
                if (!line.contains(":")) return@forEach
                val parts = line.split(":")
                if (parts.size < 2) return@forEach
                val nums = parts[1].trim().split(Regex("\\s+"))
                if (nums.isNotEmpty()) rx += nums[0].toLongOrNull() ?: 0
                if (nums.size > 8) tx += nums[8].toLongOrNull() ?: 0
            }
            rx to tx
        } catch (_: Exception) { 0L to 0L }
    }

    /** 采集一次系统状态 */
    fun collect(): SysStats {
        val rt = Runtime.getRuntime()
        val totalMem = rt.totalMemory()
        val usedMem = (totalMem - rt.freeMemory()) / 1024 / 1024
        val maxMem = rt.maxMemory() / 1024 / 1024

        // CPU
        val (idle, total) = readCpu()
        val cpu = if (lastCpuTotal > 0 && total > lastCpuTotal) {
            val idleDelta = idle - lastCpuIdle
            val totalDelta = total - lastCpuTotal
            if (totalDelta > 0) ((1.0 - idleDelta.toDouble() / totalDelta) * 100).roundToInt().coerceIn(0, 100).toDouble()
            else 0.0
        } else 0.0
        lastCpuIdle = idle; lastCpuTotal = total

        // 磁盘（根分区）
        var diskUsed = 0L; var diskTotal = 0L
        try {
            val fs = java.nio.file.Files.getFileStore(java.nio.file.Paths.get("/"))
            diskUsed = (fs.totalSpace - fs.usableSpace) / 1024 / 1024
            diskTotal = fs.totalSpace / 1024 / 1024
        } catch (_: Exception) {}

        // 网络速率
        val (rx, tx) = readNet()
        val now = System.currentTimeMillis()
        val dtSec = (now - lastNetTime).coerceAtLeast(1) / 1000.0
        val netInKB = if (lastNetRx > 0 && rx >= lastNetRx) ((rx - lastNetRx) / 1024 / dtSec).toLong() else 0L
        val netOutKB = if (lastNetTx > 0 && tx >= lastNetTx) ((tx - lastNetTx) / 1024 / dtSec).toLong() else 0L
        lastNetRx = rx; lastNetTx = tx; lastNetTime = now

        // 负载
        var load1 = 0.0; var load5 = 0.0; var load15 = 0.0
        try {
            val l = File("/proc/loadavg").readText().trim().split(Regex("\\s+"))
            if (l.size >= 3) { load1 = l[0].toDouble(); load5 = l[1].toDouble(); load15 = l[2].toDouble() }
        } catch (_: Exception) {}

        return SysStats(
            cpuPercent = cpu,
            memUsedMB = usedMem,
            memTotalMB = maxMem,
            diskUsedMB = diskUsed,
            diskTotalMB = diskTotal,
            netInKB = netInKB,
            netOutKB = netOutKB,
            load1 = load1, load5 = load5, load15 = load15,
            threads = ManagementFactory.getThreadMXBean().threadCount,
            uptimeSec = (System.currentTimeMillis() - com.qitong.gateway.http.GatewayProxy.startTime) / 1000
        )
    }

    /** 一次性快照：含 JVM 内存细化 */
    fun snapshot(): Map<String, Any?> {
        val s = collect()
        val heap = ManagementFactory.getMemoryMXBean().heapMemoryUsage
        return mapOf(
            "cpu" to s.cpuPercent,
            "memUsedMB" to s.memUsedMB,
            "memTotalMB" to s.memTotalMB,
            "heapUsedMB" to heap.used / 1024 / 1024,
            "diskUsedMB" to s.diskUsedMB,
            "diskTotalMB" to s.diskTotalMB,
            "netInKB" to s.netInKB,
            "netOutKB" to s.netOutKB,
            "load1" to s.load1,
            "load5" to s.load5,
            "load15" to s.load15,
            "threads" to s.threads,
            "uptimeSec" to s.uptimeSec
        )
    }
}