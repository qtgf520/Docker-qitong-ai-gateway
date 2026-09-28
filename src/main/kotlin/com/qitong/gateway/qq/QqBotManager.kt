package com.qitong.gateway.qq

import com.qitong.gateway.db.Database
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap

/**
 * QQ 机器人统一管理器：多号长连接、消息路由、回复。
 * 收到消息后 loopback 调用本机网关 /v1/chat/completions（OpenAI 兼容），
 * 因此直接复用网关的模型/故障转移/qtai-sj/用量统计。
 */
object QqBotManager {

    private val clients = ConcurrentHashMap<Long, QqGatewayClient>()
    private val runtime = ConcurrentHashMap<Long, QqRuntimeState>()
    private val api = QqApiClient()
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    @Volatile private lateinit var db: Database
    @Volatile private var gatewayPort: Int = 18889

    // 简易上下文记忆：key -> 最近消息
    private val history = ConcurrentHashMap<String, MutableList<Pair<String, String>>>()
    private const val HISTORY_MAX = 10

    fun statusOf(id: Long): QqRuntimeState = runtime.getOrPut(id) { QqRuntimeState() }

    fun allStatus(): Map<Long, QqRuntimeState> = runtime.toMap()

    /** 启动所有已启用的机器人。 */
    fun startAll(scope: CoroutineScope, database: Database, gatewayPort: Int) {
        this.db = database
        this.gatewayPort = gatewayPort
        scope.launch(Dispatchers.IO) {
            val bots = database.getQqBots()
            bots.filter { (it["enabled"] as? Boolean) == true }.forEach { row ->
                val id = row["id"] as Long
                startBot(botFromRow(row))
            }
            println("[QQBot] 已尝试启动 ${bots.count { (it["enabled"] as? Boolean) == true }} 个机器人")
        }
    }

    private fun botFromRow(row: Map<String, Any?>): QqBot = QqBot(
        id = row["id"] as Long,
        appid = row["appid"] as String,
        token = row["token"] as? String ?: "",
        name = row["name"] as? String ?: "",
        enabled = (row["enabled"] as? Boolean) ?: true,
        aiModel = row["aiModel"] as? String ?: "qtai-sj",
        systemPrompt = row["systemPrompt"] as? String ?: "",
        welcome = row["welcome"] as? String ?: ""
    )

    fun startBot(bot: QqBot) {
        stopBot(bot.id)
        val state = statusOf(bot.id)
        val client = QqGatewayClient(
            bot = bot,
            onGroupMessage = { msg -> scopeIo { handleGroup(bot, msg) } },
            onC2cMessage = { msg -> scopeIo { handleC2c(bot, msg) } },
            onStatusChange = { s, err ->
                state.status = s
                state.lastError = err
                if (s == QqBotStatus.ONLINE) state.readyAt = System.currentTimeMillis()
            }
        )
        clients[bot.id] = client
        client.start()
    }

    fun stopBot(id: Long) {
        runCatching { clients.remove(id)?.stop() }
    }

    fun restartBot(id: Long) {
        val row = db.getQqBotById(id) ?: return
        startBot(botFromRow(row))
    }

    private fun scopeIo(block: () -> Unit) {
        CoroutineScope(Dispatchers.IO).launch { runCatching { block() } }
    }

    // ============ 消息处理 ============

    private fun handleGroup(bot: QqBot, msg: QqGroupMessage) {
        runtime[bot.id]?.let { it.messagesHandled++; it.groupsSeen = (it.groupsSeen + 1).coerceAtLeast(1) }
        db.touchQqGroup(msg.groupOpenid, bot.appid)
        val cfg = db.getQqGroupConfig(msg.groupOpenid)
        if (cfg != null && !(cfg["aiEnabled"] as Boolean)) return // 该群关闭了 AI

        val text = stripAt(msg.content).trim()
        if (text.isEmpty()) return

        val key = "g:${msg.groupOpenid}:${msg.memberOpenid}"
        val reply = askModel(bot, key, text)
        if (!reply.isNullOrBlank()) {
            api.sendGroupMessage(bot, msg.groupOpenid, reply, msg.msgId)
        }
    }

    private fun handleC2c(bot: QqBot, msg: QqC2cMessage) {
        runtime[bot.id]?.let { it.messagesHandled++ }
        val text = msg.content.trim()
        if (text.isEmpty()) return
        val key = "c:${msg.userOpenid}"
        val reply = askModel(bot, key, text)
        if (!reply.isNullOrBlank()) {
            api.sendC2cMessage(bot, msg.userOpenid, reply, msg.msgId)
        }
    }

    /** 去掉群里 @机器人 的前缀。 */
    private fun stripAt(raw: String): String {
        // QQ @ 通常形如 "@昵称 正文"，去掉第一个 @ 到第一个空格之间的内容
        if (raw.startsWith("@")) {
            val sp = raw.indexOf(' ')
            return if (sp >= 0) raw.substring(sp + 1) else ""
        }
        return raw
    }

    /** 调本机网关大模型，带简易上下文。 */
    private fun askModel(bot: QqBot, key: String, userText: String): String? {
        val hist = history.getOrPut(key) { mutableListOf() }
        val msgs = JSONArray()
        if (bot.systemPrompt.isNotBlank()) {
            msgs.put(JSONObject().put("role", "system").put("content", bot.systemPrompt))
        }
        hist.forEach { (role, content) -> msgs.put(JSONObject().put("role", role).put("content", content)) }
        msgs.put(JSONObject().put("role", "user").put("content", userText))

        val body = JSONObject()
            .put("model", bot.aiModel)
            .put("messages", msgs)
            .put("stream", false)
            .put("temperature", 0.8)
            .toString()

        val req = Request.Builder()
            .url("http://127.0.0.1:$gatewayPort/v1/chat/completions")
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        return try {
            http.newCall(req).execute().use { resp ->
                val respBody = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    System.err.println("[QQBot] 模型调用失败 ${bot.appid}: HTTP ${resp.code} $respBody")
                    return null
                }
                val obj = JSONObject(respBody)
                val content = obj.optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")
                    ?.trim().orEmpty()
                // 更新上下文
                hist.add("user" to userText)
                hist.add("assistant" to content)
                while (hist.size > HISTORY_MAX * 2) hist.removeAt(0)
                content
            }
        } catch (e: Exception) {
            System.err.println("[QQBot] 模型调用异常 ${bot.appid}: ${e.message}")
            null
        }
    }
}
