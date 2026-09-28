package com.qitong.gateway.qq

import com.qitong.gateway.db.Database
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap

/**
 * QQ 机器人统一管理器：多号长连接 + 插件指令引擎 + 独立用户隔离 + 全量日志。
 *
 * 设计对齐"小栗子"式体验，但跑在 QQ 官方开放平台上：
 *  - 插件指令：触发器(exact/contains/regex) -> 回复 / HTTP / AI
 *  - 每用户独立：每人一把 Mutex，互不阻塞；独立冷却；独立人设与记忆
 *  - 大脑：loopback 本机 /v1/chat/completions，复用网关模型/故障转移/qtai-sj/用量
 */
object QqBotManager {

    private val clients = ConcurrentHashMap<Long, QqGatewayClient>()
    private val runtime = ConcurrentHashMap<Long, QqRuntimeState>()
    private val api = QqApiClient()
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    @Volatile private lateinit var db: Database
    @Volatile private var gatewayPort: Int = 18889

    // 简易上下文记忆：key -> 最近消息（每用户独立）
    private val history = ConcurrentHashMap<String, MutableList<Pair<String, String>>>()
    private const val HISTORY_MAX = 10
    // 每用户独立互斥：同一用户串行，不同用户并行，互不干扰
    private val userMutex = ConcurrentHashMap<String, Mutex>()
    // 每用户冷却时间戳
    private val lastReplyTs = ConcurrentHashMap<String, Long>()

    fun statusOf(id: Long): QqRuntimeState = runtime.getOrPut(id) { QqRuntimeState() }
    fun allStatus(): Map<Long, QqRuntimeState> = runtime.toMap()

