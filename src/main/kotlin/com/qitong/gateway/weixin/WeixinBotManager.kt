package com.qitong.gateway.weixin

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
 * 微信通道管理器（对齐 QqBotManager 简化版）
 *  - 多 bot 长轮询管理（start/stop/restart）
 *  - 消息分发：绑定账号 → 管理指令（白名单）→ AI 对话（loopback /v1/chat/completions）
 *  - 绑定机制复用：微信 openid 绑定网关账号（绑定码/账号密码）
 */
object WeixinBotManager {

    private val api = WeixinApiClient()
    private val clients = ConcurrentHashMap<Long, WeixinGatewayClient>()
    private val runtime = ConcurrentHashMap<Long, WeixinRuntimeState>()
    private val pendingBind = ConcurrentHashMap<String, Pair<Int, String>>()
    private val lastReplyTs = ConcurrentHashMap<String, Long>()
    private var db: Database? = null
    private var gatewayPort = 18889

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val jsonCt = "application/json; charset=utf-8".toMediaType()

    fun init(database: Database, port: Int) { db = database; gatewayPort = port }

    fun statusOf(id: Long): WeixinRuntimeState = runtime.getOrPut(id) { WeixinRuntimeState() }
    fun allStatus(): Map<Long, WeixinRuntimeState> = runtime.toMap()

    fun startAll(database: Database, port: Int) {
        init(database, port)
        runCatching { database.getWeixinBots().filter { it.enabled }.forEach { startBot(it) } }
    }

    fun startBot(bot: WeixinBot) {
        stopBot(bot.id)
        if (bot.botToken.isBlank()) return
        val state = statusOf(bot.id)
        val client = WeixinGatewayClient(
            bot = bot,
            api = api,
            onMessage = { msg -> runCatching { handleMessage(bot, msg) } },
            onStatusChange = { s, err ->
                state.status = s; state.lastError = err
                if (s == WeixinBotStatus.ONLINE) state.readyAt = System.currentTimeMillis()
                // ★ v69e 状态写日志（后台可看连接过程）
                runCatching {
                    val msg = when (s) {
                        WeixinBotStatus.ONLINE -> "✅ 微信bot已连接（长轮询在线）"
                        WeixinBotStatus.CONNECTING -> "🔄 正在连接微信 ilink…"
                        WeixinBotStatus.ERROR -> "❌ 连接异常：$err"
                        WeixinBotStatus.PAUSED -> "⏸ 风控暂停：$err"
                        WeixinBotStatus.OFFLINE -> "⏹ 已停止/离线：$err"
                        WeixinBotStatus.LOGIN_WAIT -> "等待扫码确认"
                    }
                    db?.addWeixinLog(bot.id, "", "bot_status", msg, 0)
                }
            }
        )
        clients[bot.id] = client
        client.start()
    }

    fun stopBot(id: Long) {
        runCatching { clients.remove(id)?.stop() }
        val state = statusOf(id)
        state.status = WeixinBotStatus.OFFLINE; state.lastError = "已停止"
    }

    fun restartBot(id: Long) {
        val row = db?.getWeixinBotById(id) ?: return
        startBot(row)
    }

    fun getQrCode(): Pair<String, String> = api.fetchQrCode()

    /**
     * ★ v69b 扫码确认闭环：轮询二维码直到 confirmed，拿到 bot_token/ilink_bot_id 后自动保存并启动
     * @return 结果文本（成功/失败原因）
     */
    fun confirmQrLogin(qrcode: String, timeoutMs: Long = 300000): String {
        val d = db ?: return "❌ 数据库未就绪"
        val r = api.waitQrConfirmed(qrcode, timeoutMs) ?: return "⏳ 等待扫码超时（5分钟），请重新生成二维码"
        val status = r.optString("status")
        if (status == "expired") return "❌ 二维码已过期，请重新生成"
        if (status == "binded_redirect") return "ℹ️ 该微信 bot 已连接过，无需重复扫码"
        if (status == "need_verifycode") return "⚠️ 需要输入配对码（手机微信显示的 4 位数字），当前版本请稍后重试"
        val botToken = r.optString("bot_token")
        val botId = r.optString("ilink_bot_id")
        if (botToken.isBlank() || botId.isBlank()) return "❌ 登录确认失败（未返回 bot_token/ilink_bot_id）"
        // 已存在同名 bot 则更新，否则新建
        val existing = d.getWeixinBots().firstOrNull { it.ilinkBotId == botId }
        val bot = if (existing != null) existing.copy(botToken = botToken, enabled = true)
        else com.qitong.gateway.weixin.WeixinBot(
            name = "微信bot-" + botId.substringBefore("@"),
            botToken = botToken, ilinkBotId = botId, enabled = true
        )
        d.saveWeixinBot(bot)
        startBot(bot)
        return "✅ 微信 bot 扫码连接成功！\n🆔 bot_id: $botId\n📊 已自动保存并启动长轮询\n\n现在微信里发「绑定账号 用户名 密码」即可管理网关"
    }

