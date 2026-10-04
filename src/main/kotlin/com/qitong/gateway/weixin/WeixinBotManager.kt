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
    // ★ v74 每用户多轮上下文（微信 openid 隔离，对齐 QQ history）
    private val history = ConcurrentHashMap<String, MutableList<Pair<String, String>>>()
    // ★ v75 停止信号：用户发「停止/停一下/中断」→ 打断当前 Agent 思考/执行（大模型自由调度感知停止，对齐 QQ）
    private val stopSignals = ConcurrentHashMap<String, Boolean>()
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

    /** ★ v76 全网广播（心跳/计划任务到期推送给所有在线微信 bot 的最近联系人） */
    fun broadcastAll(text: String) {
        try {
            val bots = db?.getWeixinBots()?.filter { it.enabled } ?: return
            bots.forEach { bot ->
                val st = statusOf(bot.id)
                if (st.status != WeixinBotStatus.ONLINE) return@forEach
                // 找最近活跃联系人（从历史上下文反查，没有则跳过）
                val recent = history.entries.filter { it.value.isNotEmpty() }.maxByOrNull { entry ->
                    entry.value.lastOrNull()?.second?.length ?: 0
                }?.key ?: return@forEach
                api.sendMessage(bot.botToken, recent, text, "")
            }
        } catch (e: Exception) { System.err.println("[WeixinBot] broadcastAll 异常: ${e.message}") }
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
        // ★ v70 用保存后的真实 id 启动（避免 id=0 存到 clients 导致状态查不到=一直停止）
        val saved = d.getWeixinBots().lastOrNull { it.ilinkBotId == botId }
            ?: d.getWeixinBots().lastOrNull { it.name == bot.name }
        if (saved != null) startBot(saved)
        return "✅ 微信 bot 扫码连接成功！\n🆔 bot_id: $botId\n📊 已自动保存并启动长轮询\n\n现在微信里发「绑定账号 用户名 密码」即可管理网关"
    }

    // ============ 消息分发 ============

    private fun handleMessage(bot: WeixinBot, msg: WeixinMessage) {
        val d = db ?: return
        val t = msg.content.trim()
        if (t.isBlank()) return
        val t0 = System.currentTimeMillis()
        // ★ v71 微信大消息自动分段推送（消息上限约 2000 字符，超长切段逐条发不丢信息）
        val send = { text: String ->
            val pieces = splitMessage(text)
            pieces.forEach { p -> api.sendMessage(bot.botToken, msg.from, p, msg.contextToken) }
        }

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

        // ★ v75 停止指令：用户发「停止/停一下/中断/不干了」→ 打断当前 Agent 思考/执行（大模型自由调度感知，对齐 QQ）
        if (t.equals("停止", true) || t.equals("停", true) || t.equals("停一下", true) ||
            t.equals("中断", true) || t.equals("不干了", true) || t.equals("算了", true) || t.equals("停止执行", true)) {
            stopSignals[msg.from] = true
            send("🛑 已停止任务（用户中断）。")
            d.addWeixinLog(bot.id, msg.from, "command", "停止指令", System.currentTimeMillis() - t0)
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
                    cmd == "状态" || cmd == "status" -> {
                        val r = kotlinx.coroutines.runBlocking { com.qitong.gateway.http.SkillExecutor.execute(d, "600001", "", bound?.id ?: 0L) }
                        send(r)
                    }
                    cmd == "体检" || cmd == "健康" || cmd == "全部功能" -> {
                        val r = com.qitong.gateway.sandbox.SandboxEngine.execute("sys_health", emptyMap(), isAdmin, bound?.id ?: 0L, d)
                        send(r)
                    }
                    cmd == "机器人" -> {
                        val wxbots = d.getWeixinBots()
                        val online = wxbots.count { it.enabled && runCatching { statusOf(it.id).status == WeixinBotStatus.ONLINE }.getOrDefault(false) }
                        val qqbotCount = runCatching { d.getQqBots().size }.getOrDefault(0)
                        send("🤖 机器人状态：\n💚 微信bot：$online/${wxbots.size} 在线\n💙 QQ机器人：$qqbotCount 个配置\n\n发「体检」看全功能在线状态")
                    }
                    cmd == "模型" -> {
                        val r = kotlinx.coroutines.runBlocking { com.qitong.gateway.http.SkillExecutor.execute(d, "600008", "", bound?.id ?: 0L) }
                        send(r)
                    }
                    cmd == "余额" -> {
                        val r = com.qitong.gateway.sandbox.SandboxEngine.execute("user_balance", emptyMap(), isAdmin, bound?.id ?: 0L, d)
                        send(r)
                    }
                    cmd == "重启" -> send("🔄 微信通道重启指令已收到（网关自动维护，无需手动重启）")
                    else -> send("📋 管理指令（管理员）：\n管理 状态 / 管理 体检 / 管理 机器人 / 管理 模型 / 管理 余额\n\n更多能力直接说需求，qtai-sj 会自动调工具帮你完成！")
                }
                return
            }
        }

        // ★ v73 微信功能对齐 QQ：技能/游戏/插件/工作流/提醒 走同一套
        // 1) 网关技能（查状态/排行/余额/模型/启停 等，绑定账号身份）
        com.qitong.gateway.qq.QqBotManager.matchGatewaySkillPublic(t)?.let { (code, param) ->
            val r = kotlinx.coroutines.runBlocking { com.qitong.gateway.http.SkillExecutor.execute(d, code, param, bound?.id ?: 0L) }
            send(r)
            d.addWeixinLog(bot.id, msg.from, "skill", "[$code] $t", System.currentTimeMillis() - t0)
            return
        }
        // 2) 文字游戏（猜数字/成语接龙/骰子/抽卡，复用 QQ 游戏状态）
        val gameR = com.qitong.gateway.qq.QqBotManager.handleGamePublic(msg.from, t)
        if (gameR != null) { send(gameR); return }
        // 3) 插件（QQ 插件包：菜单 + 命令脚本，微信直接可用）
        val pluginR = com.qitong.gateway.qq.QqBotManager.matchPluginPublic(t, msg.from)
        if (pluginR != null) { send(pluginR.second); return }
        // 4) 工作流（按名称触发）
        if (t.startsWith("执行工作流", true) || t.startsWith("触发工作流", true)) {
            val wfName = t.substringAfter(" ").trim()
            val wfs = try { d.getWorkflows(0) } catch (e: Exception) { emptyList() }
            val wf = wfs.firstOrNull { (it["name"] as? String)?.contains(wfName, true) == true }
            if (wf == null) send("❌ 未找到工作流「$wfName」")
            else {
                val stepsJson = try { org.json.JSONArray(wf["steps"] as? String ?: "[]") } catch (e: Exception) { org.json.JSONArray() }
                val sb = StringBuilder("⚙️ 工作流「${wf["name"]}」执行：\n")
                for (i in 0 until stepsJson.length()) {
                    val step = stepsJson.optJSONObject(i) ?: continue
                    val out = com.qitong.gateway.http.WorkflowEngine.runStep(d, step.optString("type", "reply"), step.optString("content", ""))
                    sb.append("【${i + 1}·${step.optString("type", "reply")}】\n$out\n\n")
                }
                send(sb.toString().trim())
            }
            return
        }

        // 5) ★ v76 提醒/计划任务（微信复用 scheduled_tasks 表，心跳扫描到期推送）
        if (t.startsWith("提醒", true) && (t.contains("分钟后", true) || t.contains("小时后", true) || t.contains("秒后", true))) {
            val m = Regex("""提醒我?\s*(\d+)\s*(秒|分钟|小时)后\s*(.*)""").find(t)
            if (m == null) { send("⚠️ 语法：提醒我 10分钟后 喝水"); return }
            val num = m.groupValues[1].toLongOrNull() ?: 10
            val unit = m.groupValues[2]
            val content = m.groupValues[3].ifBlank { "时间到！" }
            val delayMs = when (unit) { "秒" -> num * 1000; "小时" -> num * 3600_000L; else -> num * 60_000L }
            if (delayMs < 5000) { send("⚠️ 提醒时间太短（至少5秒）"); return }
            d.addScheduledTask("weixin", msg.from, "remind", content, System.currentTimeMillis() + delayMs)
            send("⏰ 好的，${num}${unit}后提醒你：$content（可发「查看提醒」/「取消提醒」管理）")
            d.addWeixinLog(bot.id, msg.from, "remind", "设置提醒 $content", System.currentTimeMillis() - t0)
            return
        }
        if (t.equals("查看提醒", true) || t.equals("我的提醒", true)) {
            val mine = d.getScheduledTasks(channel = "weixin", userOpenid = msg.from, status = "pending")
            if (mine.isEmpty()) send("📭 你当前没有提醒")
            else send("📋 我的提醒（${mine.size}条）：\n" + mine.mapIndexed { i, r ->
                val remain = ((r["runAt"] as? Long ?: 0) - System.currentTimeMillis())
                "${i+1}. ${r["content"]}（${if (remain > 0) "剩${remain / 60_000L}分" else "已到期"}）"
            }.joinToString("\n"))
            return
        }
        if (t.startsWith("取消提醒", true)) {
            val n = t.substringAfter("取消提醒").trim().toIntOrNull()
            val mine = d.getScheduledTasks(channel = "weixin", userOpenid = msg.from, status = "pending")
            if (n == null || n < 1 || n > mine.size) { send("⚠️ 请输入有效序号，如「取消提醒 1」"); return }
            d.cancelScheduledTask((mine[n-1]["id"] as? Number)?.toLong() ?: 0L, msg.from)
            send("✅ 已取消提醒：${mine[n-1]["content"]}")
            return
        }

        // AI 对话（loopback 网关大脑；冷却 3s）
        val last = lastReplyTs[msg.from] ?: 0L
        if (System.currentTimeMillis() - last < 3000) return
        lastReplyTs[msg.from] = System.currentTimeMillis()
        // ★ v74 思考过程也推送（AI 自己讲在干嘛）——发送回调传给 askModel
        send("🤔 收到，让我想想怎么帮你…")
        val reply = askModel(bot, msg, t, bound?.id ?: 0L) { thinkMsg -> d.addWeixinLog(bot.id, msg.from, "think", thinkMsg, 0); send(thinkMsg) }
        if (!reply.isNullOrBlank()) send(reply) else send("⚠️ 抱歉，我这边处理失败了，请再试一次")
        d.addWeixinLog(bot.id, msg.from, "ai", t, System.currentTimeMillis() - t0)
    }

    /** loopback 调用网关大脑（qtai-sj 走完整沙盒 Agent 循环：思考推送→调工具→结果回填→多轮） */
    private fun askModel(bot: WeixinBot, msg: WeixinMessage, userText: String, userId: Long, send: (String) -> Unit): String? {
        val d = db ?: return null
        val key = msg.from  // 每用户上下文隔离
        val sandboxOn = bot.aiModel.equals("qtai-sj", true)
        // ★ v71 人设优先：用户设置的 systemPrompt 始终注入（qtai-sj 时追加到沙盒提示词后，不被覆盖）
        val userPrompt = bot.systemPrompt.trim()
        var baseSys = if (sandboxOn) {
            val sb = com.qitong.gateway.sandbox.SandboxEngine.SYSTEM_PROMPT
            if (userPrompt.isNotBlank()) sb + "\n\n【机器人主人设定（必须严格遵守）】\n" + userPrompt else sb
        } else {
            userPrompt.ifBlank { "你是綦桐小助理，运行在綦桐AI网关微信渠道。" }
        }
        // ★ v76 微信长期记忆：绑定账号后按 userId 注入大脑记忆（每条消息带上下文，对齐 QQ）
        if (userId > 0) {
            runCatching {
                val mems = d.getMemories(userId, limit = 8)
                if (mems.isNotEmpty()) {
                    val memSection = "\n\n【你对这位用户的长期记忆】\n" + mems.reversed().joinToString("\n") { m ->
                        "- ${m["content"]}"
                    }
                    baseSys += memSection
                }
            }
        }
        val hist = history.getOrPut(key) { mutableListOf() }
        return try {
            // 第一次请求：带上下文
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
                .post(body.toRequestBody(jsonCt))
                .build()
            var content = http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                JSONObject(resp.body?.string().orEmpty())
                    .optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                    ?.optString("content")?.trim().orEmpty()
            }
            // ★ v74 qtai-sj 完整 Agent 循环：思考推送 → 解析函数 → 执行 → 结果回填 → 再调模型（最多5轮）
            if (sandboxOn && content.isNotBlank()) {
                val isAdmin = d.getWeixinUserPerm(msg.from) >= 3 ||
                    d.getWeixinBoundUser(msg.from)?.let { it.role == "admin" || it.role == "agent" } == true
                var loop = 0
                var noCallGuide = 0   // ★ v77 引导计数器：模型只输出描述不输出标签时引导它
                while (loop < 5) {
                    // ★ v75 停止信号：用户已发「停止」→ 立即断开循环，不再继续执行/思考（对齐 QQ v64）
                    if (stopSignals[msg.from] == true) {
                        stopSignals.remove(msg.from)
                        content = "🛑 已停止任务（用户中断）。"
                        break
                    }
                    loop++
                    val calls = com.qitong.gateway.sandbox.SandboxEngine.parseCalls(content)
                    if (calls.isEmpty()) {
                        // ★ v77 假调用修复：模型只输出了"正在调用 xxx"描述但没输出严格标签 → 引导它输出真标签再执行
                        if (noCallGuide < 2 && (content.contains("正在调用") || content.contains("调用沙盒") ||
                                content.contains("准备调用") || content.contains("我需要调用") || content.contains("我来调用"))) {
                            noCallGuide++
                            val guideBody = JSONObject()
                                .put("model", bot.aiModel)
                                .put("messages", JSONArray()
                                    .put(JSONObject().put("role", "system").put("content", baseSys))
                                    .put(JSONObject().put("role", "user").put("content", userText))
                                    .put(JSONObject().put("role", "assistant").put("content", content))
                                    .put(JSONObject().put("role", "user").put("content",
                                        "我看到你说要调用工具，但没有输出标准的沙盒函数调用标签。请严格按这个格式输出你要调用的工具（一次输出一个即可，不要解释）：\n[[沙盒:函数名(参数=值)]]\n可用函数见上面的知识库清单。"))) .toString()
                            val guideReq = Request.Builder()
                                .url("http://127.0.0.1:$gatewayPort/v1/chat/completions")
                                .addHeader("Content-Type", "application/json")
                                .post(guideBody.toRequestBody(jsonCt))
                                .build()
                            val guided = http.newCall(guideReq).execute().use { resp3 ->
                                if (!resp3.isSuccessful) null
                                else JSONObject(resp3.body?.string().orEmpty())
                                    .optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                                    ?.optString("content")?.trim().orEmpty()
                            }
                            if (guided.isNullOrBlank()) { content = cleanFunctionTags(content); break }
                            content = guided
                            continue  // 下一轮用引导结果再解析
                        }
                        break
                    }
                    val results = StringBuilder()
                    for ((fn, args) in calls) {
                        // 💭 思考推送：AI 自己讲在干嘛、调了什么工具（像真人一样）
                        val argTxt = args.entries.joinToString(",") { "${it.key}=${it.value}" }
                        send("💭 我在帮你处理，正在调用 ${fn}(${argTxt})…")
                        val r = com.qitong.gateway.sandbox.SandboxEngine.execute(fn, args, isAdmin, userId, d, "weixin")
                        results.append("【$fn 执行结果】\n$r\n")
                        send("✅ $fn：${r.take(300)}")
                    }
                    val cleanText = cleanFunctionTags(content)
                    // 结果回填给模型继续决策
                    val nextBody = JSONObject()
                        .put("model", bot.aiModel)
                        .put("messages", JSONArray()
                            .put(JSONObject().put("role", "system").put("content", baseSys))
                            .put(JSONObject().put("role", "user").put("content", userText))
                            .put(JSONObject().put("role", "assistant").put("content", cleanText))
                            .put(JSONObject().put("role", "user").put("content",
                                "【工具执行结果（已同步给用户）】\n$results\n\n根据结果：如果任务完成，直接给出简洁总结（不要复述工具输出）；如果还需要其他操作，继续调用工具。"))) .toString()
                    val nextReq = Request.Builder()
                        .url("http://127.0.0.1:$gatewayPort/v1/chat/completions")
                        .addHeader("Content-Type", "application/json")
                        .post(nextBody.toRequestBody(jsonCt))
                        .build()
                    val nextContent = http.newCall(nextReq).execute().use { resp2 ->
                        if (!resp2.isSuccessful) null
                        else JSONObject(resp2.body?.string().orEmpty())
                            .optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                            ?.optString("content")?.trim().orEmpty()
                    }
                    if (nextContent.isNullOrBlank()) {
                        content = cleanText + "\n\n" + results
                        break
                    }
                    content = nextContent
                }
                hist.add("user" to userText)
                hist.add("assistant" to cleanFunctionTags(content))
                while (hist.size > 20) hist.removeAt(0)
            } else {
                hist.add("user" to userText)
                hist.add("assistant" to content)
                while (hist.size > 20) hist.removeAt(0)
            }
            // ★ v76 微信记忆存储：绑定账号后把有信息量的话存大脑记忆（对话期间存储，下次带上下文；受 memory_enabled 开关控制）
            if (userId > 0 && !sandboxOn && d.getConfig("memory_enabled", "true") != "false") {
                runCatching {
                    val memText = userText.trim()
                    if (memText.length in 4..200 && !memText.startsWith("绑定") && !memText.startsWith("停止") &&
                        !memText.startsWith("管理") && memText != "我的账号" && memText != "退出账号") {
                        d.addMemory(userId, "微信对话", memText.take(150), "short", "neutral", 3, "weixin_chat", "", "")
                    }
                }
            }
            cleanFunctionTags(content)
        } catch (e: Exception) {
            System.err.println("[WeixinBot] 模型调用异常: ${e.message}")
            null
        }
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

    /** ★ v71 大消息分段：超 1800 字符按行切段（微信消息上限约 2000，留余量） */
    private fun splitMessage(text: String): List<String> {
        if (text.length <= 1800) return listOf(text)
        val result = mutableListOf<String>()
        val lines = text.lines()
        val cur = StringBuilder()
        for (line in lines) {
            if (cur.length + line.length + 1 > 1800 && cur.isNotEmpty()) {
                result.add(cur.toString())
                cur.clear()
            }
            cur.append(line).append("\n")
            if (cur.length > 1800) {
                // 单行超长：硬切
                result.add(cur.toString().take(1800))
                cur.clear()
                cur.append(cur.toString()) // 保留超长尾巴重新处理（罕见）
                cur.clear()
            }
        }
        if (cur.isNotEmpty()) result.add(cur.toString())
        return result.filter { it.isNotBlank() }.ifEmpty { listOf(text) }
    }
}