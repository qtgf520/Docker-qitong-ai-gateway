package com.qitong.gateway.plugin

import com.qitong.gateway.db.Database
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * ★ v1.218 轻量插件系统（对齐主流智能体平台 Star 插件框架思路，自研实现）
 *
 * 插件目录规范：
 *   plugins/
 *     my_plugin/
 *       plugin.json          # manifest：name/version/desc/author/triggers/on_message
 *       plugin.kts           # 插件逻辑（Kotlin 脚本或简单 DSL；当前支持 on_message 文本钩子配置）
 *
 * 能力：
 *  - 目录扫描 + manifest 加载
 *  - 热加载 / 热重载（改 plugin.json 后 reload 生效，无需重启进程）
 *  - on_message 钩子（命中 triggers 关键词时按 action 回复 / 转发沙盒 / 忽略）
 *  - 插件启用/停用开关
 *  - 插件市场（本地市场目录可装卸；在线市场接口预留）
 */
object PluginManager {

    data class Plugin(
        val id: String,            // 插件目录名
        val name: String,
        val version: String,
        val desc: String = "",
        val author: String = "",
        val enabled: Boolean = true,
        val triggers: List<String> = emptyList(),   // 触发关键词（命中即激活）
        val action: String = "reply",               // reply=固定回复 / ignore=忽略 / ai=走AI
        val content: String = "",                   // reply 的固定内容（支持 {user}/{nick}/{group}/{args}/{time}/{date}/{plugin}/{channel} 变量）
        val webhookUrl: String = "",                // ★ v1.244 action=webhook 时转发的地址（Dify/Coze/n8n 等）
        val webhookToken: String = "",              // ★ v1.244 可选 Bearer Token
        val replies: List<String> = emptyList(),    // ★ v1.243 随机回复池（非空时优先于 content，随机抽一条）
        val commands: List<String> = emptyList(),   // ★ v1.239 指令集（等值/带参数匹配，如 签到、/签到、查天气）
        val match: String = "contains",             // ★ v1.239 触发词匹配模式：contains / exact / prefix / regex
        val cooldown: Int = 0,                      // ★ v1.239 单用户冷却秒数（0=不限制）
        val priority: Int = 50,                     // ★ v1.239 优先级，越大越先匹配
        val manifestPath: String = ""
    )

    private val plugins = ConcurrentHashMap<String, Plugin>()

    /** 插件根目录：优先 PLUGIN_DIR；否则跟随 DB_PATH 同级的 plugins（容器内 /data/qitong/plugins，持久化卷）；最后退 ./plugins */
    @Volatile
    var pluginRoot: String = run {
        val envDir = System.getenv("PLUGIN_DIR")?.takeIf { it.isNotBlank() }
        val dbParentDir = System.getenv("DB_PATH")?.takeIf { it.isNotBlank() }?.let { File(it).absoluteFile.parentFile }
        when {
            envDir != null -> envDir
            dbParentDir != null -> File(dbParentDir, "plugins").path
            else -> System.getProperty("user.dir") + File.separator + "plugins"
        }
    }

    /** ★ v1.218 插件单次调用超时上限（毫秒，默认 5000 可配；超时自动跳过该插件并记日志） */
    @Volatile
    var pluginTimeoutMs: Long = 5000L

    /** ★ v1.218 插件执行线程池：守护线程，主进程退出不阻塞；任务可被 interrupt 真正取消 */
    private val pluginExecutor: java.util.concurrent.ExecutorService = java.util.concurrent.Executors.newCachedThreadPool { r ->
        Thread(r, "plugin-exec").apply { isDaemon = true }
    }

    /** 初始化：确保目录存在 + 全量加载 */
    fun init(): String {
        return try {
            val dir = File(pluginRoot)
            if (!dir.exists()) dir.mkdirs()
            val n = scanAll()
            "📦 插件系统就绪：扫描 $n 个插件（目录: $pluginRoot）"
        } catch (e: Exception) {
            "❌ 插件系统初始化失败：${e.message}"
        }
    }

