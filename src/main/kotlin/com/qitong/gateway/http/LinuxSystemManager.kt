package com.qitong.gateway.http

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * ★ v1.174 Linux 终端系统管理器
 *  - 网关容器内提供「真实 Linux 环境」：通过 proot（用户态 chroot）跑下载的 rootfs（Ubuntu/Debian/Alpine）
 *  - 系统镜像清单：内置可下载安装的 Linux 发行版（rootfs tar 包）
 *  - qtai-sj 专属永久终端：默认创建、不可删除、Agent 可操作（提供 execLinux 给沙盒调用）
 *  - 与 TerminalManager 的无持久 shell 不同：Linux 会话进入 proot 环境，命令在 rootfs 内执行，拥有真实命令/权限
 */
object LinuxSystemManager {

    // ============ 系统镜像注册表（可下载安装适配的 Linux） ============

    data class LinuxDistro(
        val id: String,            // 镜像ID：alpine / debian / ubuntu
        val name: String,          // 显示名
        val arch: String,          // aarch64 / x86_64
        val url: String,           // rootfs tar 下载地址
        val sizeHint: String,      // 大小提示
        val rootfsDir: String      // 解压目录（相对 LIMX_ROOT）
    )

    // 网关容器架构探测（proot 需匹配 rootfs 架构）
    val ARCH: String = runCatching {
        System.getProperty("os.arch") ?: ""
    }.getOrDefault("").let {
        when {
            it.contains("aarch64") || it.contains("arm64") -> "aarch64"
            it.contains("x86_64") || it.contains("amd64") -> "x86_64"
            else -> "other"
        }
    }

    /** rootfs 存放根目录（DATA_DIR 挂载卷，重启不丢；默认 /data/qitong/linux） */
    val LIMX_ROOT: String = (System.getenv("DATA_DIR") ?: "/data/qitong") + "/linux"

    /** 注册「可安装适配系统」清单（由管理员在界面点下载/安装） */
    fun distros(): List<LinuxDistro> = when (ARCH) {
        "aarch64" -> listOf(
            // 阿里云镜像 aarch64 rootfs（小体积，适合容器）
            LinuxDistro("alpine", "Alpine Linux (mini)", "aarch64",
                "https://dl-cdn.alpinelinux.org/alpine/v3.20/releases/aarch64/alpine-minirootfs-3.20.3-aarch64.tar.gz",
                "≈3MB，极简，适合轻量操作", "$LIMX_ROOT/alpine"),
            LinuxDistro("debian", "Debian (stable)", "aarch64",
                "https://cloud.debian.org/images/cloud/bookworm/latest/debian-12-generic-arm64.tar.xz",
                "≈400MB，完整，适合作业", "$LIMX_ROOT/debian"),
        )
        else -> listOf(
            LinuxDistro("alpine", "Alpine Linux (mini)", "x86_64",
                "https://dl-cdn.alpinelinux.org/alpine/v3.20/releases/x86_64/alpine-minirootfs-3.20.3-x86_64.tar.gz",
                "≈3MB，极简", "$LIMX_ROOT/alpine"),
            LinuxDistro("debian", "Debian (stable)", "x86_64",
                "https://cloud.debian.org/images/cloud/bookworm/latest/debian-12-generic-amd64.tar.xz",
                "≈400MB，完整", "$LIMX_ROOT/debian"),
        )
    }

    // ============ 安装状态 ============

    data class InstallState(
        val distroId: String,
        val status: String,          // downloading / installed / failed
        val progress: Int = 0,       // 0-100
        val message: String = "",
        val startedAt: Long = System.currentTimeMillis()
    )

    private val installStates = ConcurrentHashMap<String, InstallState>()
    private val installLock = Object()

    /** 查询安装状态（前端轮询进度条） */
    fun installState(distroId: String): InstallState? = installStates[distroId]

    /** 所有发行版安装状态（前端面板用） */
    fun allInstallStates(): List<Map<String, Any?>> = distros().map { d ->
        val st = installStates[d.id]
        mapOf(
            "id" to d.id, "name" to d.name, "arch" to d.arch,
            "sizeHint" to d.sizeHint, "rootfsDir" to d.rootfsDir,
            "installed" to isInstalled(d.id),
            "status" to (st?.status ?: "none"),
            "progress" to (st?.progress ?: 0),
            "message" to (st?.message ?: "")
        )
    }

