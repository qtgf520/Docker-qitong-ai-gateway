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
        var ttlMinutes: Int = 30, // 0 = 永久；>0 = 该时长（分钟）无操作自动清理
        var ownerUserId: Long = 0 // ★ v79 沙盒按用户隔离：0=全局/管理员；>0=该用户私有
    )

    private val sessions = ConcurrentHashMap<String, TermSession>()
    private val idSeq = AtomicLong(1000)

    private val dangerous = listOf(
        "rm -rf /", "rm -rf /*", "rm -fr /", "mkfs", "dd if=", "shutdown", "reboot", ":(){",
        "format", "fdisk", "chmod 777 /", "chown -R", "> /dev/sda", "curl.*|.*sh",
        // ★ v68 加固：docker 全家桶（删容器/镜像/卷/网络/服务） + 高危系统操作
        "docker rm", "docker rmi", "docker volume", "docker network", "docker compose down", "docker-compose down",
        "docker stop", "docker kill", "docker system prune", "docker builder prune", "docker image prune",
        "docker container prune", "docker restart", "docker update --restart=no",
        "systemctl stop", "systemctl disable", "service docker stop", "kill -9 1",
        "umount", "mount -o", "mv /", "cp -r / /", "tar -czf /", "tar czf /", "wget.*|.*sh",
        "echo.*> /etc", "crontab -r", "userdel", "groupdel", "passwd -d", "visudo",
        "find / -delete", "find / -exec rm", "dd if=/dev/zero", "mkfs.ext", "mkfs.xfs",
        ":(){ :|:& };:"
    )

    /** 创建临时终端会话（ttlMinutes: 0=永久；默认30分钟无操作清理；ownerUserId: 沙盒用户隔离） */
    fun create(label: String = "", ttlMinutes: Int = 30, ownerUserId: Long = 0): TermSession {
        cleanup()
        val id = "term-" + idSeq.incrementAndGet()
        val s = TermSession(
            id = id,
            label = label.ifBlank { "终端会话 " + id + "-" + (sessions.size + 1) },
            createdAt = System.currentTimeMillis(),
            ttlMinutes = ttlMinutes.coerceIn(0, 24 * 60 * 365), // 0..永久，上限1年
            ownerUserId = ownerUserId
        )
        sessions[id] = s
        return s
    }

    /** ★ v79 按用户列出会话（管理员看全部；普通用户只看自己的） */
    fun list(userId: Long = 0): List<TermSession> {
        cleanup()
        val all = sessions.values.sortedByDescending { it.lastActiveAt }
        return if (userId == 0L) all else all.filter { it.ownerUserId == 0L || it.ownerUserId == userId }
    }

    /** ★ v79 校验会话归属：管理员可操作全部，普通用户只能操作自己的（owner=0 的全局会话普通用户也可用） */
    fun canAccess(id: String, userId: Long): Boolean {
        if (userId == 0L) return true  // 管理员/系统
        val s = sessions[id] ?: return false
        return s.ownerUserId == 0L || s.ownerUserId == userId
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

    // ★ v65 异步任务：长命令后台跑，大模型可轮询 terminal_status 拿结果（OpenClaw 式等待）
    data class AsyncTask(
        val id: String,
        val cmd: String,
        val startedAt: Long,
        var done: Boolean = false,
        var output: String = "",
        var ok: Boolean = true
    )
    private val asyncTasks = ConcurrentHashMap<String, AsyncTask>()
    private val asyncSeq = AtomicLong(2000)

    /** 启动异步任务（后台线程跑，不阻塞；适合长命令/大输出） */
    fun startAsync(cmd: String): String {
        if (cmd.isBlank()) return "任务命令为空"
        if (cmd.length > 1000) return "⚠️ 命令太长（限1000字符）"
        if (dangerous.any { cmd.contains(it) }) return "⛔ 危险命令已拦截"
        val id = "task-" + asyncSeq.incrementAndGet()
        val t = AsyncTask(id, cmd, System.currentTimeMillis())
        asyncTasks[id] = t
        Thread {
            try {
                val proc = ProcessBuilder("/bin/sh", "-c", cmd).redirectErrorStream(true).start()
                // 读输出（限 200KB 防内存爆）
                val sb = StringBuilder()
                val reader = proc.inputStream.bufferedReader()
                val buf = CharArray(4096)
                var total = 0
                while (true) {
                    val n = reader.read(buf)
                    if (n <= 0) break
                    total += n
                    if (total > 200_000) { proc.destroyForcibly(); sb.append("\n…输出超200KB已截断…"); break }
                    sb.append(buf, 0, n)
                }
                val waitOk = proc.waitFor(5, TimeUnit.MINUTES)
                if (!waitOk) { proc.destroyForcibly(); t.output = sb.toString().take(100_000) + "\n⚠️ 任务超时（5分钟）已终止"; t.ok = false }
                else { t.output = sb.toString().take(100_000).ifBlank { "(无输出)" }; t.ok = proc.exitValue() == 0 }
            } catch (e: Exception) {
                t.output = "❌ 执行失败：${e.message}"
                t.ok = false
            } finally {
                t.done = true
            }
        }.start()
        return "✅ 异步任务已启动\n🆔 ID: $id\n📋 命令: $cmd\n\n稍后发 terminal_status(id=$id) 查询执行结果"
    }

    /** 查询异步任务状态 */
    fun asyncStatus(id: String): String {
        val t = asyncTasks[id] ?: return "❌ 任务不存在或已过期（task-xxx）"
        return if (!t.done) {
            "⏳ 任务运行中…（已执行 ${(System.currentTimeMillis() - t.startedAt) / 1000} 秒）\n📋 命令: ${t.cmd}\n\n请等待几秒后再查"
        } else {
            "✅ 任务完成\n📋 命令: ${t.cmd}\n⏱ 耗时: ${(System.currentTimeMillis() - t.startedAt) / 1000} 秒\n📤 输出:\n${t.output}"
        }
    }
    /** 清理过期异步任务（完成超过10分钟移除） */
    fun cleanupAsync() {
        val now = System.currentTimeMillis()
        asyncTasks.entries.removeIf { (_, t) -> t.done && now - t.startedAt > 10 * 60 * 1000L }
    }

    /** 一次性执行命令（不建会话，用于工作流/技能；危险拦截+超时） */
    fun runOnce(cmd: String): String {
        if (cmd.isBlank()) return "(无输出)"
        if (cmd.length > 500) return "⚠️ 命令太长"
        if (dangerous.any { cmd.contains(it) }) return "⛔ 危险命令已拦截"
        return try {
            val proc = ProcessBuilder("/bin/sh", "-c", cmd).redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().readText()
            if (!proc.waitFor(10, TimeUnit.SECONDS)) { proc.destroyForcibly(); "⚠️ 命令超时（10秒）已终止\n$out" }
            else out.ifBlank { "(无输出)" }.take(2000)
        } catch (e: Exception) { "❌ 执行失败：${e.message}" }
    }

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

    // ============ ★ v1.102 文件管理（对齐 Agora 文件工具：读/写/列目录/搜索） ============

    /** 列出目录内容（真实服务器目录，即容器挂载卷），返回 名称/类型/大小/修改时间 */
    fun listDir(path: String): Pair<Boolean, String> {
        return try {
            val base = java.io.File(path)
            if (!base.exists() || !base.isDirectory) return false to "目录不存在：$path"
            val sb = StringBuilder()
            val items = base.listFiles()?.sortedWith(compareBy({ it.isFile }, { it.name.lowercase() })) ?: emptyList()
            if (items.isEmpty()) return true to "（空目录）$path"
            items.forEach { f ->
                val name = f.name
                val type = if (f.isDirectory) "📁" else "📄"
                val size = if (f.isFile) {
                    when {
                        f.length() >= 1024 * 1024 * 1024L -> "%.1fG".format(f.length() / 1024.0 / 1024 / 1024)
                        f.length() >= 1024 * 1024L -> "%.1fM".format(f.length() / 1024.0 / 1024)
                        f.length() >= 1024 -> "%.1fK".format(f.length() / 1024.0)
                        else -> "${f.length()}B"
                    }
                } else "-"
                val mod = java.text.SimpleDateFormat("MM-dd HH:mm").format(java.util.Date(f.lastModified()))
                sb.append("$type $name\t$size\t$mod\n")
            }
            true to sb.toString().ifBlank { "（空目录）$path" }
        } catch (e: Exception) {
            false to "列出目录失败：${e.message}"
        }
    }

    /** 读取文件内容（限 200KB 防内存爆） */
    fun readFile(path: String): Pair<Boolean, String> {
        return try {
            val f = java.io.File(path)
            if (!f.exists()) return false to "文件不存在：$path"
            if (f.isDirectory) return false to "这是目录，用文件管理器浏览：$path"
            if (f.length() > 200 * 1024) return false to "文件过大（${f.length() / 1024}KB），仅支持预览 200KB 以内文本"
            val text = f.readText(Charsets.UTF_8).take(100_000)
            true to text.ifBlank { "（空文件）" }
        } catch (e: Exception) {
            false to "读取失败：${e.message}"
        }
    }

    /** 写文件（自动创建父目录） */
    fun writeFile(path: String, content: String): Pair<Boolean, String> {
        return try {
            val f = java.io.File(path)
            f.parentFile?.mkdirs()
            f.writeText(content, Charsets.UTF_8)
            true to "✅ 已写入 ${f.absolutePath}（${content.length} 字符）"
        } catch (e: Exception) {
            false to "写入失败：${e.message}"
        }
    }

    /** 搜索文件名（对齐 Agora fileGlob） */
    fun globFiles(basePath: String, pattern: String, depth: Int = 3): Pair<Boolean, String> {
        return try {
            val base = java.io.File(basePath)
            if (!base.exists() || !base.isDirectory) return false to "目录不存在：$basePath"
            val kw = pattern.trim().lowercase()
            val results = mutableListOf<String>()
            fun walk(dir: java.io.File, d: Int) {
                if (d > depth || results.size >= 100) return
                dir.listFiles()?.forEach { f ->
                    if (f.name.lowercase().contains(kw)) results.add(f.absolutePath)
                    if (f.isDirectory) walk(f, d + 1)
                }
            }
            walk(base, 0)
            if (results.isEmpty()) true to "未找到含「$pattern」的文件（深度${depth}）"
            else true to "🔍 找到 ${results.size} 个（深度${depth}）：\n" + results.joinToString("\n")
        } catch (e: Exception) {
            false to "搜索失败：${e.message}"
        }
    }

    // ============ ★ v1.102 SSH 远程连接（对齐 Agora Shell 多后端：本地沙盒 + 远程SSH） ============

    data class SshConfig(
        val name: String,
        val host: String,
        val port: Int = 22,
        val username: String,
        val password: String = "",
        val privateKey: String = ""
    )

    private val sshConfigs = ConcurrentHashMap<String, SshConfig>()

    /** 保存 SSH 配置（name 唯一，重复覆盖） */
    fun saveSshConfig(cfg: SshConfig): Boolean {
        if (cfg.name.isBlank() || cfg.host.isBlank() || cfg.username.isBlank()) return false
        sshConfigs[cfg.name] = cfg
        return true
    }

    fun listSshConfigs(): List<Map<String, Any?>> = sshConfigs.values.map {
        mapOf("name" to it.name, "host" to it.host, "port" to it.port, "username" to it.username, "hasPassword" to it.password.isNotBlank())
    }

    fun deleteSshConfig(name: String): Boolean = sshConfigs.remove(name) != null

    /** 通过 SSH 执行远程命令（复用 sshpass；工作目录可指定，0=默认家目录） */
    fun sshExec(cfgName: String, cmd: String, workdir: String = ""): Pair<Boolean, String> {
        val cfg = sshConfigs[cfgName] ?: return false to "SSH 配置不存在：$cfgName"
        if (cmd.isBlank()) return true to "(无输出)"
        if (dangerous.any { cmd.contains(it) }) return true to "⛔ 危险命令已拦截"
        return try {
            val fullCmd = if (workdir.isNotBlank()) "cd $workdir && $cmd" else cmd
            val pb = ProcessBuilder(
                "sshpass", "-p", cfg.password,
                "ssh", "-o", "StrictHostKeyChecking=no", "-o", "ConnectTimeout=10",
                "-p", cfg.port.toString(),
                "${cfg.username}@${cfg.host}", fullCmd
            ).redirectErrorStream(true)
            val proc = pb.start()
            val out = proc.inputStream.bufferedReader().readText()
            if (!proc.waitFor(15, TimeUnit.SECONDS)) { proc.destroyForcibly(); false to "⚠️ SSH 命令超时（15秒）已终止\n$out" }
            else true to out.ifBlank { "(无输出)" }.take(3000)
        } catch (e: Exception) {
            false to "SSH 执行失败：${e.message}"
        }
    }

    /** SSH 测连接 */
    fun sshTest(cfgName: String): Pair<Boolean, String> {
        val cfg = sshConfigs[cfgName] ?: return false to "SSH 配置不存在：$cfgName"
        return try {
            val proc = ProcessBuilder(
                "sshpass", "-p", cfg.password,
                "ssh", "-o", "StrictHostKeyChecking=no", "-o", "ConnectTimeout=8",
                "-p", cfg.port.toString(),
                "${cfg.username}@${cfg.host}", "echo __SSH_OK__ && pwd && whoami"
            ).redirectErrorStream(true).start()
            val out: String = proc.inputStream.bufferedReader().readText()
            val waitedOk: Boolean = proc.waitFor(15, TimeUnit.SECONDS)
            val okFlag: Boolean = waitedOk && out.contains("__SSH_OK__")
            if (proc.isAlive) proc.destroyForcibly()
            if (okFlag) true to "✅ SSH 连接成功（${cfg.host}:${cfg.port}）\n$out"
            else false to "❌ SSH 连接失败：$out"
        } catch (e: Exception) {
            false to "SSH 连接异常：${e.message}"
        }
    }
}