    /** 全量扫描插件目录 */
    fun scanAll(): Int {
        plugins.clear()
        val dir = File(pluginRoot)
        if (!dir.exists()) return 0
        dir.listFiles()?.filter { it.isDirectory }?.forEach { pd ->
            val mf = File(pd, "plugin.json")
            if (mf.exists()) {
                runCatching { loadManifest(pd.name, mf) }
            }
        }
        return plugins.size
    }

    /** 加载单个插件 manifest */
    private fun loadManifest(id: String, mf: File) {
        val text = mf.readText()
        val o = JSONObject(text)
        val p = Plugin(
            id = id,
            name = o.optString("name", id),
            version = o.optString("version", "1.0.0"),
            desc = o.optString("desc", ""),
            author = o.optString("author", ""),
            enabled = o.optBoolean("enabled", true),
            triggers = o.optJSONArray("triggers")?.let { arr -> (0 until arr.length()).map { arr.optString(it) } } ?: emptyList(),
            action = o.optString("action", "reply"),
            content = o.optString("content", ""),
            webhookUrl = o.optString("webhookUrl", o.optJSONObject("webhook")?.optString("url", "") ?: ""),
            webhookToken = o.optString("webhookToken", o.optJSONObject("webhook")?.optString("token", "") ?: ""),
            replies = o.optJSONArray("replies")?.let { arr -> (0 until arr.length()).map { arr.optString(it) } } ?: emptyList(),
            commands = o.optJSONArray("commands")?.let { arr -> (0 until arr.length()).map { arr.optString(it) } } ?: emptyList(),
            match = o.optString("match", "contains").lowercase(),
            cooldown = o.optInt("cooldown", 0),
            priority = o.optInt("priority", 50),
            manifestPath = mf.absolutePath
        )
        plugins[id] = p
    }

    /** 热重载：重新扫描（改 manifest 后调用，无需重启） */
    fun reload(): String {
        val n = scanAll()
        return "🔄 插件已热重载：共 $n 个插件"
    }

    /** 列出插件 */
    fun list(): List<Plugin> = plugins.values.sortedBy { it.id }

    /** 启用/停用插件 */
    fun setEnabled(id: String, enabled: Boolean): Boolean {
        val p = plugins[id] ?: return false
        plugins[id] = p.copy(enabled = enabled)
        // 写回 manifest（持久化）
        return runCatching {
            val mf = File(p.manifestPath)
            if (mf.exists()) {
                val o = JSONObject(mf.readText())
                o.put("enabled", enabled)
                mf.writeText(o.toString(2))
            }
            true
        }.getOrDefault(false)
    }

    /** 删除插件（卸载） */
    fun remove(id: String): Boolean {
        val p = plugins[id] ?: return false
        plugins.remove(id)
        return runCatching {
            val dir = File(p.manifestPath).parentFile
            dir.deleteRecursively()
            true
        }.getOrDefault(false)
    }

    /**
     * ★ on_message 钩子：消息进入时按插件触发规则处理。
     * @return 处理结果；null=无插件命中，继续走正常流程
     * ★ v1.218 运行时隔离：单个插件异常/超时仅跳过自身并记日志，绝不影响主进程与其他插件
     */
    /** 冷却表：key = 插件id + 用户标识 → 上次触发时间戳毫秒 */
    private val cooldownMap = ConcurrentHashMap<String, Long>()

    fun onMessage(text: String, channel: String, userId: String = "", groupId: String = "", nick: String = ""): String? {
        if (text.isBlank()) return null
        lastText = text; lastUser = userId; lastGroup = groupId; lastNick = nick; lastChannel = channel
        // ★ v1.239 按优先级排序（大者先），命中即返回
        val ordered = plugins.values.filter { it.enabled }.sortedByDescending { it.priority }
        for (p in ordered) {
            val hit = matchPlugin(p, text)
            val args = if (hit is MatchResult.Hit) hit.args else ""
            if (hit == null) continue
            // 冷却检查（按 插件+用户，无用户标识时按频道）
            val ck = p.id + "|" + (userId.ifBlank { channel })
            if (p.cooldown > 0) {
                val last = cooldownMap[ck] ?: 0L
                if (System.currentTimeMillis() - last < p.cooldown * 1000L) continue
            }
            // 独立错误边界：单插件异常/超时只跳过自身并记日志（含插件id+耗时），继续下一个
            val t0 = System.currentTimeMillis()
            val r = runCatching { invokePluginAction(p, t0) }
                .onFailure { e ->
                    System.err.println("[PluginManager] 插件 ${p.id} 执行失败已跳过：异常=${e::class.simpleName} 耗时=${System.currentTimeMillis() - t0}ms 原因=${e.message}")
                }
                .getOrNull() ?: continue
            cooldownMap[ck] = System.currentTimeMillis()
            return renderVars(r, p, channel, userId, groupId, nick, args)
        }
        return null
    }