    // ============ 消息分发 ============

    private fun handleMessage(bot: WeixinBot, msg: WeixinMessage) {
        val d = db ?: return
        val t = msg.content.trim()
        if (t.isBlank()) return
        val t0 = System.currentTimeMillis()
        val send = { text: String -> api.sendMessage(bot.botToken, msg.from, text, msg.contextToken) }

        // 绑定流程（pendingBind 分步）
        val bindState = pendingBind[msg.from]
        if (bindState != null && !t.startsWith("绑定账号", true) && t != "取消") {
            val (step, saved) = bindState
            if (step == 1) { pendingBind[msg.from] = 2 to t; send("📝 用户名已收到「$t」\n第 2 步：请发送你的【账号密码】"); return }
            if (step == 2) {
                val r = com.qitong.gateway.auth.AuthManager.login(d, saved, t)
                if (r.isSuccess) {
                    val u = d.getUserByUsername(saved.trim()) ?: run { pendingBind.remove(msg.from); send("❌ 账号校验失败"); return }
                    d.setWeixinUserBound(msg.from, u.id)
                    if (u.role == "admin" || u.role == "agent") d.setWeixinUserPerm(msg.from, 3)
                    pendingBind.remove(msg.from)
                    send("✅ 微信绑定成功！已登录账号「${u.username}」角色 ${u.role}，可查余额/管理网关")
                } else { pendingBind.remove(msg.from); send("❌ 登录失败：${r.exceptionOrNull()?.message ?: "用户名或密码错误"}") }
                return
            }
        }

        // 绑定码
        if (t.startsWith("绑定码 ", true)) {
            val code = t.substringAfter(" ").trim()
            val cfg = d.getConfig("qq_bind_code_$code", "")
            if (cfg.isBlank()) { send("❌ 绑定码无效或已使用"); return }
            val parts = cfg.split("|"); val userId = parts.getOrNull(0)?.toLongOrNull() ?: 0
            val expireAt = parts.getOrNull(1)?.toLongOrNull() ?: 0
            if (userId <= 0 || System.currentTimeMillis() > expireAt) { d.setConfig("qq_bind_code_$code", ""); send("❌ 绑定码已过期"); return }
            val u = d.getUserById(userId) ?: run { send("❌ 绑定账号不存在"); return }
            d.setWeixinUserBound(msg.from, u.id)
            if (u.role == "admin" || u.role == "agent") d.setWeixinUserPerm(msg.from, 3)
            d.setConfig("qq_bind_code_$code", "")
            send("✅ 绑定成功！已登录账号「${u.username}」")
            return
        }

        // 管理指令（白名单 + 权限校验）
        val bound = d.getWeixinBoundUser(msg.from)
        val isAdmin = bound?.let { it.role == "admin" || it.role == "agent" } == true ||
            d.getWeixinUserPerm(msg.from) >= 3
        when {
            t == "绑定账号" -> { pendingBind[msg.from] = 1 to ""; send("🔐 开始绑定网关账号\n第 1 步：请发送你的【账号用户名】"); return }
            t == "我的账号" -> {
                if (bound == null) send("🔓 当前未绑定网关账号\n发「绑定账号」或网页生成绑定码后发「绑定码 123456」")
                else send("👤 绑定账号：${bound.username}\n💎 角色: ${bound.role}\n💰 余额: ¥${"%.2f".format(d.getUserBalance(bound.id))}")
                return
            }
            t == "退出账号" || t == "解绑" -> { d.clearWeixinUserBound(msg.from); send("✅ 已解绑"); return }
            t == "网关状态" || t == "状态" || t.startsWith("查状态") -> {
                val r = kotlinx.coroutines.runBlocking { com.qitong.gateway.http.SkillExecutor.execute(d, "600001", "", bound?.id ?: 0L) }
                send(r); return
            }
            t == "我的余额" || t == "查余额" -> {
                val r = if (bound != null) com.qitong.gateway.sandbox.SandboxEngine.execute("user_balance", emptyMap(), isAdmin, bound.id, d)
                else "🔓 未绑定账号，先发「绑定账号」"
                send(r); return
            }
            t == "体检" || t.contains("全部功能") || t.contains("在线状态") -> {
                val r = com.qitong.gateway.sandbox.SandboxEngine.execute("sys_health", emptyMap(), isAdmin, bound?.id ?: 0L, d)
                send(r); return
            }
            t.startsWith("停止") || t == "停" || t == "中断" -> { send("🛑 已停止当前任务"); return }
            t.startsWith("管理") && !isAdmin -> { send("⛔ 仅管理员可执行管理指令"); return }
            t.startsWith("管理 ") && isAdmin -> {
                val cmd = t.substringAfter(" ").trim()
                when {
                    cmd == "状态" || cmd == "status" -> send("✅ 网关运行中（微信通道在线）")
                    cmd == "机器人" -> send("🤖 微信机器人运行中")
                    else -> send("📋 管理指令示例：\n管理 状态 / 管理 机器人 / 管理 重启\n（更多能力开发中）")
                }
                return
            }
        }

        // AI 对话（loopback 网关大脑；冷却 3s）
        val last = lastReplyTs[msg.from] ?: 0L
        if (System.currentTimeMillis() - last < 3000) return
        lastReplyTs[msg.from] = System.currentTimeMillis()
        send("🤔 正在思考…")
        val reply = askModel(bot, msg, t, bound?.id ?: 0L)
        if (!reply.isNullOrBlank()) send(reply) else send("⚠️ 抱歉，我这边处理失败了，请再试一次")
        d.addWeixinLog(bot.id, msg.from, "ai", t, System.currentTimeMillis() - t0)
    }

