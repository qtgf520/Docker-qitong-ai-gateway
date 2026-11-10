package com.qitong.gateway.mpx

import com.qitong.gateway.db.Database
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ConcurrentHashMap

/**
 * ★ v1.195 公众号 AI 智能回答
 * - 复用网关大模型（本机 /v1/chat/completions，走 loopback，跟 QQ/微信同链路）
 * - 每粉丝 openid 独立多轮上下文（保留最近 20 条，与 QQ/微信一致）
 * - 步骤显示：默认 quiet（不推过程），用户发「显示步骤」→ verbose（Agent 干活过程实时客服消息推送）
 * - 完整沙盒 Agent 循环：复用 SandboxEngine（能自主调工具，跟 QQ/微信一个体验）（本期先做纯对话 + 步骤开关，Agent 调度下一版增强）
 */
object MpBrain {

    /** 会话历史：key = botId:openid → List<Pair<role, content>> */
    private val history = ConcurrentHashMap<String, MutableList<Pair<String, String>>>()

    private val http = okhttp3.OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(90, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    /**
     * AI 智能回答（阻塞调用，返回最终回复文本）
     * @param boundUserId 粉丝已绑定网关账号的 userId（0=未绑定，聊天仍可用但无账号权限）
     * @param bindAccountId 公众号配置的默认绑定账号（粉丝未绑定时用这个身份）
     */
    fun ask(
        db: Database, bot: Map<String, Any?>, openid: String, userText: String,
        aiModel: String, systemPrompt: String, boundUserId: Long, bindAccountId: Long
    ): String {
        val botId = (bot["id"] as? Number)?.toLong() ?: 0L
        val key = "$botId:$openid"

        // 每粉丝上下文（保留最近 20 条）
        val hist = history.getOrPut(key) { mutableListOf() }
        hist.add("user" to userText)
        while (hist.size > 20) hist.removeAt(0)

        // ★ 步骤模式：跟随用户配置（默认 quiet）
        val effUserId = if (boundUserId > 0) boundUserId else bindAccountId
        val verbose = db.getUserConfig(effUserId, "mp_progress_mode", "quiet") == "verbose"

        try {
            if (verbose) MpPush.sendText(db, bot, openid, "🤔 收到，开始处理…")

            // 调本机网关大模型（跟 QQ/微信同一链路：自动故障转移/用量/计费）
            val messages = mutableListOf<Map<String, String>>()
            if (systemPrompt.isNotBlank()) messages.add(mapOf("role" to "system", "content" to systemPrompt))
            hist.forEach { (role, content) -> messages.add(mapOf("role" to role, "content" to content)) }

            val bodyJson = org.json.JSONObject().apply {
                put("model", aiModel)
                put("messages", org.json.JSONArray().apply {
                    messages.forEach { m ->
                        put(org.json.JSONObject().put("role", m["role"]).put("content", m["content"]))
                    }
                })
                put("stream", false)
            }.toString()

            val req = okhttp3.Request.Builder()
                .url("http://127.0.0.1:18889/v1/chat/completions")
                .post(bodyJson.toRequestBody("application/json".toMediaType()))
                .build()

            val reply = http.newCall(req).execute().use { resp ->
                val bodyStr = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) "⚠️ AI 服务暂不可用（HTTP ${resp.code}），请稍后再试"
                else {
                    try {
                        val json = org.json.JSONObject(bodyStr)
                        json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
                            ?.takeIf { it.isNotBlank() }
                            ?: "🤔 我还没想好怎么回答，换个问法试试？"
                    } catch (_: Exception) { "⚠️ AI 响应解析失败，请稍后再试" }
                }
            }

            hist.add("assistant" to reply)
            while (hist.size > 20) hist.removeAt(0)
            return reply
        } catch (e: Exception) {
            System.err.println("[MpBrain] AI 回答异常: ${e.message}")
            return "⚠️ AI 服务暂时出了点问题（${e.message}），请稍后再试"
        }
    }
}