    private sealed class MatchResult { data class Hit(val args: String) : MatchResult() }

    /** ★ v1.239 触发判定：指令优先（等值或「指令+参数」），其次触发词按 match 模式 */
    private fun matchPlugin(p: Plugin, text: String): MatchResult? {
        val t = text.trim()
        // 1) 指令：完全相等，或以「指令 + 空格/冒号」开头（可带参数）
        for (c in p.commands) {
            if (c.isBlank()) continue
            val cmd = c.trim()
            val plain = cmd.removePrefix("/")
            val forms = listOf(cmd, plain, "/" + plain)
            for (f in forms) {
                if (t.equals(f, ignoreCase = true)) return MatchResult.Hit("")
                if (t.startsWith(f + " ", ignoreCase = true)) return MatchResult.Hit(t.substring(f.length).trim())
                if (t.startsWith(f + "：", ignoreCase = true) || t.startsWith(f + ":", ignoreCase = true))
                    return MatchResult.Hit(t.substring(f.length).trim().trimStart('：', ':'))
            }
        }
        // 2) 触发词
        if (p.triggers.isEmpty()) return null
        val hit = when (p.match) {
            "exact" -> p.triggers.any { t.equals(it, ignoreCase = true) }
            "prefix" -> p.triggers.any { t.startsWith(it, ignoreCase = true) }
            "regex" -> p.triggers.any { runCatching { Regex(it, RegexOption.IGNORE_CASE).containsMatchIn(t) }.getOrDefault(false) }
            else -> p.triggers.any { t.contains(it, ignoreCase = true) }
        }
        if (!hit) return null
        val m = p.triggers.firstOrNull { tr ->
            when (p.match) {
                "exact" -> t.equals(tr, ignoreCase = true)
                "prefix" -> t.startsWith(tr, ignoreCase = true)
                "regex" -> runCatching { Regex(tr, RegexOption.IGNORE_CASE).containsMatchIn(t) }.getOrDefault(false)
                else -> t.contains(tr, ignoreCase = true)
            }
        }
        val args = if (m != null && p.match == "prefix" && t.length > m.length) t.substring(m.length).trim() else ""
        return MatchResult.Hit(args)
    }

    /** ★ v1.239 回复模板变量替换：{user} {nick} {group} {args} {time} {date} {plugin} {channel} */
    private fun renderVars(raw: String, p: Plugin, channel: String, userId: String, groupId: String, nick: String, args: String): String {
        if (raw == "\u0000IGNORE\u0000") return raw
        val now = java.time.LocalDateTime.now()
        val fmt = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
        val dft = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd")
        return raw
            .replace("{user}", userId.ifBlank { "同学" })
            .replace("{nick}", nick.ifBlank { userId.ifBlank { "同学" } })
            .replace("{group}", groupId)
            .replace("{args}", args)
            .replace("{time}", now.format(fmt))
            .replace("{date}", now.format(dft))
            .replace("{plugin}", p.name)
            .replace("{channel}", channel)
            .replace(Regex("\\{random:(\\d+)-(\\d+)\\}")) { m ->
                val a = m.groupValues[1].toIntOrNull() ?: 1
                val b = m.groupValues[2].toIntOrNull() ?: 100
                (if (a <= b) (a..b).random() else a).toString()
            }
    }

