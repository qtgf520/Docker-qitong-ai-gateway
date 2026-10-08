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
    @Volatile private var watchdogStarted = false

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        // ★ v1.104 总超时 40s：模型调用超时快速返回提示，防"说执行不返回"卡顿
        // ★ v1.116 单轮提高到 90s：允许模型长思考；停止靠 activeCall.cancel() 即时打断，不再依赖超时
        .callTimeout(120, TimeUnit.SECONDS)
        .build()
    private val jsonCt = "application/json; charset=utf-8".toMediaType()
    // ★ v1.116 当前活动模型调用（按用户），发「停止」立即 cancel() 打断，不再等跑完
    private val activeCalls = ConcurrentHashMap<String, okhttp3.Call>()
    // ★ v1.116 每用户当前任务 Job：新消息（非停止）立即取消旧任务 → 无缝接上下文继续干（不间断追问）
    private val userJobs = ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    fun init(database: Database, port: Int) { db = database; gatewayPort = port }

    fun statusOf(id: Long): WeixinRuntimeState = runtime.getOrPut(id) { WeixinRuntimeState() }
    fun allStatus(): Map<Long, WeixinRuntimeState> = runtime.toMap()

    fun startAll(database: Database, port: Int) {
        init(database, port)
        runCatching { database.getWeixinBots().filter { it.enabled }.forEach { startBot(it) } }
        // ★ v1.110 看门狗：每 60 秒检查掉线 bot 自动拉起——修复「重启镜像才能登上」
        if (!watchdogStarted) {
            watchdogStarted = true
            Thread {
                while (true) {
                    try {
                        Thread.sleep(60_000L)
                        val bots = db?.getWeixinBots()?.filter { it.enabled } ?: continue
                        for (b in bots) {
                            val st = statusOf(b.id)
                            if (st.status == WeixinBotStatus.ERROR || st.status == WeixinBotStatus.OFFLINE) {
                                println("[WeixinBot] ${b.name} 掉线自动重启（status=${st.status} lastErr=${st.lastError}）")
                                runCatching { startBot(b) }
                            }
                        }
                    } catch (_: InterruptedException) { break }
                    catch (_: Exception) { }
                }
            }.apply { isDaemon = true; name = "weixin-watchdog"; start() }
        }
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
        // ★ v87 有 token 即先标记连接中（不再一直 OFFLINE，长轮询一成功即 ONLINE 且稳定保持）
        state.status = WeixinBotStatus.CONNECTING; state.lastError = "启动中"
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
        // ★ v87 消息动态数：每收到一条真实消息就 +1（后台/动态预览可见）
        runCatching {
            val st = statusOf(bot.id)
            st.messagesHandled += 1
        }
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
            // ★ v1.116 即时打断：取消当前模型调用 + 取消旧任务 Job（不再等跑完/等超时）
            activeCalls[msg.from]?.cancel()
            activeCalls.remove(msg.from)
            userJobs.remove(msg.from)?.cancel()
            send("🛑 已停止任务（用户中断）。")
            d.addWeixinLog(bot.id, msg.from, "command", "停止指令", System.currentTimeMillis() - t0)
            return
        }
        stopSignals[msg.from] = false
        // ★ v1.116 不间断追问：上一任务还在跑？新消息来了直接取消旧任务，立即接入上下文继续干（对齐 QQ）
        userJobs.remove(msg.from)?.cancel()

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
            // ★ v1.118 微信技能自管理（对齐 QQ）
            t.startsWith("添加技能", true) || t.startsWith("学习技能", true) || t.startsWith("新建技能", true) -> {
                val raw = t.substringAfter(" ").trim()
                if (raw.isBlank()) { send("⚠️ 语法：添加技能 技能名|触发词|回复内容\n（如：添加技能 打招呼|你好|嗨~ 很高兴见到你！）"); return }
                val parts = raw.split("|").map { it.trim() }
                if (parts.size < 3) { send("⚠️ 请用 | 分隔：添加技能 名称|触发词|回复内容"); return }
                val owner = bound?.id ?: 0L
                try {
                    d.saveSkill(null, parts[0].take(30), parts[1].take(30), "contains", "reply", parts[2].take(500), true, owner)
                    send("✅ 技能「${parts[0]}」已创建！\n触发词：${parts[1]}\n之后发「${parts[1]}」就会回复：${parts[2]}\n（发「我的技能」查看，发「删除技能 编号」删除）")
                } catch (e: Exception) { send("❌ 创建失败：${e.message}") }
                d.addWeixinLog(bot.id, msg.from, "skill", "添加技能 ${parts[0]}", System.currentTimeMillis() - t0)
                return
            }
            t.startsWith("删除技能", true) || t.startsWith("移除技能", true) -> {
                val id = t.substringAfter(" ").trim().toLongOrNull()
                if (id == null) { send("⚠️ 语法：删除技能 编号（发「我的技能」查编号）"); return }
                val owner = bound?.id ?: 0L
                val list = try { d.getSkills(owner) } catch (e: Exception) { emptyList<Map<String, Any?>>() }
                if (!list.any { (it["id"] as? Number)?.toLong() == id }) { send("⚠️ 技能 $id 不存在或不属于你"); return }
                d.deleteSkill(id)
                send("✅ 技能 $id 已删除")
                d.addWeixinLog(bot.id, msg.from, "skill", "删除技能 $id", System.currentTimeMillis() - t0)
                return
            }
            t.startsWith("停用技能", true) || t.startsWith("启用技能", true) -> {
                val id = t.substringAfter(" ").trim().toLongOrNull()
                if (id == null) { send("⚠️ 语法：停用技能 编号 或 启用技能 编号"); return }
                val owner = bound?.id ?: 0L
                val list = try { d.getSkills(owner) } catch (e: Exception) { emptyList<Map<String, Any?>>() }
                val row = list.firstOrNull { (it["id"] as? Number)?.toLong() == id }
                if (row == null) { send("⚠️ 技能 $id 不存在或不属于你"); return }
                val newState = (row["enabled"] as? Boolean) != true
                d.saveSkill(id, "", "", "", "", "", newState, owner)
                send("✅ 技能「${row["name"]}」已${if (newState) "启用" else "停用"}")
                d.addWeixinLog(bot.id, msg.from, "skill", "切换技能 $id -> $newState", System.currentTimeMillis() - t0)
                return
            }
            t == "我的技能" || t == "技能列表" || t == "查看技能" -> {
                val owner = bound?.id ?: 0L
                val list = try { d.getSkills(owner) } catch (e: Exception) { emptyList<Map<String, Any?>>() }
                if (list.isEmpty()) { send("📭 还没有技能。发「添加技能 名称|触发词|回复内容」即可创建自己的技能"); return }
                send("🧩 【你的技能 ${list.size} 个】\n" + list.take(30).joinToString("\n") { sk ->
                    "• [${sk["id"]}] ${sk["name"]}（触发：${sk["trigger"]}）${if ((sk["enabled"] as? Boolean) == true) "✅" else "⛔停用"}"
                } + "\n\n管理：删除技能 编号 / 停用技能 编号 / 启用技能 编号")
                d.addWeixinLog(bot.id, msg.from, "skill", "查看技能列表 ${list.size}个", System.currentTimeMillis() - t0)
                return
            }
            t == "体检" || t.contains("全部功能") || t.contains("在线状态") -> {
                val r = com.qitong.gateway.sandbox.SandboxEngine.execute("sys_health", emptyMap(), isAdmin, bound?.id ?: 0L, d)
                send(r); return
            }
            // ★ v1.114 微信功能快捷面板（对齐 QQ）
            t == "功能" || t == "功能菜单" || t == "快捷指令" || t == "帮助" || t == "怎么用" -> {
                val common = "🧰 常用功能（直接发指令即可）\n" +
                    "· 查余额 / 我的账号 — 账户信息\n" +
                    "· 网关状态 / 体检 — 网关与全功能健康\n" +
                    "· 提醒我 10分钟后 xxx — 定时提醒\n" +
                    "· 待办 / 添加待办 xxx — 待办管理\n" +
                    "· 切换人格 程序员/知心姐姐/翻译官/老师 — 多角色对话\n" +
                    "· 画图 xxx — AI 生成图片\n" +
                    "· 我的技能 / 添加技能 触发词|回复内容 — 技能自管理\n" +
                    "· 执行工作流 xxx — 触发自动化工作流"
                val adminExtra = if (isAdmin) "\n\n🔐 管理员专属：\n" +
                    "· 管理 状态 / 体检 / 机器人 / 模型 / 余额\n" +
                    "· AI终端 xxx — 自然语言执行终端命令\n" +
                    "· 充值 用户名 金额 / 扣款 用户名 金额 — 用户账务" else ""
                send(common + adminExtra + "\n\n💬 其他需求直接说，我（qtai-sj）会自己找工具帮你搞定～")
                return
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
        // 2) ★ v90 自定义技能自动触发（skill_learn 学会的技能：命中触发词直接执行）
        runCatching {
            val customSkills = d.getSkills(bound?.id ?: 0L)
            val hitSkill = customSkills.filter { (it["enabled"] as? Boolean) == true }.firstOrNull { s ->
                val trig = (s["trigger"] as? String)?.trim() ?: ""
                val mt = (s["matchType"] as? String) ?: "contains"
                trig.isNotBlank() && when (mt) {
                    "contains" -> t.contains(trig, true)
                    "regex" -> runCatching { Regex(trig, RegexOption.IGNORE_CASE).containsMatchIn(t) }.getOrDefault(false)
                    else -> t.equals(trig, true)
                }
            }
            if (hitSkill != null) {
                val content = (hitSkill["content"] as? String) ?: ""
                val action = (hitSkill["action"] as? String) ?: "reply"
                val skillUserId = bound?.id ?: 0L
                // ★ v94 修复：按动作类型真执行——skill编码/沙盒函数名走执行器，reply直接发文本，其他走工作流引擎（对齐 QQ）
                val result = when (action) {
                    "skill" -> {
                        if (content.matches(Regex("\\d{6}"))) {
                            kotlinx.coroutines.runBlocking { com.qitong.gateway.http.SkillExecutor.execute(d, content, "", skillUserId) }
                        } else {
                            com.qitong.gateway.sandbox.SandboxEngine.execute(content, emptyMap(), true, skillUserId, d)
                        }
                    }
                    "reply" -> content
                    else -> com.qitong.gateway.http.WorkflowEngine.runStep(d, action, content)
                }
                send((result ?: "").ifBlank { "✅ 技能执行完成" })
                d.addWeixinLog(bot.id, msg.from, "skill_custom", "命中「${hitSkill["name"]}」（$action）", System.currentTimeMillis() - t0)
                return
            }
        }
        // 3) 文字游戏（猜数字/成语接龙/骰子/抽卡，复用 QQ 游戏状态）
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
        // ★ v76 微信长期记忆：绑定账号后按 userId 注入大脑记忆；★v1.115 未绑定用户也按 openid 注入（对齐 QQ），不再「没记忆」
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
        } else {
            // ★ v1.115 未绑定账号：按 openid 走微信独立记忆（对齐 QQ 的 qq:{openid} 通道）
            runCatching {
                val mems = d.getWxBrainMemories(msg.from, limit = 8)
                if (mems.isNotEmpty()) {
                    baseSys += "\n\n【你对这位用户的长期记忆】\n" + mems.reversed().joinToString("\n") { "- " + it }
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
            // ★ v1.116 注册当前调用到 activeCalls（可被「停止」即时 cancel）
            val firstCall = http.newCall(req)
            activeCalls[msg.from] = firstCall
            var content = firstCall.execute().use { resp ->
                activeCalls.remove(msg.from)
                if (!resp.isSuccessful) return null
                JSONObject(resp.body?.string().orEmpty())
                    .optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                    ?.optString("content")?.trim().orEmpty()
            }
             // ★ v78 收敛标记：本轮是否真执行过工具（结果已实时推送，最终回复不再复述）
            var executedCalls = false
            var lastProgressAt = System.currentTimeMillis()
            // ★ v74 qtai-sj 完整 Agent 循环：思考推送 → 解析函数 → 执行 → 结果回填 → 再调模型
            // ★ v1.116 多思考不停息：轮数 5→20，只要模型还在输出函数调用就不停；干不完继续干，除非用户说停
            if (sandboxOn && content.isNotBlank()) {
                val isAdmin = d.getWeixinUserPerm(msg.from) >= 3 ||
                    d.getWeixinBoundUser(msg.from)?.let { it.role == "admin" || it.role == "agent" } == true
                var loop = 0
                var noCallGuide = 0   // ★ v77 引导计数器：模型只输出描述不输出标签时引导它
                while (loop < 20) {
                    // ★ v75 停止信号：用户已发「停止」→ 立即断开循环，不再继续执行/思考（对齐 QQ v64）
                    if (stopSignals[msg.from] == true) {
                        stopSignals.remove(msg.from)
                        content = "🛑 已停止任务（用户中断）。"
                        break
                    }
                    loop++
                    // ★ v1.116 长时间任务进度提示：每 30s 推一次「还在处理」，不刷屏但让用户知道没卡死
                    if (System.currentTimeMillis() - lastProgressAt > 30_000) {
                        send("⏳ 任务还在处理中（第 $loop 轮），请稍候…")
                        lastProgressAt = System.currentTimeMillis()
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
                    // ★ v82 并行工具执行（Hermes PARALLEL 理念落地）：独立调用并发跑，结果按序汇总
                    // ★ v1.104 进度增强：显示「第 N 步/共 M 步」让用户看到整体进度
                    val totalSteps = calls.size
                    if (calls.size <= 1) {
                        // 单调用直接执行（★v1.110 静默：不推过程，只回最终答案）
                        val (fn, args) = calls[0]
                        val r = com.qitong.gateway.sandbox.SandboxEngine.execute(fn, args, isAdmin, userId, d, "weixin")
                        results.append("【$fn 执行结果】\n$r\n")
                        executedCalls = true
                    } else {
                        // 多调用并行（★v1.110 静默：不推「并行批处理」过程，只回最终答案）
                        val executor = java.util.concurrent.Executors.newFixedThreadPool(calls.size.coerceAtMost(5))
                        try {
                            val futures = calls.map { (fn, args) ->
                                executor.submit<Pair<String, String>> {
                                    val r = com.qitong.gateway.sandbox.SandboxEngine.execute(fn, args, isAdmin, userId, d, "weixin")
                                    fn to r
                                }
                            }
                            futures.forEach { fut ->
                                try {
                                    val (fn, r) = fut.get(30, java.util.concurrent.TimeUnit.SECONDS)
                                    results.append("【$fn 执行结果】\n$r\n")
                                    executedCalls = true
                                } catch (e: Exception) {
                                    results.append("【工具执行异常】\n${e.message}\n")
                                }
                            }
                        } finally {
                            executor.shutdown()
                        }
                    }
                    val cleanText = cleanFunctionTags(content)
                    // 结果回填给模型继续决策（★v1.110 静默：结果未展示给用户，要求模型输出完整最终答案）
                    val nextBody = JSONObject()
                        .put("model", bot.aiModel)
                        .put("messages", JSONArray()
                            .put(JSONObject().put("role", "system").put("content", baseSys))
                            .put(JSONObject().put("role", "user").put("content", userText))
                            .put(JSONObject().put("role", "assistant").put("content", cleanText))
                            .put(JSONObject().put("role", "user").put("content",
                                "【工具执行结果（内部参考，尚未向用户展示）】\n$results\n\n请基于以上真实执行结果给用户输出**完整、自然、像真人**的最终答复：\n- 任务完成→直接输出最终答案（包含关键结果/数据/结论，不要用空话）\n- 还需更多操作→继续调用工具\n- 不要输出函数调用语法，只输出对用户说的话"))) .toString()
                    val nextReq = Request.Builder()
                        .url("http://127.0.0.1:$gatewayPort/v1/chat/completions")
                        .addHeader("Content-Type", "application/json")
                        .post(nextBody.toRequestBody(jsonCt))
                        .build()
                    val nextContent = try {
                        // ★ v1.116 内循环调用也注册 activeCall（停止可即时取消）
                        val nextCall = http.newCall(nextReq)
                        activeCalls[msg.from] = nextCall
                        try {
                            nextCall.execute().use { resp2 ->
                                if (!resp2.isSuccessful) null
                                else JSONObject(resp2.body?.string().orEmpty())
                                    .optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                                    ?.optString("content")?.trim().orEmpty()
                            }
                        } finally {
                            if (activeCalls[msg.from] === nextCall) activeCalls.remove(msg.from)
                        }
                    } catch (e: Exception) { null }
                    if (nextContent.isNullOrBlank()) {
                        content = cleanText + "\n\n" + results
                        break
                    }
                    content = nextContent
                }
                hist.add("user" to userText)
                hist.add("assistant" to cleanFunctionTags(content))
                while (hist.size > 20) hist.removeAt(0)
                // ★ v92 微信记忆闭环增强：qtai-sj（sandboxOn）模式也沉淀记忆（对齐 QQ 长期记忆；受 memory_enabled 开关控制）
                // ★v1.115 未绑定用户也按 openid 沉淀（对齐 QQ），绑定账号仍走账号记忆
                if (d.getConfig("memory_enabled", "true") != "false") {
                    runCatching {
                        val memText = userText.trim()
                        if (memText.length in 4..200 && !memText.startsWith("绑定") && !memText.startsWith("停止") &&
                            !memText.startsWith("管理") && memText != "我的账号" && memText != "退出账号") {
                            if (userId > 0) d.addMemory(userId, "微信对话", memText.take(150), "short", "neutral", 3, "weixin_chat", "", "")
                            else d.saveWxBrainMemory(msg.from, memText.take(200))
                        }
                    }
                }
            } else {
                hist.add("user" to userText)
                hist.add("assistant" to content)
                while (hist.size > 20) hist.removeAt(0)
            }
            // ★ v78 收敛：工具结果已实时推送（executedCalls=true）→ 不再返回模型复述结果的总结
            //   （对齐 QQ v60：过程推送已展示，最终只留简短收尾/AI 意见，不重复已推送结果）
            if (executedCalls) {
                // 让模型基于结果生成一句简短收尾（不重复数字/结果），失败用兜底文案
                try {
                    val closeBody = JSONObject()
                        .put("model", bot.aiModel)
                        .put("messages", JSONArray()
                            .put(JSONObject().put("role", "system").put("content", baseSys))
                            .put(JSONObject().put("role", "user").put("content", userText))
                            .put(JSONObject().put("role", "assistant").put("content", cleanFunctionTags(content)))
                            .put(JSONObject().put("role", "user").put("content",
                                "工具结果已经实时推送给用户了。现在请只回复一句简短的收尾或你的看法（不要复述刚才的结果/数字/数据，一句话即可）。"))) .toString()
                    val closeReq = Request.Builder()
                        .url("http://127.0.0.1:$gatewayPort/v1/chat/completions")
                        .addHeader("Content-Type", "application/json")
                        .post(closeBody.toRequestBody(jsonCt))
                        .build()
                    val closeContent = http.newCall(closeReq).execute().use { resp4 ->
                        if (!resp4.isSuccessful) null
                        else JSONObject(resp4.body?.string().orEmpty())
                            .optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                            ?.optString("content")?.trim().orEmpty()
                    }
                    if (closeContent.isNullOrBlank() || closeContent.length <= 2) {
                        "✅ 已完成，结果已实时推送。如需继续操作，直接告诉我～"
                    } else {
                        cleanFunctionTags(closeContent).ifBlank { "✅ 已完成，结果已实时推送。如需继续操作，直接告诉我～" }
                    }
                } catch (e: Exception) {
                    "✅ 已完成，结果已实时推送。如需继续操作，直接告诉我～"
                }
            } else {
                // ★ v76 微信记忆存储：绑定账号后把有信息量的话存大脑记忆（对话期间存储，下次带上下文；受 memory_enabled 开关控制）
                // ★v1.115 未绑定用户也按 openid 沉淀（对齐 QQ）
                if (!sandboxOn && d.getConfig("memory_enabled", "true") != "false") {
                    runCatching {
                        val memText = userText.trim()
                        if (memText.length in 4..200 && !memText.startsWith("绑定") && !memText.startsWith("停止") &&
                            !memText.startsWith("管理") && memText != "我的账号" && memText != "退出账号") {
                            if (userId > 0) d.addMemory(userId, "微信对话", memText.take(150), "short", "neutral", 3, "weixin_chat", "", "")
                            else d.saveWxBrainMemory(msg.from, memText.take(200))
                        }
                    }
                }
                cleanFunctionTags(content)
            }
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