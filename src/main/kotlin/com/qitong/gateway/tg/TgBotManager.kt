package com.qitong.gateway.tg

import com.qitong.gateway.db.Database
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * ★ v1.215 Telegram 平台适配器（对齐多平台架构，自研实现）
 * 能力：Bot Token 长轮询收消息 → 网关 AI 回复；sendMessage 主动推送；
 *      后台配置：botToken/extra、启停开关。平台层抽象：新增平台=加适配器+后台配置。
 */
object TgBotManager {

    data class TgBot(val id: Long, val name: String, val botToken: String, val enabled: Boolean)

    private data class State(val status: String = "OFFLINE", var lastSeq: Long = 0L)
    private val bots = ConcurrentHashMap<Long, State>()
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(70, TimeUnit.SECONDS).build()
    private val jsonCt = "application/json; charset=utf-8".toMediaType()
    private var db: Database? = null
    private var gatewayPort = 18889

    fun startAll(database: Database, port: Int) {
        db = database; gatewayPort = port
        db?.getConfig("tg_bots", "")?.takeIf { it.isNotBlank() }?.split(",")?.forEach { tok0 ->
            val tok = tok0.trim()
            if (tok.isNotBlank()) runCatching { startBot(TgBot(System.currentTimeMillis()%100000, "tg-"+tok.takeLast(8), tok, true)) }
        }
    }

    fun startBot(b: TgBot) {
        bots[b.id] = State("ONLINE")
        Thread { loop(b) }.apply { isDaemon = true; name = "tg-${b.id}"; start() }
    }

    private fun loop(b: TgBot) {
        while (bots[b.id]?.status != "OFFLINE") {
            try {
                val st = bots[b.id] ?: break
                val off = if (st.lastSeq > 0) st.lastSeq else 0
                val url = "https://api.telegram.org/bot${b.botToken}/getUpdates?timeout=50&offset=${off + 1}"
                val req = Request.Builder().url(url).get().build()
                http.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val o = JSONObject(resp.body?.string().orEmpty())
                        val arr = o.optJSONArray("result") ?: JSONArray()
                        for (i in 0 until arr.length()) {
                            val u = arr.optJSONObject(i) ?: continue
                            st.lastSeq = u.optLong("update_id", st.lastSeq)
                            handleUpdate(b, u)
                        }
                    }
                }
            } catch (e: Exception) {
                if (bots[b.id]?.status == "OFFLINE") break
            }
        }
    }

    private fun handleUpdate(b: TgBot, u: JSONObject) {
        val msg = u.optJSONObject("message") ?: u.optJSONObject("edited_message") ?: return
        val chat = msg.optJSONObject("chat") ?: return
        val chatId = chat.optLong("id")
        val text = msg.optString("text", "").trim()
        if (text.isEmpty() || chatId == 0L) return
        val d = db ?: return
        // 插件钩子
        runCatching {
            val pHit = com.qitong.gateway.plugin.PluginManager.onMessage(text, "tg")
            if (pHit != null) { if (pHit != "\u0000IGNORE\u0000") sendMessage(b, chatId, pHit); return }
        }
        // 调网关 AI
        try {
            val body = JSONObject().put("model", "qtai-sj")
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", text)))
                .put("stream", false)
            val req = Request.Builder().url("http://127.0.0.1:$gatewayPort/v1/chat/completions")
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody(jsonCt)).build()
            http.newCall(req).execute().use { r ->
                if (r.isSuccessful) {
                    val reply = JSONObject(r.body?.string().orEmpty()).optJSONArray("choices")
                        ?.optJSONObject(0)?.optJSONObject("message")?.optString("content")?.trim().orEmpty()
                    if (reply.isNotBlank()) sendMessage(b, chatId, reply)
                }
            }
        } catch (e: Exception) { sendMessage(b, chatId, "⚠️ 处理失败：${e.message}") }
        runCatching { d.addPlatformMessage("tg", b.id, chatId.toString(), "", "in", text, "qtai-sj") }
    }

    fun sendMessage(b: TgBot, chatId: Long, text: String) {
        runCatching {
            val body = JSONObject().put("chat_id", chatId).put("text", text)
            val req = Request.Builder().url("https://api.telegram.org/bot${b.botToken}/sendMessage")
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody(jsonCt)).build()
            http.newCall(req).execute().use { it.body?.string() }
        }
        runCatching { db?.addPlatformMessage("tg", b.id, chatId.toString(), "", "out", text, "qtai-sj") }
    }

    fun status(): String = bots.entries.joinToString("; ") { "${it.key}:${it.value.status}" }
}