    /** ★ v1.218 单插件动作执行：独立线程 + 可中断真取消（daemon 线程池） + 超时上限 */
    private fun invokePluginAction(p: Plugin, t0: Long): String? {
        if (p.action == "ai") return null   // 放行走 AI，不拦截
        val fut = pluginExecutor.submit<String?> {
            when (p.action) {
                "reply" -> p.replies.takeIf { it.isNotEmpty() }?.randomOrNull()
                    ?: p.content.ifBlank { "📦 ${p.name}：触发成功" }
                "ignore" -> "\u0000IGNORE\u0000"   // 忽略标记：上层见到即不回复
                "webhook" -> callWebhook(p)
                else -> null
            }
        }
        return try {
            fut.get(pluginTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            fut.cancel(true)   // 真正中断线程（interrupt），非仅抛异常
            System.err.println("[PluginManager] 插件 ${p.id} 调用超时已取消：${pluginTimeoutMs}ms 耗时=${System.currentTimeMillis() - t0}ms")
            null
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e   // 插件业务异常向上抛，由 onMessage 捕获跳过
        }
    }

    /** ★ v1.244 webhook 执行：把消息 POST 给外部工作流（Dify/Coze/n8n/自建 API），返回其答复
     *  请求体：{text,user,group,nick,channel,plugin}；响应兼容 {answer}/{reply}/{content}/{text}/纯文本
     */
    @Suppress("DEPRECATION")
    private fun callWebhook(p: Plugin): String? {
        if (p.webhookUrl.isBlank()) return "⚠️ 插件「${p.name}」未配置 webhookUrl"
        val body = JSONObject()
            .put("text", lastText)
            .put("user", lastUser)
            .put("group", lastGroup)
            .put("nick", lastNick)
            .put("channel", lastChannel)
            .put("plugin", p.name)
        val req = okhttp3.Request.Builder().url(p.webhookUrl)
            .post(okhttp3.RequestBody.create("application/json".toMediaType(), body.toString()))
            .addHeader("Accept", "application/json, text/plain")
            .apply { if (p.webhookToken.isNotBlank()) addHeader("Authorization", "Bearer ${p.webhookToken}") }
            .build()
        val client = com.qitong.gateway.network.WebToolClient.http.newBuilder()
            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build()
        return try {
            client.newCall(req).execute().use { resp ->
                val raw = resp.body?.string() ?: ""
                if (raw.isBlank()) return if (resp.code in 200..299) null else "⚠️ 工作流返回空（HTTP ${resp.code}）"
                runCatching {
                    val o = JSONObject(raw)
                    o.optString("answer").ifBlank { o.optString("reply") }
                        .ifBlank { o.optString("content") }.ifBlank { o.optString("text") }
                        .ifBlank { o.optJSONObject("data")?.optString("answer") ?: "" }
                }.getOrNull()?.takeIf { it.isNotBlank() } ?: raw.take(1500)
            }
        } catch (e: Exception) {
            "⚠️ 工作流调用失败：${e.message ?: e::class.simpleName}"
        }
    }

    /** 当前消息上下文（webhook 用；单线程池内短生命周期，够用） */
    @Volatile private var lastText = ""
    @Volatile private var lastUser = ""
    @Volatile private var lastGroup = ""
    @Volatile private var lastNick = ""
    @Volatile private var lastChannel = ""

    /** ★ v1.218 在线插件市场：从远程仓库拉取 JSON 索引并安装/更新插件
     *  - marketPull(url)          ：拉取索引（name/version/desc/entry），比对本地版本，返回可更新/可安装列表
     *  - marketPull(url, install) ：带 install=true 时，把索引里每个插件按 entry 的 zip/manifest 拉取并落地到插件目录
     *  索引 JSON 格式：{"plugins":[{"name":"xxx","version":"1.2.0","desc":"...","entry":"https://.../xxx.zip"}]}
     *  entry 指向的 zip 需内含 plugin.json（与本地目录规范一致），落地后自动 scanAll 生效
     */
    /** ★ v1.240 市场列表（结构化）：给后台卡片式市场用，返回每个插件的远端/本地版本与状态 */
    fun marketList(url: String): List<Map<String, Any?>> {
        val idx = fetchIndex(url) ?: return emptyList()
        val out = ArrayList<Map<String, Any?>>()
        for (i in 0 until idx.length()) {
            val p = idx.optJSONObject(i) ?: continue
            val name = p.optString("name", ""); val version = p.optString("version", "")
            val desc = p.optString("desc", ""); val entry = p.optString("entry", "")
            if (name.isBlank() || entry.isBlank()) continue
            val local = plugins.values.firstOrNull { it.name == name || it.id == name }
            val state = when { local == null -> "new"; local.version != version -> "update"; else -> "latest" }
            out.add(mapOf("name" to name, "version" to version, "desc" to desc, "entry" to entry,
                "localVersion" to (local?.version ?: ""), "state" to state))
        }
        return out
    }

    /** 拉取市场索引并归一化成 JSONArray */
    private fun fetchIndex(url: String): org.json.JSONArray? {
        val indexText = try {
            val req = okhttp3.Request.Builder().url(url)
                .addHeader("User-Agent", "QitongAI-Gateway/1.0")
                .addHeader("Accept", "application/json").get().build()
            com.qitong.gateway.network.WebToolClient.http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) null else resp.body?.string()
            }
        } catch (e: Exception) { null }
        if (indexText.isNullOrBlank()) return null
        return try {
            val o = JSONObject(indexText)
            if (o.has("plugins")) o.getJSONArray("plugins") else org.json.JSONArray("[$indexText]")
        } catch (e: Exception) {
            try { org.json.JSONArray(indexText) } catch (e2: Exception) { null }
        }
    }

    fun marketPull(url: String, install: Boolean = false): String {
        if (url.isBlank()) return "⚠️ 语法：market(url=插件仓库索引地址, install=true可选)"
        // 1) 拉取远端索引 JSON
        val indexText = try {
            val req = okhttp3.Request.Builder()
                .url(url)
                .addHeader("User-Agent", "QitongAI-Gateway/1.0")
                .addHeader("Accept", "application/json")
                .get()
                .build()
            val client = com.qitong.gateway.network.WebToolClient.http
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) null else resp.body?.string()
            }
        } catch (e: Exception) { null }
        if (indexText.isNullOrBlank()) return "❌ 拉取插件索引失败：$url（网络不可达或非 JSON）"
        val arr = try {
            val o = JSONObject(indexText)
            if (o.has("plugins")) o.getJSONArray("plugins") else org.json.JSONArray("[$indexText]")
        } catch (e: Exception) {
            // 兼容：顶层就是数组
            try { org.json.JSONArray(indexText) } catch (e2: Exception) { null }
        } ?: return "❌ 索引格式无法解析（期望 {\"plugins\":[{name,version,desc,entry}]}）"

        val sb = StringBuilder()
        var installed = 0
        for (i in 0 until arr.length()) {
            val p = arr.optJSONObject(i) ?: continue
            val name = p.optString("name", "")
            val version = p.optString("version", "")
            val desc = p.optString("desc", "")
            val entry = p.optString("entry", "")
            if (name.isBlank() || entry.isBlank()) continue
            // 2) 比对本地版本
            val local = plugins.values.firstOrNull { it.name == name || it.id == name }
            val needUpdate = local == null || version != local.version
            sb.append(if (local == null) "🆕" else if (needUpdate) "⬆️" else "✅")
                .append(" 「").append(name).append("」v").append(version)
                .append(if (local == null) "（可安装）" else if (needUpdate) "（本地 ${local.version} → 可更新）" else "（已是最新）")
                .append(if (desc.isNotBlank()) " - " + desc.take(50) else "").append("\n")
            // 3) 安装/更新（entry 落 zip 到插件目录）
            if (install && needUpdate) {
                val ok = installFromEntry(name, entry)
                if (ok) installed++ else sb.append("   ❌ 「").append(name).append("」下载/安装失败\n")
            }
        }
        val head = "🌐 插件市场（$url）\n"
        val tail = if (install) "\n📦 本次安装/更新 $installed 个插件，已热重载生效" else "\n💡 加 install=true 一键安装全部可装/可更新项"
        return head + sb.toString().trim() + tail
    }

    /** ★ v1.236 从 GitHub 仓库 / zip 直链一键安装（对齐 AstrBot 插件 URL 安装） */
    fun installFromUrl(rawUrl: String): String {
        val u = normalizePluginUrl(rawUrl)
        if (u.isBlank()) return "⚠️ 请提供 GitHub 仓库地址（如 https://github.com/用户/仓库）或 zip 直链"
        val name = guessPluginName(u)
        var ok = downloadAndInstall(name, u)
        // GitHub 仓库默认分支可能是 master：main 失败再试 master
        if (!ok && isGithubRepo(u)) {
            val alt = u.replace("/refs/heads/main.zip", "/refs/heads/master.zip")
            if (alt != u) ok = downloadAndInstall(name, alt)
        }
        return if (ok) {
            scanAll()
            "✅ 已安装插件「$name」，已热加载生效（触发词见插件配置）"
        } else "❌ 安装失败：$u（要求 zip 内含 plugin.json，且网络可达）"
    }

    private fun isGithubRepo(u: String): Boolean = u.contains("github.com/")

    private fun normalizePluginUrl(raw: String): String {
        val u = raw.trim()
        if (u.isBlank()) return ""
        if (u.endsWith(".zip")) return u
        val gh = Regex("""https?://github\.com/([^/]+)/([^/]+)/?$""").find(u)
        if (gh != null) return "https://github.com/${gh.groupValues[1]}/${gh.groupValues[2]}/archive/refs/heads/main.zip"
        return u
    }

    private fun guessPluginName(url: String): String {
        Regex("""github\.com/([^/]+)/([^/]+)""").find(url)?.let {
            return sanitizeName(it.groupValues[2].removeSuffix(".git").substringBefore("/"))
        }
        return sanitizeName(url.substringAfterLast('/').substringBefore('?').removeSuffix(".zip"))
    }

    /** 从 entry URL 下载 zip 并落地到插件目录（zip 内含 plugin.json；落地后自动装载） */
    private fun installFromEntry(pluginName: String, entry: String): Boolean = downloadAndInstall(pluginName, entry)

    private fun downloadAndInstall(pluginName: String, entry: String): Boolean {
        return try {
            val dir = File(pluginRoot)
            if (!dir.exists()) dir.mkdirs()
            val zipFile = File(dir, ".market_${pluginName}.zip")
            // 下载
            val req = okhttp3.Request.Builder().url(entry).get().build()
            val client = com.qitong.gateway.network.WebToolClient.http
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return false
                resp.body?.byteStream()?.use { ins -> zipFile.outputStream().use { ins.copyTo(it) } }
            }
            if (!zipFile.exists() || zipFile.length() < 50) { zipFile.delete(); return false }
            // 解压到插件目录
            val target = File(dir, sanitizeName(pluginName))
            if (target.exists()) target.deleteRecursively()
            target.mkdirs()
            java.util.zip.ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
                var e = zis.nextEntry
                while (e != null) {
                    val outFile = File(target, e.name)
                    if (e.isDirectory) { outFile.mkdirs() } else {
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().use { zis.copyTo(it) }
                    }
                    zis.closeEntry(); e = zis.nextEntry
                }
            }
            zipFile.delete()
            // ★ v1.236 GitHub archive 会多一层 仓库名-分支/ 目录：单层则拍平
            var mf = File(target, "plugin.json")
            if (!mf.exists()) {
                val subs = target.listFiles()?.filter { it.isDirectory } ?: emptyList()
                if (subs.size == 1 && File(subs[0], "plugin.json").exists()) {
                    subs[0].listFiles()?.forEach { f -> runCatching { f.renameTo(File(target, f.name)) } }
                    runCatching { subs[0].deleteRecursively() }
                    mf = File(target, "plugin.json")
                }
            }
            if (!mf.exists()) { target.deleteRecursively(); return false }
            scanAll()
            true
        } catch (e: Exception) { false }
    }

    /** 插件目录名净化（防路径穿越） */
    private fun sanitizeName(name: String): String =
        name.replace(Regex("[^a-zA-Z0-9_\\-\\u4e00-\\u9fa5]"), "_").take(60).ifBlank { "plugin_${System.currentTimeMillis()}" }
}