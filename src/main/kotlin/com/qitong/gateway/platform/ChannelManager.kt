package com.qitong.gateway.platform

import com.qitong.gateway.db.Database
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * ★ v1.247 多平台消息渠道：飞书 / 钉钉 / Slack / Discord / 自定义 Webhook
 *  - 出站：按平台格式 POST 群机器人 Webhook（飞书/钉钉走 text 结构，Slack/Discord 走 text/content）
 *  - 入站：/api/channels/{id}/inbound?token=… 接收文本，先过插件，再交给 AI 回复
 *  - 飞书事件订阅的 URL 校验（challenge）同时兼容
 */
object ChannelManager {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    fun send(ch: Map<String, Any?>, text: String): Pair<Boolean, String> {
        val type = (ch["type"] as? String ?: "custom").lowercase()
        val url = (ch["webhookUrl"] as? String ?: "").trim()
        if (url.isBlank()) return false to "未配置 Webhook 地址"
        if (!url.startsWith("http")) return false to "Webhook 地址必须以 http(s) 开头"
        val payload = when (type) {
            "feishu", "lark" -> JSONObject().put("msg_type", "text").put("content", JSONObject().put("text", text))
            "dingtalk" -> JSONObject().put("msgtype", "text").put("text", JSONObject().put("content", text))
            "slack" -> JSONObject().put("text", text)
            "discord" -> JSONObject().put("content", text.take(2000))
            else -> JSONObject().put("text", text)
        }
        return try {
            val req = Request.Builder().url(url)
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            client.newCall(req).execute().use { r ->
                val body = r.body?.string().orEmpty().take(300)
                if (r.isSuccessful) true to "HTTP ${r.code} $body" else false to "HTTP ${r.code} $body"
            }
        } catch (e: Exception) {
            false to (e.message ?: e::class.simpleName ?: "发送失败")
        }
    }

    /** 入站消息 → 插件优先，其次 AI；返回回复文本（空串表示不回复） */
    fun reply(db: Database, channelType: String, text: String, user: String, group: String): String {
        if (text.isBlank()) return ""
        val plugin = runCatching { com.qitong.gateway.plugin.PluginManager.onMessage(text, "channel:$channelType", user, group, user) }.getOrNull()
        if (!plugin.isNullOrBlank()) return plugin
        val model = db.getConfig("channel_ai_model", "").ifBlank {
            runCatching { db.getModels().firstOrNull { it.isEnabled && it.modelId.isNotBlank() }?.modelId }.getOrNull() ?: ""
        }
        if (model.isBlank()) return "（未配置可用模型，插件之外的消息暂不回复）"
        val base = "http://127.0.0.1:" + (System.getenv("GATEWAY_PORT")?.takeIf { it.isNotBlank() } ?: "18889")
        return try {
            val body = JSONObject()
                .put("model", model)
                .put("stream", false)
                .put("max_tokens", 1024)
                .put("messages", org.json.JSONArray().put(
                    JSONObject().put("role", "system").put("content", "你是綦桐AI网关的消息助手，回答简洁、准确、直接。")
                ).put(JSONObject().put("role", "user").put("content", text)))
            val req = Request.Builder().url("$base/v1/chat/completions")
                .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return "（AI 调用失败 HTTP ${r.code}）"
                JSONObject(r.body?.string().orEmpty()).optJSONArray("choices")
                    ?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
            }
        } catch (e: Exception) {
            "（AI 调用异常：${e.message}）"
        }
    }

    /** 从各家入站回调里尽量抽出文本 */
    fun extractText(raw: String, type: String): String {
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return ""
        // 飞书 v2 事件：event.message.content 是 JSON 字符串 {"text":"..."}
        val ev = obj.optJSONObject("event")
        val msg = ev?.optJSONObject("message")
        val content = msg?.optString("content").orEmpty()
        if (content.isNotBlank()) {
            runCatching { JSONObject(content).optString("text") }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
            return content
        }
        // 钉钉：text.content
        obj.optJSONObject("text")?.optString("content")?.takeIf { it.isNotBlank() }?.let { return it }
        // Slack Events：event.text
        ev?.optString("text")?.takeIf { it.isNotBlank() }?.let { return it }
        // 通用
        return obj.optString("text").ifBlank { obj.optString("content") }
    }
}
