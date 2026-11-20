package com.qitong.gateway.mpx

import com.qitong.gateway.db.Database
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ConcurrentHashMap

/**
 * v1.193 公众号 AI 智能回答
 * - 复用网关大模型(本机 /v1/chat/completions loopback，跟 QQ/微信同链路)
 * - 每粉丝 openid 独立多轮上下文(保留最近 20 条)
 * - v1.223 完整沙盒 Agent 循环: 复用 SandboxEngine 自主调工具；模型思考话术实时客服消息推送；
 *   工具执行细节按 mp_progress_mode: quiet(默认)=只推思考、verbose=思考+步骤都推
 */
object MpBrain {

    /** 会话历史: key = botId:openid */
    private val history = ConcurrentHashMap<String, MutableList<Pair<String, String>>>()

    private val http = okhttp3.OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(90, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val jsonCt = "application/json".toMediaType()

    private fun chatLoopback(model: String, messages: List<Map<String, String>>, openid: String, pSend: (String) -> Unit): String {
        val msgsArr = org.json.JSONArray().apply {
            messages.forEach { m -> put(org.json.JSONObject().put("role", m["role"]).put("content", m["content"])) }
        }
        // ★ v1.229 学 AstrBot：公众号模型调用同样切流式（stream=true），AI 边想边说边推人话过渡语
        val sr = com.qitong.gateway.sandbox.StreamChatEngine.chatStream(
            model = model,
            messages = msgsArr,
            gatewayPort = 18889,
            onHumanLine = { line ->
                if (com.qitong.gateway.sandbox.StreamChatEngine.isHumanActionLine(line)) {
                    val sp = line.removePrefix("好的").removePrefix("嗯").removePrefix("好").trim()
                    if (sp.isNotEmpty()) pSend("💭 $sp")
                }
            }
        )
        val content = sr.content.trim()
        return if (content.isEmpty()) "⚠️ AI 服务暂不可用（流式空返回），请稍后再试"
        else content
    }

    fun ask(
        db: Database, bot: Map<String, Any?>, openid: String, userText: String,
        aiModel: String, systemPrompt: String, boundUserId: Long, bindAccountId: Long
    ): String {
        val botId = (bot["id"] as? Number)?.toLong() ?: 0L
        val key = "$botId:$openid"
        val hist = history.getOrPut(key) { mutableListOf() }
        hist.add("user" to userText)
        while (hist.size > 20) hist.removeAt(0)

        val effUserId = if (boundUserId > 0) boundUserId else bindAccountId
        // v1.223 默认 quiet(只推思考)；verbose=思考+步骤都推
        val verbose = db.getUserConfig(effUserId, "mp_progress_mode", "quiet") == "verbose"
        val pSend = { m: String ->
            val isToolDetail = m.contains("正在执行", true) || m.contains("完成：", true) || m.contains("同时处理", true)
            if (verbose || !isToolDetail) runCatching { MpPush.sendText(db, bot, openid, m) }
        }

        try {
            val sandboxPrompt = com.qitong.gateway.sandbox.SandboxEngine.SYSTEM_PROMPT
            val sysFull = if (systemPrompt.isNotBlank()) "$sandboxPrompt\n\n【公众号人设】$systemPrompt" else sandboxPrompt

            var content = userText
            val effUser = if (effUserId > 0) db.getUserById(effUserId) else null
            val isAdmin = effUser?.role == "admin" || effUser?.role == "agent"
            var reply = ""
            var loopGuard = 0
            val executedSigs = mutableSetOf<String>()
            var lastSpoken = ""

            // v1.223 Agent 循环: 模型输出沙盒调用 → 真执行 → 结果回填 → 再想，最多 8 轮
            while (loopGuard < 8) {
                loopGuard++
                val msgs = mutableListOf<Map<String, String>>()
                msgs.add(mapOf("role" to "system", "content" to sysFull))
                msgs.add(mapOf("role" to "user", "content" to content))
                val modelOut = chatLoopback(aiModel, msgs, openid, pSend)
                if (modelOut.startsWith("⚠️ AI") || modelOut.startsWith("🤔 我还没")) { reply = modelOut; break }

                val calls = com.qitong.gateway.sandbox.SandboxEngine.parseCalls(modelOut, executedSigs)
                if (calls.isEmpty()) {
                    reply = com.qitong.gateway.sandbox.SandboxEngine.cleanCallTags(modelOut).trim()
                    break
                }

                // ★ v1.226 学 AstrBot：真实思考由 reasoning_content 推理链提供（对齐 QQ/微信）
                //   正文不再当思考推（避免重复/假思考）；有调用轮次的正文仅用于解析工具
                val reasonTxt = com.qitong.gateway.sandbox.SandboxEngine.parseReasoningFromContent(modelOut) ?: ""
                if (reasonTxt.isNotBlank()) {
                    val r2 = reasonTxt.replace(Regex("\\s+"), " ").take(200)
                    if (r2.length >= 2 && r2 != lastSpoken && !r2.contains("工具执行结果")) {
                        lastSpoken = r2
                        pSend("💭 $r2")
                    }
                }

                val fn = calls.first().first
                val args = calls.first().second
                val argShow = args.entries.joinToString(",") { "${it.key}=${it.value}" }.take(80)
                pSend("🔧 正在执行：$fn($argShow)")
                val r = try {
                    com.qitong.gateway.sandbox.SandboxEngine.execute(fn, args, isAdmin, effUserId, db)
                } catch (e: Exception) {
                    "❌ 工具执行异常($fn)：${e.message}"
                }
                executedSigs.add(com.qitong.gateway.sandbox.SandboxEngine.callSignature(fn, args))
                pSend("✅ $fn 完成")
                content = "【$fn 执行结果】\n$r"
            }
            if (reply.isBlank()) reply = content
            reply = com.qitong.gateway.sandbox.SandboxEngine.cleanCallTags(reply).trim()

            hist.add("assistant" to reply)
            while (hist.size > 20) hist.removeAt(0)
            return reply
        } catch (e: Exception) {
            System.err.println("[MpBrain] AI 回答异常: ${e.message}")
            return "⚠️ AI 服务暂时出了点问题(${e.message})，请稍后再试"
        }
    }
}