    fun startAll(scope: CoroutineScope, database: Database, gatewayPort: Int) {
        this.db = database
        this.gatewayPort = gatewayPort
        // 记忆自动过期：每 6 小时清理一次 30 天前的 QQ 记忆
        scope.launch(Dispatchers.IO) {
            runCatching { db.cleanOldQqMemories(30) }
            while (true) {
                kotlinx.coroutines.delay(6 * 3600 * 1000L)
                runCatching { db.cleanOldQqMemories(30) }
            }
        }
        scope.launch(Dispatchers.IO) {
            val bots = database.getQqBots()
            bots.filter { (it["enabled"] as? Boolean) == true }.forEach { row ->
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

    fun stopBot(id: Long) { runCatching { clients.remove(id)?.stop() } }

    fun restartBot(id: Long) {
        val row = db.getQqBotById(id) ?: return
        startBot(botFromRow(row))
    }

    private fun scopeIo(block: () -> Unit) {
        CoroutineScope(Dispatchers.IO).launch { runCatching { block() } }
    }

    // ============ 消息处理（每用户独立互斥） ============

    private fun handleGroup(bot: QqBot, msg: QqGroupMessage) {
        val isNewGroup = db.getQqGroupConfig(msg.groupOpenid) == null
        db.touchQqGroup(msg.groupOpenid, bot.appid)
        db.touchQqUser(msg.memberOpenid)
        runtime[bot.id]?.let { it.messagesHandled++; it.groupsSeen = (it.groupsSeen + 1).coerceAtLeast(1) }

        // 新群首次触达：若开启欢迎且填了欢迎语，自动下发
        if (isNewGroup) {
            val cfg = db.getQqGroupConfig(msg.groupOpenid)
            if (cfg != null && (cfg["welcomeEnabled"] as Boolean)) {
                val g = cfg["greeting"] as? String
                if (!g.isNullOrBlank()) api.sendGroupMessage(bot, msg.groupOpenid, g, null)
            }
        }

        val text = stripAt(msg.content).trim()
        if (text.isEmpty()) return

        val key = "g:${msg.groupOpenid}:${msg.memberOpenid}"
        withUserLock(key) {
            dispatch(bot, key, msg.groupOpenid, msg.memberOpenid, text, msg.msgId,
                send = { content -> api.sendGroupMessage(bot, msg.groupOpenid, content, msg.msgId) })
        }
    }

    private fun handleC2c(bot: QqBot, msg: QqC2cMessage) {
        db.touchQqUser(msg.userOpenid)
        runtime[bot.id]?.let { it.messagesHandled++ }
        val key = "c:${msg.userOpenid}"
        withUserLock(key) {
            dispatch(bot, key, "", msg.userOpenid, msg.content.trim(), msg.msgId,
                send = { content -> api.sendC2cMessage(bot, msg.userOpenid, content, msg.msgId) })
        }
    }

    private fun withUserLock(key: String, block: () -> Unit) {
        val m = userMutex.getOrPut(key) { Mutex() }
        CoroutineScope(Dispatchers.IO).launch {
            m.withLock { runCatching { block() } }
        }
    }

    /** 统一分发：内置指令 > 插件指令 > 大模型；全程记日志。 */
    private fun dispatch(
        bot: QqBot, key: String, groupOpenid: String, userOpenid: String,
        text: String, msgId: String, send: (String) -> Boolean
    ) {
        val t0 = System.currentTimeMillis()
        // 0) 内置指令（签到/积分/全员禁言）
        val builtin = matchBuiltin(text, groupOpenid)
        if (builtin != null) {
            when (builtin.first) {
                "sign" -> {
                    if (onCooldown(userOpenid, 3)) return
                    val reward = (5..20).random()
                    val got = db.signQqUser(userOpenid, reward)
                    val reply = if (got != null) "签到成功 +$got 积分" else "今天已经签过到啦，明天再来～"
                    send(reply); lastReplyTs[userOpenid] = System.currentTimeMillis()
                    db.addQqLog(bot.appid, groupOpenid, userOpenid, "command", "签到", System.currentTimeMillis() - t0)
                    return
                }
                "points" -> {
                    val p = db.getQqPoints(userOpenid)
                    send("当前积分：${p["points"]}（累计签到 ${p["signCount"]} 次）")
                    return
                }
                "mute_on" -> {
                    if (groupOpenid.isBlank()) return
                    if (api.setGroupMute(bot, groupOpenid, true)) send("已开启全员禁言") else send("全员禁言失败（需机器人是群管理员）")
                    db.addQqLog(bot.appid, groupOpenid, userOpenid, "command", "全员禁言", System.currentTimeMillis() - t0)
                    return
                }
                "mute_off" -> {
                    if (groupOpenid.isBlank()) return
                    if (api.setGroupMute(bot, groupOpenid, false)) send("已解除全员禁言") else send("解除失败")
                    db.addQqLog(bot.appid, groupOpenid, userOpenid, "command", "解除全员禁言", System.currentTimeMillis() - t0)
                    return
                }
            }
        }
        // 1) 插件指令匹配
        val cmd = matchCommand(text)
        if (cmd != null) {
            if (onCooldown(userOpenid, (cmd["cooldown"] as? Number)?.toInt() ?: 5)) return
            val reply = when (cmd["action"]) {
                "http" -> httpGet(cmd["content"] as String)
                "ai" -> askModel(bot, key, "${cmd["content"]} $text", userOpenid)
                else -> cmd["content"] as String
            }
            if (!reply.isNullOrBlank()) {
                send(reply)
                lastReplyTs[userOpenid] = System.currentTimeMillis()
            }
            db.addQqLog(bot.appid, groupOpenid, userOpenid, "command",
                "命中[${cmd["trigger"]}] -> $text", System.currentTimeMillis() - t0)
            return
        }

        // 2) 大模型对话
        val u = db.getQqUser(userOpenid)
        if (u != null && !(u["aiEnabled"] as Boolean)) return
        val cfg = if (groupOpenid.isNotBlank()) db.getQqGroupConfig(groupOpenid) else null
        if (cfg != null && !(cfg["aiEnabled"] as Boolean)) return
        if (onCooldown(userOpenid, 3)) return

        val reply = askModel(bot, key, text, userOpenid)
        if (!reply.isNullOrBlank()) {
            send(reply)
            lastReplyTs[userOpenid] = System.currentTimeMillis()
            db.addQqLog(bot.appid, groupOpenid, userOpenid, "ai", text, System.currentTimeMillis() - t0)
        }
    }

    private fun onCooldown(userOpenid: String, seconds: Int): Boolean {
        val last = lastReplyTs[userOpenid] ?: 0L
        return System.currentTimeMillis() - last < seconds * 1000L
    }

    /** 内置指令匹配。 */
    private fun matchBuiltin(text: String, groupOpenid: String): Pair<String, String>? {
        val t = text.trim()
        if (t.equals("签到", true) || t.equals("打卡", true)) return "sign" to t
        if (t.equals("我的积分", true) || t.equals("积分", true)) return "points" to t
        if (groupOpenid.isNotBlank()) {
            if (t.equals("全员禁言", true)) return "mute_on" to t
            if (t.equals("解除全员禁言", true) || t.equals("取消全员禁言", true)) return "mute_off" to t
        }
        return null
    }

    /** 按优先级匹配一条启用指令。 */
    private fun matchCommand(text: String): Map<String, Any?>? {
        val cmds = db.getEnabledQqCommands()
        for (c in cmds) {
            val trigger = c["trigger"] as? String ?: continue
            if (trigger.isBlank()) continue
            val hit = when (c["matchType"]) {
                "contains" -> text.contains(trigger, ignoreCase = true)
                "regex" -> runCatching { Regex(trigger, RegexOption.IGNORE_CASE).containsMatchIn(text) }.getOrDefault(false)
                else -> text.equals(trigger, ignoreCase = true)
            }
            if (hit) return c
        }
        return null
    }

    private fun stripAt(raw: String): String {
        if (raw.startsWith("@")) {
            val sp = raw.indexOf(' ')
            return if (sp >= 0) raw.substring(sp + 1) else ""
        }
        return raw
    }

    private fun httpGet(url: String): String? {
        return try {
            val req = Request.Builder().url(url).get().build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return null
                r.body?.string()?.trim()?.take(500)
            }
        } catch (e: Exception) { null }
    }

    /** 调本机网关大模型，带每用户独立上下文与人设覆盖。 */
    private fun askModel(bot: QqBot, key: String, userText: String, userOpenid: String): String? {
        val hist = history.getOrPut(key) { mutableListOf() }
        val msgs = JSONArray()
        val userPersona = db.getQqUser(userOpenid)?.get("persona") as? String?
        val sys = when {
            !userPersona.isNullOrBlank() -> userPersona
            bot.systemPrompt.isNotBlank() -> bot.systemPrompt
            else -> ""
        }
        // 长期大脑记忆：把该用户最近的记忆注入 system，跨天记得对方
        val mems = db.getQqBrainMemories(userOpenid, limit = 6)
        val sysFull = if (mems.isNotEmpty()) {
            sys + "\n\n【你对这位用户的长期记忆】\n" + mems.reversed().joinToString("\n") { "- " + it }
        } else sys
        if (sysFull.isNotBlank()) msgs.put(JSONObject().put("role", "system").put("content", sysFull))
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
                    System.err.println("[QQBot] 模型调用失败 ${bot.appid}: HTTP ${resp.code}")
                    db.addQqLog(bot.appid, "", userOpenid, "error", "模型调用 HTTP ${resp.code}", 0)
                    return null
                }
                val obj = JSONObject(respBody)
                val content = obj.optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")?.trim().orEmpty()
                hist.add("user" to userText)
                hist.add("assistant" to content)
                while (hist.size > HISTORY_MAX * 2) hist.removeAt(0)
                // 长期记忆：把用户这次说的话存一条（截断，防止刷爆）
                if (userText.length >= 4) {
                    runCatching { db.saveQqBrainMemory(userOpenid, userText.take(200)) }
                }
                content
            }
        } catch (e: Exception) {
            System.err.println("[QQBot] 模型调用异常 ${bot.appid}: ${e.message}")
            db.addQqLog(bot.appid, "", userOpenid, "error", "模型异常 ${e.message}", 0)
            null
        }
    }
}
