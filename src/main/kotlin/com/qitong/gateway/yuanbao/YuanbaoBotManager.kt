package com.qitong.gateway.yuanbao

import com.qitong.gateway.db.Database
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 元宝 Bot 管理核心 —— 多 bot 生命周期 + AI 回复（对齐微信 WeixinBotManager 风格）
 * 每个 bot = 一个 YuanbaoClient（WS 长连接），收到消息后调网关 AI 回复
 */
object YuanbaoBotManager {

    @Volatile private var db: Database? = null
    @Volatile private var gatewayPort = 18889
    private val clients = ConcurrentHashMap<Long, YuanbaoClient>()
    private val statuses = ConcurrentHashMap<Long, YuanbaoRuntimeState>()
    private val histories = ConcurrentHashMap<String, MutableList<Pair<String, String>>>()  // key: botId:from
    private val lastReplyTs = ConcurrentHashMap<String, Long>()
    @Volatile private var watchdogStarted = false

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .build()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    fun init(database: Database, port: Int) { db = database; gatewayPort = port }

    fun statusOf(id: Long): YuanbaoRuntimeState = statuses.getOrPut(id) { YuanbaoRuntimeState() }
    fun allStatus(): Map<Long, YuanbaoRuntimeState> = statuses.toMap()

    fun startAll(database: Database, port: Int) {
        init(database, port)
        runCatching { database.getYuanbaoBots().filter { it.enabled }.forEach { startBot(it) } }
        if (!watchdogStarted) {
            watchdogStarted = true
            Thread {
                while (true) {
                    try {
                        Thread.sleep(60_000L)
                        val bots = db?.getYuanbaoBots()?.filter { it.enabled } ?: continue
                        for (b in bots) {
                            val st = statusOf(b.id)
                            if (st.status != "在线") {
                                println("[Yuanbao] ${b.name} 掉线自动重启（status=${st.status} err=${st.lastError}）")
                                runCatching { startBot(b) }
                            }
                        }
                    } catch (_: InterruptedException) { break }
                    catch (_: Exception) {}
                }
            }.apply { isDaemon = true; name = "yuanbao-watchdog"; start() }
        }
    }

    fun startBot(bot: YuanbaoBot) {
        stopBot(bot.id)
        if (bot.appKey.isBlank() || bot.appSecret.isBlank()) return
        val st = statusOf(bot.id)
        st.status = "连接中"; st.lastError = "启动中"
        val c = YuanbaoClient(
            botId = bot.id,
            name = bot.name,
            appKey = bot.appKey,
            appSecret = bot.appSecret,
            aiModel = bot.aiModel,
            systemPrompt = bot.systemPrompt,
            onMessage = { msg -> runCatching { handleMessage(bot, msg) } },
            onStatus = { s, err ->
                st.status = s; st.lastError = err
                if (s == "在线") st.readyAt = System.currentTimeMillis()
                runCatching { db?.addYuanbaoLog(bot.id, "", "bot_status", "$s $err", 0) }
                println("[Yuanbao] ${bot.name} -> $s $err")
            }
        )
        clients[bot.id] = c
        c.start()
    }

    fun stopBot(id: Long) {
        runCatching { clients.remove(id)?.stop() }
        val st = statusOf(id)
        st.status = "离线"; st.lastError = "已停止"
    }

    fun restartBot(id: Long) {
        val row = db?.getYuanbaoBotById(id) ?: return
        startBot(row)
    }

