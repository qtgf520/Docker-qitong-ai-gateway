package com.qitong.gateway.plugin

import com.qitong.gateway.db.Database
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * ★ v1.206 轻量插件系统（对齐主流智能体平台 Star 插件框架思路，自研实现）
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
     */
    fun onMessage(text: String, channel: String): String? {
        if (text.isBlank()) return null
        for (p in plugins.values) {
            if (!p.enabled) continue
            val hit = p.triggers.any { text.contains(it, ignoreCase = true) }
            if (!hit) continue
            when (p.action) {
                "reply" -> return p.content.ifBlank { "📦 ${p.name}：触发成功" }
                "ignore" -> return "\u0000IGNORE\u0000"   // 忽略标记：上层见到即不回复
                "ai" -> return null                          // 走 AI（不拦截）
                else -> return null
            }
        }
        return null
    }

    /** 在线市场接口预留（后续对接远程插件仓库） */
    fun marketPull(url: String): String = "🌐 在线插件市场对接功能开发中（预留接口：$url）"
}