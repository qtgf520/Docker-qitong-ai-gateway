package com.qitong.gateway.plugin

import com.qitong.gateway.db.Database
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
        val content: String = "",                   // reply 的固定内容
        val manifestPath: String = ""
    )

    private val plugins = ConcurrentHashMap<String, Plugin>()

    /** 插件根目录（容器内 /data/qitong/plugins，本地测试 ./plugins） */
    @Volatile
    var pluginRoot: String = System.getProperty("user.dir") + File.separator + "plugins"

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
    fun onMessage(text: String, channel: String): String? {
        if (text.isBlank()) return null
        for (p in plugins.values) {
            if (!p.enabled) continue
            val hit = p.triggers.any { text.contains(it, ignoreCase = true) }
            if (!hit) continue
            // 独立错误边界：单插件异常/超时只跳过自身并记日志（含插件id+耗时），继续下一个
            val t0 = System.currentTimeMillis()
            val r = runCatching { invokePluginAction(p, t0) }
                .onFailure { e ->
                    System.err.println("[PluginManager] 插件 ${p.id} 执行失败已跳过：异常=${e::class.simpleName} 耗时=${System.currentTimeMillis() - t0}ms 原因=${e.message}")
                }
                .getOrNull() ?: continue
            return r
        }
        return null
    }

    /** ★ v1.218 单插件动作执行：独立线程 + 可中断真取消（daemon 线程池） + 超时上限 */
    private fun invokePluginAction(p: Plugin, t0: Long): String? {
        if (p.action == "ai") return null   // 放行走 AI，不拦截
        val fut = pluginExecutor.submit<String?> {
            when (p.action) {
                "reply" -> p.content.ifBlank { "📦 ${p.name}：触发成功" }
                "ignore" -> "\u0000IGNORE\u0000"   // 忽略标记：上层见到即不回复
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

    /** ★ v1.218 在线插件市场：从远程仓库拉取 JSON 索引并安装/更新插件
     *  - marketPull(url)          ：拉取索引（name/version/desc/entry），比对本地版本，返回可更新/可安装列表
     *  - marketPull(url, install) ：带 install=true 时，把索引里每个插件按 entry 的 zip/manifest 拉取并落地到插件目录
     *  索引 JSON 格式：{"plugins":[{"name":"xxx","version":"1.2.0","desc":"...","entry":"https://.../xxx.zip"}]}
     *  entry 指向的 zip 需内含 plugin.json（与本地目录规范一致），落地后自动 scanAll 生效
     */
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

    /** 从 entry URL 下载 zip 并落地到插件目录（zip 内含 plugin.json；落地后自动装载） */
    private fun installFromEntry(pluginName: String, entry: String): Boolean {
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
            // manifest 校验 + 重新扫描
            val mf = File(target, "plugin.json")
            if (!mf.exists()) { target.deleteRecursively(); return false }
            scanAll()
            true
        } catch (e: Exception) { false }
    }

    /** 插件目录名净化（防路径穿越） */
    private fun sanitizeName(name: String): String =
        name.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").take(60).ifBlank { "plugin_${System.currentTimeMillis()}" }
}