    /** 收到元宝消息 → AI 回复（对齐微信 askModel：qtai-sj 沙盒循环） */
    private fun handleMessage(bot: YuanbaoBot, msg: YuanbaoInbound) {
        val d = db ?: return
        val text = msg.text.trim()
        if (text.isEmpty()) return
        val key = "${bot.id}:${if (msg.isGroup) "g:${msg.groupCode}" else "c:${msg.fromAccount}"}"
        val send = { content: String ->
            if (msg.isGroup) clients[bot.id]?.sendGroup(msg.fromAccount, content, msg.groupCode)
            else clients[bot.id]?.sendC2C(msg.fromAccount, content)
        }
        // 冷却 2s（防刷）
        val last = lastReplyTs[key] ?: 0L
        if (System.currentTimeMillis() - last < 2000) return
        lastReplyTs[key] = System.currentTimeMillis()

        // 停止/帮助等内置
        if (text == "停止" || text == "停") { send("🛑 已停止当前任务"); d.addYuanbaoLog(bot.id, msg.fromAccount, "command", "停止指令", 0); return }
        if (text == "帮助" || text == "功能" || text == "怎么用") {
            send("🧰 元宝bot 可直接对话\n· 问我任何问题\n· 查余额/网关状态/体检\n· 提醒我 10分钟后 xxx\n· 切换人格 程序员\n其他需求直接说，我会自己找工具帮你搞定～")
            d.addYuanbaoLog(bot.id, msg.fromAccount, "command", "功能菜单", 0)
            return
        }
        if (text.startsWith("绑定账号", true)) {
            val parts = text.substringAfter(" ").trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            if (parts.size >= 2) {
                val r = com.qitong.gateway.auth.AuthManager.login(d, parts[0], parts.drop(1).joinToString(" "))
                if (r.isSuccess) {
                    val u = d.getUserByUsername(parts[0]) ?: run { send("❌ 账号校验失败"); return }
                    setYuanbaoBound(d, msg, u.id, bot.id)
                    send("✅ 绑定成功！已登录「${u.username}」角色 ${u.role}，可查余额/用量/管理网关")
                } else send("❌ 登录失败：${r.exceptionOrNull()?.message ?: "用户名或密码错误"}")
            } else send("🔐 语法：绑定账号 用户名 密码")
            d.addYuanbaoLog(bot.id, msg.fromAccount, "bind", text, 0)
            return
        }
        if (text == "我的账号" || text == "查余额") {
            val uid = getYuanbaoBoundUserId(d, msg, bot.id)
            if (uid <= 0) { send("🔓 未绑定账号。发「绑定账号 用户名 密码」登录"); return }
            val u = d.getUserById(uid)
            if (u == null) { send("🔓 账号不存在"); return }
            send("👤 ${u.username}（${u.role}）\n💰 余额：¥${"%.2f".format(d.getUserBalance(uid))}\n💳 累计充值：¥${"%.2f".format(u.totalRecharge)}")
            d.addYuanbaoLog(bot.id, msg.fromAccount, "query", "查账号", 0)
            return
        }

        // ★ v1.119 元宝网关技能直接调用（查状态/排行/体检等，复用 SkillExecutor）
        com.qitong.gateway.qq.QqBotManager.matchGatewaySkillPublic(text)?.let { (code, param) ->
            val uid = getYuanbaoBoundUserId(d, msg, bot.id)
            val r = kotlinx.coroutines.runBlocking { com.qitong.gateway.http.SkillExecutor.execute(d, code, param, uid) }
            send(r)
            d.addYuanbaoLog(bot.id, msg.fromAccount, "skill", "[$code] $text", 0)
            return
        }
        // 体检 / 全功能
        if (text == "体检" || text.contains("全部功能") || text.contains("在线状态")) {
            val uid = getYuanbaoBoundUserId(d, msg, bot.id)
            val isAdmin = d.getUserById(uid)?.let { it.role == "admin" || it.role == "agent" } == true
            val r = com.qitong.gateway.sandbox.SandboxEngine.execute("sys_health", emptyMap(), isAdmin, uid, d)
            send(r); return
        }

        // AI 对话（调网关模型）
        send("🤔 收到，让我想想…")
        val uid = getYuanbaoBoundUserId(d, msg, bot.id)
        val isAdmin = d.getUserById(uid)?.let { it.role == "admin" || it.role == "agent" } == true
        val reply = askModel(bot, key, text, uid, isAdmin, d)
        if (reply.isNullOrBlank()) send("⚠️ 抱歉，我这边处理失败了，请再试一次")
        else send(reply)
        d.addYuanbaoLog(bot.id, msg.fromAccount, "ai", text, 0)
    }