    /** loopback 调用网关大脑（qtai-sj 走沙盒 Agent 循环，其他模型走普通对话） */
    private fun askModel(bot: WeixinBot, msg: WeixinMessage, userText: String, userId: Long): String? {
        val d = db ?: return null
        val sandboxOn = bot.aiModel.equals("qtai-sj", true)
        val baseSys = if (sandboxOn) com.qitong.gateway.sandbox.SandboxEngine.SYSTEM_PROMPT
        else bot.systemPrompt.ifBlank { "你是綦桐小助理，运行在綦桐AI网关微信渠道。" }
        val body = JSONObject()
            .put("model", bot.aiModel)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", baseSys))
                .put(JSONObject().put("role", "user").put("content", userText)))
            .put("stream", false)
            .put("temperature", 0.8)
            .toString()
        return try {
            val req = Request.Builder()
                .url("http://127.0.0.1:$gatewayPort/v1/chat/completions")
                .addHeader("Content-Type", "application/json")
                .post(body.toRequestBody(jsonCt))
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                var content = JSONObject(resp.body?.string().orEmpty())
                    .optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                    ?.optString("content")?.trim().orEmpty()
                // qtai-sj 沙盒 Agent 循环（单轮工具执行，结果回填）
                if (sandboxOn && content.isNotBlank()) {
                    val isAdmin = d.getWeixinUserPerm(msg.from) >= 3 ||
                        d.getWeixinBoundUser(msg.from)?.let { it.role == "admin" || it.role == "agent" } == true
                    val calls = com.qitong.gateway.sandbox.SandboxEngine.parseCalls(content)
                    if (calls.isNotEmpty()) {
                        val sb = StringBuilder()
                        for ((fn, args) in calls) {
                            val r = com.qitong.gateway.sandbox.SandboxEngine.execute(fn, args, isAdmin, userId, d)
                            sb.append("【$fn 执行结果】\n$r\n")
                        }
                        content = cleanFunctionTags(content) + "\n\n" + sb.toString().trim()
                    }
                }
                content
            }
        } catch (e: Exception) { null }
    }

    /** 清洗回复里的函数调用标签（防 XML/JSON 泄漏给用户，对齐 QQ 侧逻辑） */
    private fun cleanFunctionTags(text: String): String {
        var s = text
        // [[沙盒:fn(args)]]
        s = s.replace(Regex("\\[\\[沙盒:[^\\]]+\\]\\]"), "")
        // <dots_function_call ...>...</dots_function_call> / <function_call> / <invoke> 完整对
        s = s.replace(Regex("<(?:dots_function_call|function_call|invoke)[^>]*>[\\s\\S]*?</(?:dots_function_call|function_call|invoke)>"), "")
        // 单标签 <dots_function_call name="x"/> / <function_call>（无配对）
        s = s.replace(Regex("<(?:dots_function_call|function_call|invoke)\\s+name=\"[^\"]*\"\\s*/?>"), "")
        // 残留的 JSON 函数调用 {name:..., arguments:{...}}
        s = s.replace(Regex("\\{\\\\?\"name\\\\?\"\\\\?\\s*[:：][^}]*\\}"), "")
        return s.trim()
    }
}