    /** 是否已安装（rootfs 目录存在且非空） */
    fun isInstalled(distroId: String): Boolean {
        val d = distros().firstOrNull { it.id == distroId } ?: return false
        val dir = File(d.rootfsDir)
        return dir.exists() && (dir.listFiles()?.isNotEmpty() == true)
    }

    /** ★ 安装系统：下载 rootfs（后台线程，进度实时更新）→ 解压到目录 */
    fun install(distroId: String): Pair<Boolean, String> {
        val d = distros().firstOrNull { it.id == distroId } ?: return false to "未知系统镜像：$distroId"
        if (isInstalled(distroId)) return false to "系统已安装（${d.name}），无需重复安装"
        synchronized(installLock) {
            val cur = installStates[distroId]
            if (cur != null && (cur.status == "downloading" || cur.status == "installing")) {
                return false to "系统正在安装中（${cur.status}），请稍候…"
            }
            installStates[distroId] = InstallState(distroId, "downloading", 0, "准备下载 ${d.name}…")
        }
        Thread {
            try {
                val dir = File(d.rootfsDir)
                dir.mkdirs()
                val tmp = File(LIMX_ROOT, "download_${d.id}.tar")
                tmp.parentFile?.mkdirs()
                if (tmp.exists()) tmp.delete()
                // 下载（curl -L --progress 或 Java 流式，这里用 Java HttpURLConnection 精确进度）
                val conn = java.net.URL(d.url).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 20_000
                conn.readTimeout = 120_000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "QiTongGateway/1.221")
                conn.connect()
                if (conn.responseCode !in 200..399) {
                    installStates[distroId] = InstallState(distroId, "failed", 0, "下载失败 HTTP ${conn.responseCode}")
                    return@Thread
                }
                val total = conn.contentLengthLong
                val input = conn.inputStream
                val out = tmp.outputStream()
                val buf = ByteArray(64 * 1024)
                var downloaded = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    downloaded += n
                    val pct = if (total > 0) ((downloaded * 100) / total).toInt().coerceIn(0, 99) else 0
                    installStates[distroId] = InstallState(distroId, "downloading", pct, "下载中… ${(downloaded / 1024 / 1024)}MB" + if (total > 0) "/${total / 1024 / 1024}MB" else "")
                }
                out.close(); input.close()
                installStates[distroId] = InstallState(distroId, "installing", 99, "解压安装中…（首次较慢）")
                // 解压（tar 支持 .tar.gz/.tar.xz，容器内 busybox tar 自动识别；无 tar 则用系统 tar）
                val tarOk = extractTar(tmp, dir)
                tmp.delete()
                if (!tarOk) {
                    installStates[distroId] = InstallState(distroId, "failed", 0, "解压失败（可能 tar 不支持 .xz，请装 tar 或用 gzip）")
                    return@Thread
                }
                installStates[distroId] = InstallState(distroId, "installed", 100, "✅ ${d.name} 安装完成")
            } catch (e: Exception) {
                installStates[distroId] = InstallState(distroId, "failed", 0, "安装失败：${e.message}")
            }
        }.start()
        return true to "开始安装 ${d.name}（后台下载，进度面板可实时查看）"
    }

    /** 解压 tar 包（智能识别 .tar.gz / .tar.xz / .tar） */
    private fun extractTar(tar: File, destDir: File): Boolean {
        return try {
            destDir.mkdirs()
            // 用 bash 的 tar（容器 base 一般有），压缩格式自动识别
            val proc = ProcessBuilder("bash", "-c", "tar -xf \"${tar.absolutePath}\" -C \"${destDir.absolutePath}\"").redirectErrorStream(true).start()
            val err = proc.inputStream.bufferedReader().readText()
            val ok = proc.waitFor(300, java.util.concurrent.TimeUnit.SECONDS)
            if (!ok) { proc.destroyForcibly(); false } else proc.exitValue() == 0 || err.isBlank()
        } catch (e: Exception) { false }
    }

    /** 卸载系统（删除 rootfs 目录） */
    fun remove(distroId: String): Pair<Boolean, String> {
        val d = distros().firstOrNull { it.id == distroId } ?: return false to "未知系统镜像"
        val dir = File(d.rootfsDir)
        if (!dir.exists()) return false to "系统未安装"
        dir.deleteRecursively()
        installStates.remove(distroId)
        return true to "已卸载 ${d.name}"
    }

    // ============ Linux 会话（proot 进入 rootfs 执行，真持久 shell 环境） ============

    data class LinuxSession(
        val id: String,
        val distroId: String,
        val label: String,
        val createdAt: Long,
        var lastActiveAt: Long = System.currentTimeMillis(),
        var commands: Int = 0,
        var permanent: Boolean = false,        // true=永久（qtai-sj 专属等，不自动清理、不可删除）
        var ownerUserId: Long = 0,             // 0=系统/管理员；>0=用户私有
        var output: StringBuilder = StringBuilder()   // 命令滚动输出
    )

    private val linuxSessions = ConcurrentHashMap<String, LinuxSession>()
    private val linuxSeq = AtomicLong(3000)

    /** 默认 qtai-sj 永久终端（启动即建，不可删除） */
    const val QTAI_TERMINAL_ID = "linux-qtai"
    const val QTAI_TERMINAL_LABEL = "qtai-sj · 专属 Linux 终端"

    /** 确保 qtai-sj 永久终端存在（Main 启动调用） */
    fun ensureQtaiTerminal() {
        if (!linuxSessions.containsKey(QTAI_TERMINAL_ID)) {
            linuxSessions[QTAI_TERMINAL_ID] = LinuxSession(
                id = QTAI_TERMINAL_ID,
                distroId = "alpine",   // 默认关联 Alpine（轻量）；装了 debian 后 agent 可自行切换
                label = QTAI_TERMINAL_LABEL,
                createdAt = System.currentTimeMillis(),
                permanent = true,
                ownerUserId = 0
            )
        }
    }

    /** 创建 Linux 会话（默认 30 分钟无操作清理；ttlMinutes=0 永久；qtai 专属 permanent=true 不可删除） */
    fun create(distroId: String, label: String = "", ownerUserId: Long = 0): LinuxSession {
        val d = distros().firstOrNull { it.id == distroId } ?: distros().first()
        val id = "linux-" + linuxSeq.incrementAndGet()
        val s = LinuxSession(
            id = id,
            distroId = d.id,
            label = label.ifBlank { "Linux·" + d.name + "-" + id },
            createdAt = System.currentTimeMillis(),
            ownerUserId = ownerUserId
        )
        linuxSessions[id] = s
        return s
    }

    /** 列出会话（按用户隔离；管理员看全部） */
    fun listSessions(userId: Long = 0): List<LinuxSession> {
        cleanupLinux()
        val all = linuxSessions.values.sortedByDescending { it.lastActiveAt }
        return if (userId == 0L) all else all.filter { it.ownerUserId == 0L || it.ownerUserId == userId }
    }

    /** 会话访问权限：管理员/系统=全部；普通用户=自己的或全局 */
    fun canAccess(id: String, userId: Long): Boolean {
        if (userId == 0L) return true
        val s = linuxSessions[id] ?: return false
        return s.ownerUserId == 0L || s.ownerUserId == userId
    }

    /** 设置永久/非永久 */
    fun setPermanent(id: String, permanent: Boolean): Boolean {
        val s = linuxSessions[id] ?: return false
        // qtai 专属终端强制永久，不可取消
        if (id == QTAI_TERMINAL_ID && !permanent) return false
        s.permanent = permanent
        s.lastActiveAt = System.currentTimeMillis()
        return true
    }

    /** 删除会话（qtai 永久终端不可删除） */
    fun removeSession(id: String): Boolean {
        if (id == QTAI_TERMINAL_ID) return false
        return linuxSessions.remove(id) != null
    }

    fun getSession(id: String): LinuxSession? = linuxSessions[id]

    // 危险命令黑名单（沿用 TerminalManager 策略，proot 内同样拦截）
    private val dangerous = listOf(
        "rm -rf /", "rm -rf /*", "mkfs", "dd if=", "shutdown", "reboot", ":(){",
        "format", "fdisk", "> /dev/sda", "curl.*|.*sh", "wget.*|.*sh",
        "chmod 777 /", "chown -R", "kill -9 1", "umount /", ":(){ :|:& };:"
    )

    /**
     * 在 Linux 会话中执行命令（proot 进入该 rootfs，使用 /bin/sh -c）
     * ★ 关键：命令在下载的 rootfs 内执行（真实 Linux 命令集，如 apk/apt/pip 等）
     */
    fun execLinux(id: String, cmd: String): Pair<Boolean, String> {
        cleanupLinux()
        val s = linuxSessions[id] ?: return false to "Linux 会话不存在或已关闭"
        if (cmd.isBlank()) return true to "(无输出)"
        if (cmd.length > 1000) return true to "⚠️ 命令太长（限1000字符）"
        if (dangerous.any { cmd.contains(it) }) return true to "⛔ 危险命令已拦截"
        val d = distros().firstOrNull { it.id == s.distroId } ?: return false to "镜像不存在"
        val rootfs = File(d.rootfsDir)
        if (!rootfs.exists() || rootfs.listFiles()?.isEmpty() != false) {
            return false to "⚠️ ${d.name} 尚未安装，请先在「Linux 系统」面板点「下载安装」"
        }
        // proot 二进制：容器内未装则提示；未来可自动下载 proot 静态二进制
        val prootBin = File("/usr/local/bin/proot")
        val prootCmd = if (prootBin.exists())
            listOf(prootBin.absolutePath, "-R", rootfs.absolutePath, "/bin/sh", "-c", cmd)
        else
            listOf("proot", "-R", rootfs.absolutePath, "/bin/sh", "-c", cmd)  // 系统已装 proot 时

        s.lastActiveAt = System.currentTimeMillis()
        s.commands++
        return try {
            val proc = ProcessBuilder(prootCmd).redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().readText()
            // ★ v1.175 卡顿修复：同步等 25s 上限（沙盒 withTimeout 35s 内安全），超时明确提示不卡死
            val waited = proc.waitFor(25, java.util.concurrent.TimeUnit.SECONDS)
            if (!waited) { proc.destroyForcibly(); "⚠️ Linux 命令执行超时（25秒），进程已终止。可能是耗时任务，建议拆成小步骤执行（如先装依赖再跑）\n$out".take(2000) }
            else out.ifBlank { "(无输出)" }
        } catch (e: Exception) {
            // proot 未找到
            if (e.message?.contains("No such file") == true || e.message?.contains("Cannot run program") == true) {
                "❌ 未找到 proot。请先在服务器安装 proot（apt-get install proot 或放静态二进制到 /usr/local/bin/proot）"
            } else "❌ 执行失败：${e.message}"
        }.let { shown ->
            s.output.append("$ ").append(cmd).append("\n").append(shown.take(1500)).append("\n\n")
            val keep = s.output.toString().lines().takeLast(400).joinToString("\n")
            s.output = StringBuilder(keep)
            true to shown
        }
    }

    /** 清理非永久且超时未活动的会话（30分钟默认；permanent 永不清理） */
    private fun cleanupLinux() {
        val now = System.currentTimeMillis()
        linuxSessions.entries.removeIf { (_, s) ->
            !s.permanent && now - s.lastActiveAt > 30 * 60 * 1000L
        }
    }

    /** 会话列表（对外 JSON） */
    fun linuxSessionsJson(userId: Long = 0): List<Map<String, Any?>> = listSessions(userId).map { s ->
        mapOf(
            "id" to s.id, "distroId" to s.distroId, "label" to s.label,
            "createdAt" to s.createdAt, "lastActiveAt" to s.lastActiveAt,
            "commands" to s.commands, "permanent" to s.permanent,
            "ownerUserId" to s.ownerUserId,
            "output" to s.output.toString(),
            "installed" to isInstalled(s.distroId)
        )
    }
}