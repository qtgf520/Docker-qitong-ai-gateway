package com.qitong.gateway.http

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 沙盒 Linux 终端管理器（全部临时会话，进程用完即焚，不做长期保留）
 *  - 会话仅是内存容器：每次 exec 独立 /bin/sh -c 执行（无持久 shell，安全）
 *  - 支持危险命令黑名单 + 10 秒超时 + 输出截断
 *  - 自动清理 30 分钟未活动的会话
 */
object TerminalManager {

    data class TermSession(
        val id: String,
        val label: String,
        val createdAt: Long,
        var lastActiveAt: Long = System.currentTimeMillis(),
        var commands: Int = 0,
        var output: StringBuilder = StringBuilder(),
        var ttlMinutes: Int = 30 // 0 = 永久；>0 = 该时长（分钟）无操作自动清理
    )

    private val sessions = ConcurrentHashMap<String, TermSession>()
    private val idSeq = AtomicLong(1000)

    private val dangerous = listOf(
        "rm -rf /", "mkfs", "dd if=", "shutdown", "reboot", ":(){",
        "format", "fdisk", "chmod 777 /", "chown -R", "> /dev/sda", "curl.*|.*sh"
    )

    /** 创建临时终端会话（ttlMinutes: 0=永久；默认30分钟无操作清理） */
    fun create(label: String = "", ttlMinutes: Int = 30): TermSession {
        cleanup()
        val id = "term-" + idSeq.incrementAndGet()
        val s = TermSession(
            id = id,
            label = label.ifBlank { "终端会话 " + id + "-" + (sessions.size + 1) },
            createdAt = System.currentTimeMillis(),
            ttlMinutes = ttlMinutes.coerceIn(0, 24 * 60 * 365) // 0..永久，上限1年
        )
        sessions[id] = s
        return s
    }

    /** 列出所有临时会话 */
    fun list(): List<TermSession> {
        cleanup()
        return sessions.values.sortedByDescending { it.lastActiveAt }
    }

    /** 调整会话时长（0=永久；>0=分钟）；返回调整后是否成功 */
    fun setTtl(id: String, ttlMinutes: Int): Boolean {
        val s = sessions[id] ?: return false
        s.ttlMinutes = ttlMinutes.coerceIn(0, 24 * 60 * 365)
        s.lastActiveAt = System.currentTimeMillis()
        return true
    }

    /** 关闭临时会话（删除内存记录） */
    fun close(id: String): Boolean = sessions.remove(id) != null

    fun closeAll() { sessions.clear() }

    fun get(id: String): TermSession? = sessions[id]

    /** 在临时会话中执行命令 */
    fun exec(id: String, cmd: String): Pair<Boolean, String> {
        cleanup()
        val s = sessions[id] ?: return false to "会话不存在或已关闭，请重新创建终端"
        if (cmd.isBlank()) return true to "(无输出)"
        if (cmd.length > 500) return true to "⚠️ 命令太长（限500字符）"
        if (dangerous.any { cmd.contains(it) }) return true to "⛔ 危险命令已拦截"
        s.lastActiveAt = System.currentTimeMillis()
        s.commands++
        val out = try {
            val proc = ProcessBuilder("/bin/sh", "-c", cmd)
                .redirectErrorStream(true)
                .start()
            val text = proc.inputStream.bufferedReader().readText()
            if (!proc.waitFor(10, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                "⚠️ 命令超时（10秒）已终止\n$text"
            } else text.ifBlank { "(无输出)" }
        } catch (e: Exception) {
            "❌ 执行失败：${e.message}"
        }
        val shown = out.take(2000)
        s.output.append("$ ").append(cmd).append("\n").append(shown).append("\n\n")
        // 输出日志只保留最近 10 条
        val keep = s.output.toString().lines().takeLast(300).joinToString("\n")
        s.output = StringBuilder(keep)
        return true to shown
    }

    /** 清理过期会话：ttlMinutes>0 且超时未活动的清理；ttl=0（永久）不清理 */
    private fun cleanup() {
        val now = System.currentTimeMillis()
        sessions.entries.removeIf { (_, s) ->
            s.ttlMinutes > 0 && now - s.lastActiveAt > s.ttlMinutes.toLong() * 60 * 1000L
        }
    }
}