    /** 调网关 AI（qtai-sj 走完整沙盒 Agent 循环，对齐微信 askModel） */
    private fun askModel(bot: YuanbaoBot, key: String, userText: String, userId: Long, isAdmin: Boolean, d: Database): String? {
        val hist = histories.getOrPut(key) { mutableListOf() }
        val sandboxOn = bot.aiModel.equals("qtai-sj", true)
        val baseSys = if (sandboxOn) {
            val sb = com.qitong.gateway.sandbox.SandboxEngine.SYSTEM_PROMPT
            if (bot.systemPrompt.isNotBlank()) sb + "\n\n【机器人主人设定（必须严格遵守）】\n" + bot.systemPrompt else sb
        } else {
            bot.systemPrompt.ifBlank { "你是綦桐小助理，运行在元宝bot渠道。" }
        }
        // 长期记忆注入
        if (userId > 0) {
            runCatching {
                val mems = d.getMemories(userId, limit = 8)
                if (mems.isNotEmpty()) {
                    val msg = "\n\n【你对这位用户的长期记忆】\n" + mems.reversed().joinToString("\n") { m -> "- ${m["content"]}" }
                    baseSys + msg
                }
            }
        } else {
            runCatching {
                val mems = d.getYuanbaoBrainMemories(key, limit = 8)
                if (mems.isNotEmpty()) {
                    baseSys + "\n\n【你对这位用户的长期记忆】\n" + mems.reversed().joinToString("\n") { "- " + it }
                }
            }
        }
        return try {
            var content: String? = null
            // 首次请求
            val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", baseSys))
            hist.forEach { (r, c) -> msgs.put(JSONObject().put("role", r).put("content", c)) }
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
                .post(body.toRequestBody(JSON))
                .build()
            val firstResp = http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) null
                else JSONObject(resp.body?.string().orEmpty())
                    .optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                    ?.optString("content")?.trim().orEmpty()
            }
            content = firstResp
            // Agent 循环（qtai-sj 沙盒）
            val firstContent = firstResp ?: return null
            var finalContent = firstContent
            if (sandboxOn && firstContent.isNotBlank()) {
                var loop = 0
                var noCallGuide = 0
                var cur: String = firstContent
                while (loop < 20) {
                    loop++
                    val calls = com.qitong.gateway.sandbox.SandboxEngine.parseCalls(cur)
                    if (calls.isEmpty()) {
                        if (noCallGuide < 2 && (cur.contains("正在调用") || cur.contains("调用沙盒") || cur.contains("准备调用"))) {
                            noCallGuide++
                            val guideMsgs = JSONArray()
                                .put(JSONObject().put("role", "system").put("content", baseSys))
                                .put(JSONObject().put("role", "user").put("content", userText))
                                .put(JSONObject().put("role", "assistant").put("content", cur))
                                .put(JSONObject().put("role", "user").put("content", "我看到你说要调用工具但没输出标签，请严格按格式输出：[[沙盒:函数名(参数=值)]]\n可用函数见上面的知识库清单。"))
                            val guideBody = JSONObject()
                                .put("model", bot.aiModel)
                                .put("messages", guideMsgs)
                                .put("stream", false)
                                .put("temperature", 0.75)
                                .toString()
                            val guideReq = Request.Builder()
                                .url("http://127.0.0.1:$gatewayPort/v1/chat/completions")
                                .addHeader("Content-Type", "application/json")
                                .post(guideBody.toRequestBody(JSON))
                                .build()
                            val guided = http.newCall(guideReq).execute().use { resp2 ->
                                if (!resp2.isSuccessful) null
                                else JSONObject(resp2.body?.string().orEmpty())
                                    .optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                                    ?.optString("content")?.trim().orEmpty()
                            }
                            if (guided.isNullOrBlank()) break
                            cur = guided
                            continue
                        }
                        break
                    }
                    // 执行调用
                    val results = StringBuilder()
                    calls.forEach { (fn, args) ->
                        val r = com.qitong.gateway.sandbox.SandboxEngine.execute(fn, args, isAdmin, userId, d, "yuanbao")
                        results.append("【$fn 执行结果】\n$r\n")
                    }
                    val cleanText = cleanFunctionTags(cur)
                    val nextMsgs = JSONArray()
                        .put(JSONObject().put("role", "system").put("content", baseSys))
                        .put(JSONObject().put("role", "user").put("content", userText))
                        .put(JSONObject().put("role", "assistant").put("content", cleanText))
                        .put(JSONObject().put("role", "user").put("content", "【工具执行结果（内部参考）】\n$results\n\n请基于真实执行结果输出**完整、自然**的最终答复（包含关键结果/数据）；还需操作就继续调用工具；不要输出函数调用语法。"))
                    val nextBody = JSONObject()
                        .put("model", bot.aiModel)
                        .put("messages", nextMsgs)
                        .put("stream", false)
                        .put("temperature", 0.75)
                        .toString()
                    val nextReq = Request.Builder()
                        .url("http://127.0.0.1:$gatewayPort/v1/chat/completions")
                        .addHeader("Content-Type", "application/json")
                        .post(nextBody.toRequestBody(JSON))
                        .build()
                    val nextContent = http.newCall(nextReq).execute().use { resp3 ->
                        if (!resp3.isSuccessful) null
                        else JSONObject(resp3.body?.string().orEmpty())
                            .optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                            ?.optString("content")?.trim().orEmpty()
                    }
                    if (nextContent.isNullOrBlank()) break
                    cur = nextContent
                    finalContent = cur
                    if (com.qitong.gateway.sandbox.SandboxEngine.parseCalls(cur).isEmpty()) break
                }
                finalContent = cur
            }
            hist.add("user" to userText)
            hist.add("assistant" to finalContent)
            while (hist.size > 20) hist.removeAt(0)
            // 记忆沉淀
            if (userId > 0 && userText.length in 4..200) {
                runCatching { d.addMemory(userId, "元宝对话", userText.take(150), "short", "neutral", 3, "yuanbao_chat", "", "") }
            } else if (userText.length in 4..200) {
                runCatching { d.saveYuanbaoBrainMemory(key, userText.take(200)) }
            }
            cleanFunctionTags(finalContent)
        } catch (e: Exception) {
            System.err.println("[Yuanbao] AI 异常: ${e.message}")
            null
        }
    }

    /** 清洗函数调用标签（对齐 QQ） */
    private fun cleanFunctionTags(text: String): String {
        var s = text
        s = s.replace(Regex("\\[\\[沙盒:[^\\]]+\\]\\]"), "")
        s = s.replace(Regex("<(?:dots_function_call|function_call|invoke)[^>]*>[\\s\\S]*?</(?:dots_function_call|function_call|invoke)>"), "")
        s = s.replace(Regex("</?(?:dots_function_call|function_call|invoke)\\s*[^>]*>"), "")
        s = s.replace(Regex("<(?:parameter|param)\\s+name=\"[^\"]*\"[^>]*>[\\s\\S]*?</(?:parameter|param)>"), "")
        s = s.replace(Regex("</?(?:parameter|param)\\s*[^>]*>"), "")
        return s.trim()
    }

    // ===== 账号绑定（yunying 账号按 isGroup:fromAccount 存） =====
    private fun yuanbaoBindKey(msg: YuanbaoInbound) = if (msg.isGroup) "yb-g:${msg.groupCode}:${msg.fromAccount}" else "yb-c:${msg.fromAccount}"

    private fun setYuanbaoBound(d: Database, msg: YuanbaoInbound, userId: Long, botId: Long) {
        d.setConfig(yuanbaoBindKey(msg), "$userId|$botId")
    }

    private fun getYuanbaoBoundUserId(d: Database, msg: YuanbaoInbound, botId: Long): Long {
        val cfg = d.getConfig(yuanbaoBindKey(msg), "")
        if (cfg.isBlank()) return 0L
        val uid = cfg.split("|").firstOrNull()?.toLongOrNull() ?: 0L
        return uid
    }
}

/** 元宝 bot 运行时状态 */
data class YuanbaoRuntimeState(
    var status: String = "离线",
    var lastError: String = "",
    var readyAt: Long = 0L,
    var messagesHandled: Long = 0L
)

/** 元宝 bot 配置实体 */
data class YuanbaoBot(
    val id: Long = 0,
    val name: String = "",
    val appKey: String = "",
    val appSecret: String = "",
    val enabled: Boolean = true,
    val aiModel: String = "qtai-sj",
    val systemPrompt: String = "",
    val createdAt: Long = 0
)