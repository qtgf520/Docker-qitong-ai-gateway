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
    /** v51 分步绑定状态：openid -> (step, username)；step=1 等用户名，2 等密码 */
    private val pendingBind = ConcurrentHashMap<String, Pair<Int, String>>()

    // ============ 文字游戏状态（每用户独立） ============
    private val gameState = ConcurrentHashMap<String, MutableMap<String, Any>>()

    // ============ 提醒（每用户独立，内存定时） ============
    data class ReminderItem(
        val userOpenid: String,
        val groupOpenid: String,
        val content: String,
        val delayMs: Long,
        val bot: QqBot,
        val createdAt: Long = System.currentTimeMillis()
    ) {
        fun dueAt(): Long = createdAt + delayMs
        fun remainText(): String {
            val remain = (dueAt() - System.currentTimeMillis()).coerceAtLeast(0)
            return if (remain >= 3600_000L) "${remain / 3600_000L}小时${(remain % 3600_000L) / 60_000L}分"
            else "${remain / 60_000L}分${(remain % 60_000L) / 1000}秒"
        }
    }
    private val reminders = java.util.concurrent.CopyOnWriteArrayList<ReminderItem>()
    @Volatile private var reminderSchedulerStarted = false
    private fun ensureReminderScheduler() {
        if (reminderSchedulerStarted) return
        synchronized(this) {
            if (reminderSchedulerStarted) return
            reminderSchedulerStarted = true
            CoroutineScope(Dispatchers.IO).launch {
                while (true) {
                    kotlinx.coroutines.delay(5000)
                    val now = System.currentTimeMillis()
                    val due = reminders.filter { it.dueAt() <= now }
                    due.forEach { r ->
                        try {
                            val at = api.getAccessToken(r.bot)
                            if (!at.isNullOrBlank()) {
                                val target = if (r.groupOpenid.isNotBlank()) {
                                    api.sendGroupMessage(r.bot, at, r.groupOpenid, "⏰ 提醒 @${r.userOpenid.take(6)}：${r.content}", null)
                                } else false
                                db.addQqLog(r.bot.appid, r.groupOpenid, r.userOpenid, "remind", "提醒触发: ${r.content}", 0)
                            }
                        } catch (_: Exception) {}
                        reminders.remove(r)
                    }
                }
            }
        }
    }

    /** 游戏指令：开始猜数字 / 猜数字 50 / 开始成语接龙 / 成语 xxx / 骰子 / 抽卡 */
    private fun matchGame(text: String): String? {
        val t = text.trim()
        if (t.contains("猜数字", true) || t.startsWith("猜", true) && t.length <= 4) return "guess"
        if (t.contains("成语接龙", true) || t.startsWith("接龙", true)) return "idiom"
        if (t.contains("骰子", true) || t.contains("掷骰子", true)) return "dice"
        if (t.contains("抽卡", true) || t.contains("抽奖", true)) return "gacha"
        return null
    }

    private fun handleGame(userOpenid: String, text: String): String? {
        val t = text.trim()
        when (matchGame(text)) {
            "guess" -> {
                val st = gameState.getOrPut(userOpenid) { mutableMapOf() }
                val cur = st["guess"]
                if (t.contains("开始", true) || t.contains("重新", true) || cur == null) {
                    val num = (1..100).random()
                    st["guess"] = num
                    st["guessTries"] = 0
                    return "🎮 猜数字开始！已想好 1-100 的数，回复「猜 50」试猜～"
                }
                if (t.startsWith("猜", true)) {
                    val n = t.substringAfter("猜").trim().toIntOrNull() ?: return "⚠️ 请输入数字，如「猜 50」"
                    val target = st["guess"] as Int
                    val tries = ((st["guessTries"] as Int?) ?: 0) + 1
                    st["guessTries"] = tries
                    return when {
                        n < target -> "📉 小了！猜大点（已试 $tries 次）"
                        n > target -> "📈 大了！猜小点（已试 $tries 次）"
                        else -> { st.remove("guess"); "🎉 恭喜猜中 $target！用了 $tries 次。回复「猜数字」再来一局～" }
                    }
                }
                return "🎮 当前在猜数字，回复「猜 数字」继续"
            }
            "dice" -> {
                val d1 = (1..6).random(); val d2 = (1..6).random()
                val total = d1 + d2
                return if (total == 7 || total == 11) "🎲 掷出 $d1 + $d2 = $total 🎉 大赢！" else "🎲 掷出 $d1 + $d2 = $total"
            }
            "gacha" -> {
                val r = (1..100).random()
                val card = when {
                    r <= 3 -> "🌟 SSR 传说卡！"
                    r <= 15 -> "✨ SR 稀有卡"
                    r <= 45 -> "💎 R 卡"
                    else -> "📦 N 卡"
                }
                return "🃏 抽卡结果（概率 $r/100）：$card"
            }
            "idiom" -> {
                val st = gameState.getOrPut(userOpenid) { mutableMapOf() }
                val cur = st["idiom"] as? String
                if (t.contains("开始", true) || cur == null) {
                    val pool = arrayOf("一心一意", "二龙戏珠", "三阳开泰", "四海为家", "五谷丰登", "六六大顺", "七步成诗", "八面玲珑", "九九归一", "十全十美")
                    val pick = pool.random()
                    st["idiom"] = pick
                    st["idiomCount"] = 0
                    return "🧩 成语接龙开始！我先出：「$pick」——请用「$pick」最后一个字（谐音也可）接一个成语"
                }
                if (t.startsWith("成语", true)) {
                    val word = t.substringAfter("成语").trim()
                    val lastChar = (cur as String).takeLast(1)
                    if (!word.contains(lastChar) && !word.contains(if (lastChar == "一") "一" else lastChar)) {
                        return "❌ 接龙失败：需包含「$lastChar」字（谐音也可）。当前：「$cur」"
                    }
                    val cnt = ((st["idiomCount"] as Int?) ?: 0) + 1
                    st["idiom"] = word
                    st["idiomCount"] = cnt
                    return "✅ 接得好！「$word」（已接 $cnt 个）。继续：用「${word.takeLast(1)}」接～"
                }
                return "🧩 当前接龙：「$cur」，回复「成语 xxx」来接"
            }
        }
        return null
    }

    fun statusOf(id: Long): QqRuntimeState = runtime.getOrPut(id) { QqRuntimeState() }
    fun allStatus(): Map<Long, QqRuntimeState> = runtime.toMap()

    fun startAll(scope: CoroutineScope, database: Database, gatewayPort: Int) {
        this.db = database
        this.gatewayPort = gatewayPort
        // 启动提醒调度器（定时触发到点提醒）
        ensureReminderScheduler()
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
        // ★ 掉线自动重登监控：每 5 分钟检查所有已启用机器人，掉线（ERROR/OFFLINE 且重试后仍离线）自动重启
        scope.launch(Dispatchers.IO) {
            while (true) {
                kotlinx.coroutines.delay(5 * 60 * 1000L)
                runCatching {
                    val bots = database.getQqBots().filter { (it["enabled"] as? Boolean) == true }
                    bots.forEach { row ->
                        val b = botFromRow(row)
                        val st = statusOf(b.id)
                        val s = st.status
                        val lastErr = st.lastError
                        // ERROR/OFFLINE 说明网关已掉线且重连机制没救回来 → 强制重启（重新拉连接+换token）
                        if (s == QqBotStatus.ERROR || s == QqBotStatus.OFFLINE) {
                            println("[QQBot] ${b.appid} 掉线自动重登（status=$s lastErr=$lastErr）")
                            startBot(b)
                        }
                    }
                }.onFailure { e -> System.err.println("[QQBot] 掉线重登检查异常: ${e.message}") }
            }
        }
        // ★ v47 自主心跳（对齐 Kai Heartbeat）：每 30 分钟自检，发现问题推送到第一个启用机器人的第一个群
        runCatching {
            com.qitong.gateway.sandbox.HeartbeatEngine.onIssue = { msg ->
                runCatching {
                    val bots = database.getQqBots().filter { (it["enabled"] as? Boolean) == true }
                    if (bots.isNotEmpty()) {
                        val b = botFromRow(bots.first())
                        val at = api.getAccessToken(b)
                        if (!at.isNullOrBlank()) {
                            val groups = database.getQqGroups()
                            if (groups.isNotEmpty()) {
                                val gid = (groups.first()["groupOpenid"] as? String).orEmpty()
                                if (gid.isNotBlank()) api.sendGroupMessage(b, at, gid, msg, null)
                            }
                        }
                    }
                }
            }
            com.qitong.gateway.sandbox.HeartbeatEngine.start(database)
        }
    }

    private fun botFromRow(row: Map<String, Any?>): QqBot = QqBot(
        id = row["id"] as Long,
        appid = row["appid"] as String,
        token = row["token"] as? String ?: "",
        appSecret = row["appSecret"] as? String ?: "",
        useSandbox = (row["useSandbox"] as? Boolean) ?: false,
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
            },
            // ★ 掉线自动重登：重连时用 AppID+AppSecret 重新换 AccessToken，防止 token 过期后永远连不上
            onTokenRefresh = { api.getAccessToken(bot) }
        )
        clients[bot.id] = client
        // 新版鉴权：先换 AccessToken（AppID+AppSecret），失败则直接报错不连接
        CoroutineScope(Dispatchers.IO).launch {
            val at = api.getAccessToken(bot)
            if (at.isNullOrBlank()) {
                state.status = QqBotStatus.ERROR
                state.lastError = "获取AccessToken失败（检查 AppID/AppSecret/沙箱开关）"
                System.err.println("[QQBot] ${bot.appid} 获取AccessToken失败")
                return@launch
            }
            client.start(at)
        }
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
        // ★ 群隔离：用户按群登记（群内成员独立管理，私聊另算）
        db.touchQqUser(msg.memberOpenid, msg.groupOpenid, stripAt(msg.content).trim().take(20))
        runtime[bot.id]?.let { it.messagesHandled++; it.groupsSeen = (it.groupsSeen + 1).coerceAtLeast(1) }
        // 刷新 AccessToken（7200s 有效，这里简单每次调用前获取）
        val at = api.getAccessToken(bot) ?: run {
            System.err.println("[QQBot] ${bot.appid} 群消息处理：获取AccessToken失败")
            return
        }
        // 新群首次触达：若开启欢迎且填了欢迎语，自动下发
        if (isNewGroup) {
            val cfg = db.getQqGroupConfig(msg.groupOpenid)
            if (cfg != null && (cfg["welcomeEnabled"] as Boolean)) {
                val g = cfg["greeting"] as? String
                if (!g.isNullOrBlank()) api.sendGroupMessage(bot, at, msg.groupOpenid, g, null)
            }
        }

        val text = stripAt(msg.content).trim()
        if (text.isEmpty()) return

        val key = "g:${msg.groupOpenid}:${msg.memberOpenid}"
        withUserLock(key) {
            dispatch(bot, key, msg.groupOpenid, msg.memberOpenid, text, msg.msgId,
                send = { content -> api.sendGroupMessage(bot, at, msg.groupOpenid, content, msg.msgId) })
        }
    }

    private fun handleC2c(bot: QqBot, msg: QqC2cMessage) {
        // ★ 群隔离：私聊用户 group 为空串独立登记
        db.touchQqUser(msg.userOpenid, "", msg.content.trim().take(20))
        runtime[bot.id]?.let { it.messagesHandled++ }
        val at = api.getAccessToken(bot) ?: run {
            System.err.println("[QQBot] ${bot.appid} 私聊消息处理：获取AccessToken失败")
            return
        }
        val key = "c:${msg.userOpenid}"
        withUserLock(key) {
            dispatch(bot, key, "", msg.userOpenid, msg.content.trim(), msg.msgId,
                send = { content -> api.sendC2cMessage(bot, at, msg.userOpenid, content, msg.msgId) })
        }
    }

    private fun withUserLock(key: String, block: suspend () -> Unit) {
        val m = userMutex.getOrPut(key) { Mutex() }
        CoroutineScope(Dispatchers.IO).launch {
            m.withLock { runCatching { block() } }
        }
    }

    /** 统一分发：内置指令 > 网关技能 > 插件指令 > 大模型；全程记日志。 */
    private suspend fun dispatch(
        bot: QqBot, key: String, groupOpenid: String, userOpenid: String,
        text: String, msgId: String, send: (String) -> Boolean
    ) {
        val t0 = System.currentTimeMillis()
        val t = text.trim()
        // ★ v51 分步绑定处理：用户在绑定流程中（pendingBind 有值）发的任意消息 = 绑定输入
        val bindState = pendingBind[userOpenid]
        if (bindState != null && !t.startsWith("绑定账号", true) && !t.equals("取消", true)) {
            val (step, savedUser) = bindState
            if (step == 1) {
                // 第 1 步收到用户名
                pendingBind[userOpenid] = 2 to t
                send("📝 用户名已收到「$t」\n第 2 步：请发送你的【账号密码】\n（发「取消」结束绑定）")
                return
            } else if (step == 2) {
                // 第 2 步收到密码，直接绑定
                val username = savedUser
                val password = t
                val result = com.qitong.gateway.auth.AuthManager.login(db, username, password)
                if (result.isSuccess) {
                    val u = db.getUserByUsername(username.trim()) ?: run { pendingBind.remove(userOpenid); send("❌ 账号校验失败"); return }
                    db.setQqUserBound(userOpenid, u.id)
                    if (u.role == "admin" || u.role == "agent") db.setQqUserPerm(userOpenid, 3, "")
                    val bal = db.getUserBalance(u.id)
                    pendingBind.remove(userOpenid)
                    send("✅ 绑定成功！已登录账号「${u.username}」\n👤 角色: ${u.role}\n💰 余额: ¥${"%.2f".format(bal)}\n📊 累计充值: ¥${"%.2f".format(u.totalRecharge)}\n发「我的账号」随时查看，发「退出账号」解绑")
                    db.addQqLog(bot.appid, groupOpenid, userOpenid, "bind", "绑定账号 $username", System.currentTimeMillis() - t0)
                } else {
                    pendingBind.remove(userOpenid)
                    send("❌ 登录失败：${result.exceptionOrNull()?.message ?: "用户名或密码错误"}（发「绑定账号」重新开始）")
                }
                return
            }
        }
        if (t.equals("取消", true) && pendingBind.containsKey(userOpenid)) {
            pendingBind.remove(userOpenid)
            send("已取消绑定")
            return
        }
        // ★ 群卡片模式：群内可切换（发「切换卡片/切换文本」），插件/AI 回复按模式用卡片或文本发送
        val cardMode = if (groupOpenid.isNotBlank()) {
            (db.getQqGroupConfig(groupOpenid)?.get("cardMode") as? Boolean) == true
        } else false
        // smartSend：按群卡片模式自动选择发送方式（群内卡片用 markdown，私聊/文本用纯文本）
        // ★ v47 修复：QQ 被动回复(msg_id)每条消息只能成功一次！
        //   过程推送（💭/✅）走主动消息（无 msg_id），最终回复 isFinal=true 才用被动回复（msg_id）
        var replyCount = 0
        val smartSend: (String, Boolean) -> Boolean = { content, isFinal ->
            replyCount++
            val usePassive = isFinal  // 只有最终回复用被动回复
            val effectiveMsgId = if (usePassive) msgId else null
            val at2 = api.getAccessToken(bot)
            if (groupOpenid.isNotBlank()) {
                if (cardMode && !at2.isNullOrBlank()) {
                    api.sendGroupCardMessage(bot, at2, groupOpenid, content, effectiveMsgId)
                } else if (!at2.isNullOrBlank()) {
                    api.sendGroupMessage(bot, at2, groupOpenid, content, effectiveMsgId)
                } else {
                    if (usePassive) send(content) else false
                }
            } else {
                if (!at2.isNullOrBlank()) api.sendC2cMessage(bot, at2, userOpenid, content, effectiveMsgId)
                else { if (usePassive) send(content) else false }
            }
        }
        // 0) 内置指令（签到/积分/全员禁言）
        val builtin = matchBuiltin(text, groupOpenid)
        if (builtin != null) {
            when (builtin.first) {
                "sign" -> {
                    if (onCooldown(userOpenid, 3)) return
                    val reward = (5..20).random()
                    // ★ 积分按群独立：群内签到记入本群，私聊记入空群
                    val got = db.signQqUser(userOpenid, reward, groupOpenid)
                    val reply = if (got != null) "签到成功 +$got 积分" else "今天已经签过到啦，明天再来～"
                    send(reply); lastReplyTs[userOpenid] = System.currentTimeMillis()
                    db.addQqLog(bot.appid, groupOpenid, userOpenid, "command", "签到", System.currentTimeMillis() - t0)
                    return
                }
                "points" -> {
                    // ★ 积分按群独立：查本群积分
                    val p = db.getQqPoints(userOpenid, groupOpenid)
                    send("当前积分：${p["points"]}（累计签到 ${p["signCount"]} 次）")
                    return
                }
                "rename" -> {
                    // ★ 备注修改：群/私聊均可，用户改自己的；管理员(3+)可"改备注 openid 名字"帮改别人
                    val parts = builtin.second.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
                    if (parts.isEmpty()) { send("⚠️ 语法：改我名字 新备注；管理员可：改备注 用户openid 新备注"); return }
                    // 管理员帮改：第一个参数是 openid（后台QQ管理可查），剩余是名字
                    val qqU = db.getQqUserByGroup(userOpenid, groupOpenid)
                    val perm = (qqU?.get("permLevel") as? Number)?.toInt() ?: 1
                    if (parts.size >= 2 && perm >= 3) {
                        val targetOpenid = parts[0]
                        val newName = parts.drop(1).joinToString(" ")
                        if (newName.length > 20) { send("⚠️ 备注最多 20 字"); return }
                        db.updateQqUserByGroup(targetOpenid, groupOpenid, displayName = newName)
                        send("✅ 已把用户备注改为「$newName」")
                        db.addQqLog(bot.appid, groupOpenid, userOpenid, "command", "帮改 $targetOpenid 备注 $newName", System.currentTimeMillis() - t0)
                        return
                    }
                    // 用户改自己的
                    val newName = builtin.second.trim()
                    if (newName.length > 20) { send("⚠️ 备注最多 20 字"); return }
                    db.updateQqUserByGroup(userOpenid, groupOpenid, displayName = newName)
                    send("✅ 已把你的备注改为「$newName」（本群生效）")
                    db.addQqLog(bot.appid, groupOpenid, userOpenid, "command", "改备注 $newName", System.currentTimeMillis() - t0)
                    return
                }
                "mute_on" -> {
                    if (groupOpenid.isBlank()) return
                    // ★ 群管权限开关：本群禁止全员禁言则拒绝
                    val gc = db.getQqGroupConfig(groupOpenid)
                    if (gc != null && (gc["adminMute"] as? Boolean) != true) { send("⛔ 本群已关闭「全员禁言」权限"); return }
                    // 需要管理级权限(3+)
                    val qqU = db.getQqUserByGroup(userOpenid, groupOpenid)
                    val perm = (qqU?.get("permLevel") as? Number)?.toInt() ?: 1
                    if (perm < 3) { send("⛔ 全员禁言需要管理级权限(3级)，您当前为 ${permLabel(perm)}"); return }
                    val at = api.getAccessToken(bot)
                    if (at.isNullOrBlank()) { send("禁言失败（获取凭证失败）"); return }
                    if (api.setGroupMute(bot, at, groupOpenid, true)) send("已开启全员禁言") else send("全员禁言失败（需机器人是群管理员）")
                    db.addQqLog(bot.appid, groupOpenid, userOpenid, "command", "全员禁言", System.currentTimeMillis() - t0)
                    return
                }
                "mute_off" -> {
                    if (groupOpenid.isBlank()) return
                    val gc = db.getQqGroupConfig(groupOpenid)
                    if (gc != null && (gc["adminMute"] as? Boolean) != true) { send("⛔ 本群已关闭「全员禁言」权限"); return }
                    val qqU = db.getQqUserByGroup(userOpenid, groupOpenid)
                    val perm = (qqU?.get("permLevel") as? Number)?.toInt() ?: 1
                    if (perm < 3) { send("⛔ 解除禁言需要管理级权限(3级)，您当前为 ${permLabel(perm)}"); return }
                    val at = api.getAccessToken(bot)
                    if (at.isNullOrBlank()) { send("解除失败（获取凭证失败）"); return }
                    if (api.setGroupMute(bot, at, groupOpenid, false)) send("已解除全员禁言") else send("解除失败")
                    db.addQqLog(bot.appid, groupOpenid, userOpenid, "command", "解除全员禁言", System.currentTimeMillis() - t0)
                    return
                }
                "members" -> {
                    // ★ 群成员列表（需管理级3+ + 群管权限 adminKick）
                    if (groupOpenid.isBlank()) return
                    val gc = db.getQqGroupConfig(groupOpenid)
                    if (gc != null && (gc["adminKick"] as? Boolean) != true) { send("⛔ 本群已关闭「群员管理」权限"); return }
                    val qqU = db.getQqUserByGroup(userOpenid, groupOpenid)
                    val perm = (qqU?.get("permLevel") as? Number)?.toInt() ?: 1
                    if (perm < 3) { send("⛔ 群成员管理需要管理级权限(3级)"); return }
                    val at = api.getAccessToken(bot) ?: run { send("获取凭证失败"); return }
                    val members = api.getGroupMembers(bot, at, groupOpenid)
                    if (members.isEmpty()) send("📋 群成员列表（空或需机器人是群管理员）")
                    else send("📋 群成员 ${members.size} 人：\n" + members.take(50).joinToString("\n") { "· $it" })
                    db.addQqLog(bot.appid, groupOpenid, userOpenid, "command", "查群成员 ${members.size}人", System.currentTimeMillis() - t0)
                    return
                }
                "kick" -> {
                    // ★ 踢出群成员（需管理级3+ + 群管权限 adminKick）
                    if (groupOpenid.isBlank()) return
                    val gc = db.getQqGroupConfig(groupOpenid)
                    if (gc != null && (gc["adminKick"] as? Boolean) != true) { send("⛔ 本群已关闭「群员管理」权限"); return }
                    val qqU = db.getQqUserByGroup(userOpenid, groupOpenid)
                    val perm = (qqU?.get("permLevel") as? Number)?.toInt() ?: 1
                    if (perm < 3) { send("⛔ 踢人需要管理级权限(3级)"); return }
                    val targetOpenid = builtin.second.trim()
                    if (targetOpenid.isBlank()) { send("⚠️ 语法：踢 用户openid"); return }
                    val at = api.getAccessToken(bot) ?: run { send("获取凭证失败"); return }
                    if (api.kickGroupMember(bot, at, groupOpenid, targetOpenid)) send("✅ 已踢出 $targetOpenid") else send("踢人失败（需机器人是群主/管理员）")
                    db.addQqLog(bot.appid, groupOpenid, userOpenid, "command", "踢出 $targetOpenid", System.currentTimeMillis() - t0)
                    return
                }
                "card_on" -> {
                    // 切换为卡片模式（markdown 卡片回复）
                    if (groupOpenid.isBlank()) { send("卡片模式仅群内可用（私聊默认文本）"); return }
                    val qqU = db.getQqUserByGroup(userOpenid, groupOpenid)
                    val perm = (qqU?.get("permLevel") as? Number)?.toInt() ?: 1
                    if (perm < 3) { send("⛔ 切换模式需要管理级权限(3级)"); return }
                    db.updateQqGroup(groupOpenid, null, null, null, null, null, null, null, null, cardMode = true)
                    send("✅ 本群已切换为 **卡片模式**，AI/插件回复将用 markdown 卡片发送")
                    db.addQqLog(bot.appid, groupOpenid, userOpenid, "command", "切换卡片模式", System.currentTimeMillis() - t0)
                    return
                }
                "card_off" -> {
                    if (groupOpenid.isBlank()) { send("文本模式仅群内可用"); return }
                    val qqU = db.getQqUserByGroup(userOpenid, groupOpenid)
                    val perm = (qqU?.get("permLevel") as? Number)?.toInt() ?: 1
                    if (perm < 3) { send("⛔ 切换模式需要管理级权限(3级)"); return }
                    db.updateQqGroup(groupOpenid, null, null, null, null, null, null, null, null, cardMode = false)
                    send("✅ 本群已切换为 **文本模式**，回复将用普通文本发送")
                    db.addQqLog(bot.appid, groupOpenid, userOpenid, "command", "切换文本模式", System.currentTimeMillis() - t0)
                    return
                }
                "group_note" -> {
                    // 群里改当前群备注/群名（管理员3+）
                    if (groupOpenid.isBlank()) { send("私聊无法修改群备注"); return }
                    val qqU = db.getQqUserByGroup(userOpenid, groupOpenid)
                    val perm = (qqU?.get("permLevel") as? Number)?.toInt() ?: 1
                    if (perm < 3) { send("⛔ 修改群备注需要管理级权限(3级)，您当前为 ${permLabel(perm)}"); return }
                    val newName = builtin.second.trim()
                    if (newName.isBlank()) { send("⚠️ 语法：修改群备注 新群名"); return }
                    if (newName.length > 30) { send("⚠️ 群备注最多 30 字"); return }
                    db.updateQqGroup(groupOpenid, null, null, null, null, null, null, newName)
                    send("✅ 本群备注已改为「$newName」")
                    db.addQqLog(bot.appid, groupOpenid, userOpenid, "command", "修改群备注 $newName", System.currentTimeMillis() - t0)
                    return
                }
                "bind" -> {
                    // ★ v51 支持分步绑定：发「绑定账号」→ 机器人问用户名 → 输入用户名 → 问密码 → 输入密码 → 绑定完成
                    val parts = builtin.second.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
                    if (parts.isEmpty()) {
                        // 启动分步绑定
                        pendingBind[userOpenid] = 1 to ""
                        send("🔐 开始绑定网关账号（分步）\n第 1 步：请发送你的【账号用户名】")
                        return
                    }
                    if (parts.size < 2) {
                        // 只有一个参数 = 可能是用户名，进入密码步骤
                        pendingBind[userOpenid] = 2 to parts[0]
                        send("📝 用户名已收到「${parts[0]}」\n第 2 步：请发送你的【账号密码】\n（格式提示：也可以一次发「绑定账号 用户名 密码」直接绑定）")
                        return
                    }
                    val username = parts[0]
                    val password = parts.drop(1).joinToString(" ")
                    val result = com.qitong.gateway.auth.AuthManager.login(db, username, password)
                    if (result.isSuccess) {
                        val u = db.getUserByUsername(username.trim()) ?: run { send("❌ 账号校验失败"); return }
                        db.setQqUserBound(userOpenid, u.id)
                        // 绑定后同步为管理级权限（管理员账号）或保留原权限
                        if (u.role == "admin" || u.role == "agent") db.setQqUserPerm(userOpenid, 3, "")
                        val bal = db.getUserBalance(u.id)
                        pendingBind.remove(userOpenid)
                        send("✅ 绑定成功！已登录账号「${u.username}」\n👤 角色: ${u.role}\n💰 余额: ¥${"%.2f".format(bal)}\n📊 累计充值: ¥${"%.2f".format(u.totalRecharge)}\n发「我的账号」随时查看，发「退出账号」解绑")
                        db.addQqLog(bot.appid, groupOpenid, userOpenid, "bind", "绑定账号 $username", System.currentTimeMillis() - t0)
                    } else {
                        send("❌ 登录失败：${result.exceptionOrNull()?.message ?: "用户名或密码错误"}")
                        db.addQqLog(bot.appid, groupOpenid, userOpenid, "bind", "绑定失败 $username", System.currentTimeMillis() - t0)
                    }
                    return
                }
                "bind_code" -> {
                    // ★ v52 绑定码登录：网页生成 6 位码，群里发「绑定码 xxxx」即绑定（无需私聊/加好友）
                    val code = builtin.second.trim()
                    if (code.isBlank() || !code.matches(Regex("\\d{6}"))) {
                        send("⚠️ 绑定码是 6 位数字。\n请先在【网页后台→个人中心→QQ绑定码】点「生成绑定码」，再把 6 位码发到这里。")
                        return
                    }
                    val cfg = db.getConfig("qq_bind_code_$code", "")
                    if (cfg.isBlank()) {
                        send("❌ 绑定码无效或已使用。请重新在网页个人中心生成。")
                        return
                    }
                    val parts = cfg.split("|")
                    val userId = parts.getOrNull(0)?.toLongOrNull() ?: 0
                    val expireAt = parts.getOrNull(1)?.toLongOrNull() ?: 0
                    if (userId <= 0 || System.currentTimeMillis() > expireAt) {
                        db.setConfig("qq_bind_code_$code", "") // 清理过期码
                        send("❌ 绑定码已过期。请重新在网页个人中心生成。")
                        return
                    }
                    val u = db.getUserById(userId)
                    if (u == null) { send("❌ 绑定账号不存在"); return }
                    db.setQqUserBound(userOpenid, u.id)
                    if (u.role == "admin" || u.role == "agent") db.setQqUserPerm(userOpenid, 3, "")
                    db.setConfig("qq_bind_code_$code", "") // 一次性使用
                    val bal = db.getUserBalance(u.id)
                    send("✅ 绑定成功！已登录账号「${u.username}」\n👤 角色: ${u.role}\n💰 余额: ¥${"%.2f".format(bal)}\n发「我的账号」随时查看，发「退出账号」解绑")
                    db.addQqLog(bot.appid, groupOpenid, userOpenid, "bind", "绑定码登录 ${u.username}", System.currentTimeMillis() - t0)
                    return
                }
                "my_account" -> {
                    val bound = db.getQqBoundUser(userOpenid)
                    if (bound == null) {
                        send("🔓 当前未绑定网关账号\n私聊发：绑定账号 用户名 密码 即可登录绑定（获得该账号全部权限，可查余额/用量/分销）")
                    } else {
                        val bal = db.getUserBalance(bound.id)
                        val boundModels = bound.bindModels
                        send("👤 当前绑定账号：${bound.username}\n💎 角色: ${bound.role}\n💰 余额: ¥${"%.2f".format(bal)}\n💳 累计充值: ¥${"%.2f".format(bound.totalRecharge)}\n📦 绑定模型: ${if (boundModels.isNullOrEmpty()) "全部" else boundModels.joinToString(",")}\n\n发「退出账号」解绑")
                    }
                    return
                }
                "unbind" -> {
                    val bound = db.getQqBoundUser(userOpenid)
                    if (bound == null) { send("🔓 当前未绑定账号"); return }
                    db.clearQqUserBound(userOpenid)
                    send("✅ 已退出账号「${bound.username}」，解除绑定成功")
                    db.addQqLog(bot.appid, groupOpenid, userOpenid, "bind", "解绑账号 ${bound.username}", System.currentTimeMillis() - t0)
                    return
                }
            }
        }
        // 0.5) 网关技能指令（内置：查状态/排行/余额/充值/启停/切模型等，按 openid 权限控制）
        val skill = matchGatewaySkill(text)
        if (skill != null) {
            val (code, param) = skill
            val qqUser = db.getQqUser(userOpenid)
            val perm = (qqUser?.get("permLevel") as? Number)?.toInt() ?: 1
            if (perm <= 0) { send("⛔ 您没有权限使用机器人网关操作，请联系管理员开通"); return }
            // 权限分级：查询类(6xxxxx)需>=1；切换类(2xxxxx)/测速(1xxxxx)需>=2；网关启停(3xxxxx)/管理(8xxxxx)需>=3
            val need = when {
                code.startsWith("6") -> 1
                code.startsWith("1") || code.startsWith("2") -> 2
                code.startsWith("8") || code.startsWith("3") || code.startsWith("9") -> 3
                else -> 2
            }
            if (perm < need) { send("⛔ 该操作需要权限等级 ${needLabel(need)}，您当前为 ${permLabel(perm)}"); return }
            // ★ v53 绑定账号 userId：技能执行用绑定账号身份（查余额/用量/分销等拿到绑定账号真实数据）
            val skillUserId = db.getQqBoundUser(userOpenid)?.id ?: 0L
            val result = com.qitong.gateway.http.SkillExecutor.execute(db, code, param, skillUserId)
            send(result)
            db.addQqLog(bot.appid, groupOpenid, userOpenid, "skill", "[$code] $text", System.currentTimeMillis() - t0)
            return
        }
        // 0.6) 文字游戏（每用户独立，无需权限门槛，娱乐）
        val game = handleGame(userOpenid, text)
        if (game != null) { send(game); db.addQqLog(bot.appid, groupOpenid, userOpenid, "game", text, System.currentTimeMillis() - t0); return }

        // 0.65) 工作流关键词触发（QQ 发匹配触发词 → 执行整个工作流）
        val wfHit = try {
            db.getWorkflows(0).filter { (it["enabled"] as? Boolean) == true && it["triggerType"] as? String == "keyword" }
                .firstOrNull { w -> val trig = (w["triggerText"] as? String)?.trim() ?: ""; trig.isNotBlank() && t.contains(trig, true) }
        } catch (e: Exception) { null }
        if (wfHit != null) {
            val qqUser = db.getQqUser(userOpenid)
            val perm = (qqUser?.get("permLevel") as? Number)?.toInt() ?: 1
            if (perm < 2) { send("⛔ 工作流触发需要操作级权限(2级)，您当前为 ${permLabel(perm)}"); return }
            send("⚙️ 正在执行工作流「${wfHit["name"]}」…")
            val stepsJson = try { org.json.JSONArray(wfHit["steps"] as? String ?: "[]") } catch (e: Exception) { org.json.JSONArray() }
            val sb = StringBuilder()
            for (i in 0 until stepsJson.length()) {
                val step = stepsJson.optJSONObject(i) ?: continue
                val out = com.qitong.gateway.http.WorkflowEngine.runStep(db, step.optString("type", "reply"), step.optString("content", ""))
                sb.append("【").append(i + 1).append("·").append(step.optString("type", "reply")).append("】\n").append(out).append("\n\n")
            }
            send("✅ 工作流「${wfHit["name"]}」完成：\n" + sb.toString().take(800))
            db.addQqLog(bot.appid, groupOpenid, userOpenid, "workflow", "触发 ${wfHit["name"]}", System.currentTimeMillis() - t0)
            return
        }

        // 0.7) 沙盒 Linux 终端操控（需管理级权限，远程执行 shell 命令）
        // 0.7a) AI 智能终端：自然语言直接操作
        if (t.startsWith("AI终端", true) || t.startsWith("智能终端", true) || t.startsWith("ai终端", true)) {
            val qqUser = db.getQqUser(userOpenid)
            val perm = (qqUser?.get("permLevel") as? Number)?.toInt() ?: 1
            if (perm < 3) { send("⛔ 终端操控需要管理级权限(3级)，您当前为 ${permLabel(perm)}"); return }
            val req = t.substringAfter(" ").trim()
            if (req.isBlank()) { send("⚠️ 语法：AI终端 你的需求（如：AI终端 查看磁盘占用）"); return }
            send("🤖 AI 思考中…")
            val cmd = com.qitong.gateway.http.AiTermHelper.genCommand(req)
            if (cmd.isBlank()) { send("😵 AI 无法生成安全命令，换个说法试试"); return }
            val dangerous = listOf("rm -rf /", "mkfs", "dd if=", "shutdown", "reboot", ":(){", "format", "fdisk")
            if (dangerous.any { cmd.contains(it) }) { send("⛔ 生成命令涉及危险操作已拦截"); return }
            val sessions = com.qitong.gateway.http.TerminalManager.list()
            val sid = if (sessions.isNotEmpty()) sessions.first().id
                else com.qitong.gateway.http.TerminalManager.create("QQ-AI终端-" + userOpenid.take(6)).id
            val (ok, out) = com.qitong.gateway.http.TerminalManager.exec(sid, cmd)
            if (!ok) { send("⚠️ $out"); return }
            send("🤖 AI 智能终端 [${sid}]\n💡 需求：$req\n$ cmd\n\n" + out.take(700))
            db.addQqLog(bot.appid, groupOpenid, userOpenid, "ai_term", "[$req] -> $cmd", System.currentTimeMillis() - t0)
            return
        }
        // 0.7b) 提醒（提醒我 X分钟后 内容）
        if (t.startsWith("提醒", true) && (t.contains("分钟后", true) || t.contains("小时后", true) || t.contains("秒后", true))) {
            val qqUser = db.getQqUser(userOpenid)
            val perm = (qqUser?.get("permLevel") as? Number)?.toInt() ?: 1
            if (perm < 1) { send("⛔ 没有权限使用提醒功能"); return }
            val m = Regex("""提醒我?\s*(\d+)\s*(秒|分钟|小时)后\s*(.*)""").find(t)
            if (m == null) { send("⚠️ 语法：提醒我 10分钟后 喝水"); return }
            val num = m.groupValues[1].toLongOrNull() ?: 10
            val unit = m.groupValues[2]
            val content = m.groupValues[3].ifBlank { "时间到！" }
            val delayMs = when (unit) { "秒" -> num * 1000; "小时" -> num * 3600_000L; else -> num * 60_000L }
            if (delayMs < 5000) { send("⚠️ 提醒时间太短（至少5秒）"); return }
            val remind = ReminderItem(userOpenid, groupOpenid, content, delayMs, bot)
            reminders.add(remind)
            send("⏰ 好的，${num}${unit}后提醒你：$content（可发「查看提醒」/「取消提醒」管理）")
            db.addQqLog(bot.appid, groupOpenid, userOpenid, "remind", "设置提醒 $content", System.currentTimeMillis() - t0)
            return
        }
        if (t.equals("查看提醒", true) || t.equals("我的提醒", true)) {
            val mine = reminders.filter { it.userOpenid == userOpenid }
            if (mine.isEmpty()) send("📭 你当前没有提醒")
            else send("📋 我的提醒（${mine.size}条）：\n" + mine.mapIndexed { i, r -> "${i+1}. ${r.content}（剩余${r.remainText()}）" }.joinToString("\n"))
            return
        }
        if (t.startsWith("取消提醒", true)) {
            val n = t.substringAfter("取消提醒").trim().toIntOrNull()
            val mine = reminders.filter { it.userOpenid == userOpenid }
            if (n == null || n < 1 || n > mine.size) { send("⚠️ 请输入有效序号，如「取消提醒 1」"); return }
            val removed = mine[n-1]
            reminders.remove(removed)
            send("✅ 已取消提醒：${removed.content}")
            return
        }
        // 0.7c) 群发（管理级：群发 内容）
        if (t.startsWith("群发", true) || t.startsWith("广播", true)) {
            val qqUser = db.getQqUser(userOpenid)
            val perm = (qqUser?.get("permLevel") as? Number)?.toInt() ?: 1
            if (perm < 3) { send("⛔ 群发需要管理级权限(3级)，您当前为 ${permLabel(perm)}"); return }
            val content = t.substringAfter(" ").trim()
            if (content.isBlank()) { send("⚠️ 语法：群发 内容"); return }
            val groups = db.getQqGroups()
            if (groups.isEmpty()) { send("⚠️ 暂无可广播的群（还没有群触发过机器人）"); return }
            send("📢 正在向 ${groups.size} 个群广播…")
            var okCount = 0
            groups.forEach { g ->
                val gid = g["groupOpenid"] as? String ?: return@forEach
                val at = api.getAccessToken(bot)
                if (!at.isNullOrBlank() && api.sendGroupMessage(bot, at, gid, content, null)) okCount++
            }
            send("✅ 群发完成：成功 $okCount/${groups.size} 个群")
            db.addQqLog(bot.appid, groupOpenid, userOpenid, "broadcast", "群发到 $okCount/${groups.size} 群", System.currentTimeMillis() - t0)
            return
        }
        if (t.equals("创建终端", true) || t.startsWith("创建终端 ", true)) {
            val qqUser = db.getQqUser(userOpenid)
            val perm = (qqUser?.get("permLevel") as? Number)?.toInt() ?: 1
            if (perm < 3) { send("⛔ 终端操控需要管理级权限(3级)，您当前为 ${permLabel(perm)}"); return }
            val label = t.removePrefix("创建终端").trim().ifBlank { "QQ终端-" + userOpenid.take(6) }
            val s = com.qitong.gateway.http.TerminalManager.create(label)
            send("✅ 终端创建成功！\n📛 会话: ${s.label}\n🆔 ID: ${s.id}\n\n发送「终端 $s.id 命令」即可执行（如：终端 $s.id ls -la）\nWeb后台「侧边栏 → 终端」也能看到并操作它")
            db.addQqLog(bot.appid, groupOpenid, userOpenid, "terminal", "创建终端 ${s.id}", System.currentTimeMillis() - t0)
            return
if (t.startsWith("终端 ", true) || t.startsWith("执行 ", true) || t.startsWith("运行 ", true)) {
            val qqUser = db.getQqUser(userOpenid)
            val perm = (qqUser?.get("permLevel") as? Number)?.toInt() ?: 1
            if (perm < 3) { send("⛔ 终端操控需要管理级权限(3级)，您当前为 ${permLabel(perm)}"); return }
            var rest = t.substringAfter(" ").trim()
            if (rest.isBlank()) { send("⚠️ 语法：终端 <命令>（用最近会话）或 终端 <会话ID> <命令>；先发「创建终端」创建会话"); return }
            var targetId: String? = null
            // 若第一个词是已有会话ID，则在该会话执行
            val first = rest.substringBefore(" ").trim()
            if (first.startsWith("term-") && com.qitong.gateway.http.TerminalManager.get(first) != null) {
                targetId = first
                rest = rest.substringAfter(" ").trim()
            }
            if (rest.isBlank()) { send("⚠️ 请提供要执行的命令"); return }
            if (rest.length > 500) { send("⚠️ 命令太长（限500字符）"); return }
            val dangerous = listOf("rm -rf /", "mkfs", "dd if=", "shutdown", "reboot", ":(){", "format", "fdisk")
            if (dangerous.any { rest.contains(it) }) { send("⛔ 危险命令已拦截"); return }
            // 无指定会话：自动创建/复用最近一次
            if (targetId == null) {
                val sessions = com.qitong.gateway.http.TerminalManager.list()
                val mine = sessions.firstOrNull()
                if (mine == null) {
                    val s = com.qitong.gateway.http.TerminalManager.create("QQ终端-" + userOpenid.take(6))
                    targetId = s.id
                } else targetId = mine.id
            }
            val (ok, out) = com.qitong.gateway.http.TerminalManager.exec(targetId!!, rest)
            if (!ok) { send("⚠️ $out"); return }
            send("🖥 [${targetId}] 终端执行：\n$ rest\n\n" + out.take(800))
            db.addQqLog(bot.appid, groupOpenid, userOpenid, "shell", "[$targetId] $rest → ${out.take(60)}", System.currentTimeMillis() - t0)
            return
        }
        }

        // 1) 插件指令匹配
        val cmd = matchCommand(text)
        if (cmd != null) {
            if (onCooldown(userOpenid, (cmd["cooldown"] as? Number)?.toInt() ?: 5)) return
            val reply = when (cmd["action"]) {
                "http" -> httpGet(cmd["content"] as String)
                "ai" -> askModel(bot, key, "${cmd["content"]} $text", userOpenid, groupOpenid)
                // ★ 插件强化：支持终端命令/网关技能/工作流/AI画图
                "terminal" -> runTerminal(cmd["content"] as String)
                "skill" -> runSkillCode(cmd["content"] as String, text, userOpenid)
                "workflow" -> runWorkflowByName(cmd["content"] as String, text)
                "image" -> runImageGen(cmd["content"] as String, text)
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

        // 1.5) ★ 上传的插件包（zip 安装的游戏/功能插件）：菜单 + 命令分发
        val pluginHit = matchPlugin(text, userOpenid)
        if (pluginHit != null) {
            val (pluginName, output) = pluginHit
            smartSend(output, true)
            lastReplyTs[userOpenid] = System.currentTimeMillis()
            db.addQqLog(bot.appid, groupOpenid, userOpenid, "plugin", "[$pluginName] $text", System.currentTimeMillis() - t0)
            return
        }

        // 2) 大模型对话（人格系统 qtai-sj：需 permLevel>=1 才允许；禁止=0 不响应）
        // ★ 按群取用户（群隔离）：群内用群记录，私聊 group 为空
        val u = db.getQqUserByGroup(userOpenid, groupOpenid) ?: db.getQqUser(userOpenid)
        val perm = (u?.get("permLevel") as? Number)?.toInt() ?: 1
        if (perm <= 0) { send("⛔ 您已被禁止使用机器人，请联系管理员"); return }
        if (u != null && !(u["aiEnabled"] as Boolean)) return
        val cfg = if (groupOpenid.isNotBlank()) db.getQqGroupConfig(groupOpenid) else null
        if (cfg != null && !(cfg["aiEnabled"] as Boolean)) return
        if (onCooldown(userOpenid, 3)) return

        val reply = askModel(bot, key, text, userOpenid, groupOpenid, smartSend)
        if (!reply.isNullOrBlank()) {
            smartSend(reply, true)
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
        if (t.startsWith("改我名字", true) || t.startsWith("修改备注", true) || t.startsWith("改备注", true)) return "rename" to t.substringAfter(" ").trim()
        // ★ v52 绑定码登录：网页个人中心生成 6 位码，群里发「绑定码 xxxx」即可绑定（无需私聊）
        if (t.startsWith("绑定码", true)) return "bind_code" to t.substringAfter(" ").trim()
        // ★ v50 QQ 远程登录绑定：私发账号密码绑定网关账号，绑定后获得该账号权限/可查
        if (t.startsWith("绑定账号", true) || t.startsWith("绑定", true) && t.length > 3) return "bind" to t.substringAfter(" ").trim()
        if (t.equals("我的账号", true) || t.equals("账号信息", true)) return "my_account" to t
        if (t.equals("退出账号", true) || t.equals("解绑", true) || t.equals("退出登录", true)) return "unbind" to t
        if (t.equals("切换卡片", true) || t.equals("卡片模式", true)) return "card_on" to t
        if (t.equals("切换文本", true) || t.equals("文本模式", true) || t.equals("切换文字", true)) return "card_off" to t
        // 群里改当前群备注/群名（管理员3+）
        if (t.startsWith("修改群备注", true) || t.startsWith("修改群名", true) || t.startsWith("改群名", true) || t.startsWith("改群备注", true)) return "group_note" to t.substringAfter(" ").trim()
        if (groupOpenid.isNotBlank()) {
            if (t.equals("全员禁言", true)) return "mute_on" to t
            if (t.equals("解除全员禁言", true) || t.equals("取消全员禁言", true)) return "mute_off" to t
            if (t.startsWith("群成员", true) || t.startsWith("成员列表", true)) return "members" to t
            if (t.startsWith("踢", true) || t.startsWith("踢出", true)) return "kick" to t.substringAfter(" ").trim()
        }
        return null
    }

    /** 网关技能指令匹配（对齐原APP技能：查状态/排行/模型/启停/切换等；返回 技能编码+参数） */
    private fun matchGatewaySkill(text: String): Pair<String, String>? {
        val t = text.trim()
        if (t.isBlank()) return null
        // 查官网状态 / 网关状态
        if (t.startsWith("查状态", true) || t.equals("网关状态", true) || t.startsWith("状态", true) && t.length <= 6) return "600001" to ""
        // 查排行榜 / 测速排行
        if (t.contains("排行", true) || t.contains("排行榜", true) || t.startsWith("测速排行", true)) return "600002" to ""
        // 查活跃模型 / 当前模型
        if (t.contains("活跃模型", true) || t.contains("当前模型", true)) return "600003" to ""
        // 查流量 / 用量
        if (t.contains("流量", true) || t.contains("用量", true) || t.contains("上行", true)) return "600004" to ""
        // 查余额：查余额 用户名
        if (t.startsWith("查余额", true) || t.startsWith("查余额 ", true)) return "600009" to t.substringAfter("余额").trim()
        // 充值：充值 用户名 金额
        if (t.startsWith("充值 ", true) || t.startsWith("给 ", true) && t.contains("充值", true)) {
            return "900020" to t.substringAfter("充值").trim().ifBlank { t.substringAfter("给 ").trim() }
        }
        // 查服务商
        if (t.contains("服务商", true) && (t.contains("查", true) || t.contains("列", true) || t.length <= 5)) return "600007" to ""
        // 查模型列表
        if (t.contains("模型列表", true) || t.equals("模型", true)) return "600008" to ""
        // 切换模型：切换模型 xxx
        if (t.startsWith("切换", true) || t.startsWith("切到", true) || t.startsWith("用", true)) {
            val p = t.substringAfter(" ").trim().ifBlank { "" }
            return "200001" to p
        }
        // 启停：启动网关 / 停止网关
        if (t.equals("启动网关", true) || t.equals("开启网关", true)) return "300004" to ""
        if (t.equals("停止网关", true) || t.equals("关闭网关", true)) return "300005" to ""
        // 故障转移开关
        if (t.contains("开启故障", true) || t.contains("打开故障", true)) return "300001" to ""
        if (t.contains("关闭故障", true) || t.contains("关闭转移", true)) return "300002" to ""
        // 启用/禁用模型
        if (t.startsWith("启用模型", true) || t.startsWith("启用 ", true)) return "800003" to t.substringAfter(" ").trim()
        if (t.startsWith("禁用模型", true) || t.startsWith("禁用 ", true)) return "800004" to t.substringAfter(" ").trim()
        return null
    }

    private fun permLabel(level: Int): String = when (level) {
        0 -> "禁止"
        1 -> "查询"
        2 -> "操作"
        3 -> "管理"
        4 -> "全部"
        else -> "未知"
    }

    private fun needLabel(need: Int): String = when (need) {
        1 -> "查询(1级)"
        2 -> "操作(2级)"
        3 -> "管理(3级)"
        else -> "操作"
    }

    /** 插件动作：终端命令（需管理级3+） */
    private fun runTerminal(content: String): String {
        val cmd = content.trim().ifBlank { "ls" }
        val s = com.qitong.gateway.http.TerminalManager.create("QQ插件终端")
        val (ok, out) = com.qitong.gateway.http.TerminalManager.exec(s.id, cmd)
        com.qitong.gateway.http.TerminalManager.close(s.id)
        return if (ok) "🖥 $cmd\n\n$out".take(900) else "⚠️ $out"
    }

    /** 插件动作：网关技能编码（如 600001=查状态） */
    private fun runSkillCode(content: String, text: String, userOpenid: String = ""): String {
        val code = content.trim().substringBefore(" ").ifBlank { return "⚠️ 未配置技能编码" }
        val param = text.substringAfter(" ").trim()
        // ★ v53 绑定账号 userId：插件技能执行用绑定账号身份
        val skillUserId = if (userOpenid.isNotBlank()) db.getQqBoundUser(userOpenid)?.id ?: 0L else 0L
        val result = kotlinx.coroutines.runBlocking {
            com.qitong.gateway.http.SkillExecutor.execute(db, code, param, skillUserId)
        }
        return result
    }

    /** 插件动作：执行工作流（按名称） */
    private fun runWorkflowByName(content: String, text: String): String {
        val name = content.trim().ifBlank { return "⚠️ 未配置工作流名称" }
        val wf = try { db.getWorkflows(0).firstOrNull { (it["name"] as? String) == name || (it["name"] as? String)?.contains(name, true) == true } } catch (e: Exception) { null }
            ?: return "⚠️ 工作流「$name」不存在"
        val stepsJson = try { org.json.JSONArray(wf["steps"] as? String ?: "[]") } catch (e: Exception) { org.json.JSONArray() }
        val sb = StringBuilder("⚙️ 工作流「${wf["name"]}」执行：\n")
        for (i in 0 until stepsJson.length()) {
            val st = stepsJson.getJSONObject(i)
            val r = com.qitong.gateway.http.WorkflowEngine.runStep(db, st.optString("type", "reply"), st.optString("content", ""))
            sb.append("${i + 1}. ${st.optString("type", "reply")}: $r\n")
        }
        return sb.toString().take(900)
    }

    /** 插件动作：AI 画图（调用网关画图模型 /v1/images/generations） */
    private fun runImageGen(content: String, text: String): String {
        val prompt = if (content.isNotBlank()) "$content $text" else text
        return try {
            val req = okhttp3.Request.Builder()
                .url("http://127.0.0.1:$gatewayPort/v1/images/generations")
                .addHeader("Content-Type", "application/json")
                .post("{\"model\":\"\",\"prompt\":${org.json.JSONObject.quote(prompt)},\"n\":1}".toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) "🎨 画图失败（HTTP ${resp.code}）"
                else {
                    val b64 = org.json.JSONObject(body).optJSONArray("data")?.optJSONObject(0)?.optString("b64_json")
                    if (b64.isNullOrBlank()) "🎨 画图完成，但未返回图片（模型可能不支持）"
                    else "🎨 画图成功（b64 图片 ${b64.length / 1024}KB，可在管理后台查看）"
                }
            }
        } catch (e: Exception) { "🎨 画图失败：${e.message}" }
    }

    /** 上传的插件包分发："菜单"显示已装插件菜单；其他文本匹配插件内命令（读插件目录 txt 脚本） */
    private fun matchPlugin(text: String, userOpenid: String): Pair<String, String>? {
        val t = text.trim()
        if (t.isBlank()) return null
        val dataDir = System.getenv("DATA_DIR") ?: "/data/qitong"
        // "菜单" → 列出所有已装插件
        if (t.equals("菜单", true) || t.equals("插件菜单", true)) {
            val plugins = db.getQqPlugins().filter { (it["enabled"] as? Boolean) != false }
            if (plugins.isEmpty()) return "插件菜单" to "📦 暂无已安装插件。管理员可在 QQ管理→插件 tab 上传压缩包安装。"
            val sb = StringBuilder("📦 已安装插件（${plugins.size}个）：\n")
            plugins.forEach { p ->
                sb.append("▪ ${p["name"]} v${p["version"]}\n")
                val menu = p["menu"] as? String
                if (!menu.isNullOrBlank()) sb.append("   ${menu}\n")
            }
            return "插件菜单" to sb.toString().take(900)
        }
        // 匹配插件目录下的脚本文件（txt 命令脚本）
        val pluginDirs = java.io.File(dataDir, "plugins").listFiles() ?: return null
        for (dir in pluginDirs) {
            if (!dir.isDirectory) continue
            val pluginName = dir.name
            // 读 plugin.json/描述（若有）
            val scriptDir = java.io.File(dir, "游戏")
            val files = if (scriptDir.exists()) scriptDir.listFiles() else dir.listFiles()
            files ?: continue
            // 匹配：命令前缀 = 文件名（去扩展名）
            for (f in files) {
                val base = f.name.substringBeforeLast(".").trim()
                if (base.isBlank() || !f.name.endsWith(".txt")) continue
                if (t.startsWith(base, true) || t.equals(base, true) || t.contains(base, true) && t.length <= base.length + 2) {
                    // 读取脚本内容，随机选一行回复（支持 $变量$ 占位符简单替换；GBK 旧脚本双编码回退）
                    val gbk = java.nio.charset.Charset.forName("GBK")
                    val content = runCatching { f.readText(Charsets.UTF_8) }.getOrDefault("")
                        .ifBlank { runCatching { f.readText(gbk) }.getOrDefault("") }
                    val lines = content.split("\n").filter { it.isNotBlank() && !it.startsWith("//") }
                    if (lines.isEmpty()) continue
                    val line = lines.random()
                    // 变量替换：$XX$/$名字$ 用 openid；$扣除xx$/$奖励xx$/$赞xx$/$贬xx$ 等旧平台变量保留语义去符号；
                    // 行首 "$变量$：" 前缀剥离
                    var out = line
                        .replace("\$XX\$", userOpenid.take(4))
                        .replace("\$名字\$", userOpenid.take(6))
                        .replace(Regex("\\$([^$]{1,12})\\$"), "「$1」")
                        .trim()
                    // 行首孤立 "「xxx」：" 前缀去掉（如 「扣除3000」：）
                    out = out.replace(Regex("^「[^」]{1,12}」[：:]\\s*"), "")
                    out = out.take(500)
                    return pluginName to "【$pluginName·$base】\n$out"
                }
            }
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

    /** 沙盒终端执行（容器内 shell，10 秒超时，输出截断） */
    private fun runShell(cmd: String): String {
        return try {
            val proc = ProcessBuilder("/bin/sh", "-c", cmd)
                .redirectErrorStream(true)
                .start()
            val out = proc.inputStream.bufferedReader().readText()
            if (!proc.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                return "⚠️ 命令超时（10秒）已终止\n$out".take(800)
            }
            out.ifBlank { "(无输出)" }.take(800)
        } catch (e: Exception) {
            "❌ 执行失败：${e.message}".take(800)
        }
    }

    /** 调本机网关大模型，带每用户独立上下文与人设覆盖。 */
    /** 清洗回复里的函数调用标签（[[沙盒:...]]、<dots_function_call>、<function_call>、<invoke>、<parameter>），保留文本部分 */
    private fun cleanFunctionTags(text: String): String {
        var out = text
        // 移除 [[沙盒:...]] 调用
        out = out.replace(Regex("\\[\\[沙盒:[^\\]]*\\]\\]"), "（已调用）")
        // 移除完整的 XML 函数调用标签对（含嵌套 parameter/param）
        out = out.replace(Regex("<(?:dots_function_call|function_call|invoke)[^>]*>[\\s\\S]*?</(?:dots_function_call|function_call|invoke)>"), "")
        // 移除可能残留的单标签（<dots_function_call name="xxx"> 或 </dots_function_call>）
        out = out.replace(Regex("</?(?:dots_function_call|function_call|invoke)\\s*[^>]*>"), "")
        // ★ v49 移除 parameter/param 子标签（<parameter name="query">值</parameter>）
        out = out.replace(Regex("<(?:parameter|param)\\s+name=\"[^\"]*\"[^>]*>[\\s\\S]*?</(?:parameter|param)>"), "")
        out = out.replace(Regex("</?(?:parameter|param)\\s*[^>]*>"), "")
        return out.trim()
    }

    private fun askModel(bot: QqBot, key: String, userText: String, userOpenid: String, groupOpenid: String = "", smartSend: (String, Boolean) -> Boolean = { _, _ -> false }): String? {
        val hist = history.getOrPut(key) { mutableListOf() }
        val msgs = JSONArray()
        // ★ 按群取人设（群隔离兼容：全局用户），群专属提示词优先
        val groupCfg = if (groupOpenid.isNotBlank()) db.getQqGroupConfig(groupOpenid) else null
        val groupPrompt = groupCfg?.get("groupPrompt") as? String
        val userPersona = db.getQqUser(userOpenid)?.get("persona") as? String?
        // ★ qtai-sj 沙盒模式：选中 qtai-sj 模型自动注入沙盒专属系统提示词（含知识库）
        val sandboxOn = bot.aiModel.equals("qtai-sj", true)
        // ★ v50 绑定账号身份：QQ 用户绑定网关账号后，沙盒调用自动用该账号 userId（权限/余额/用量对齐）
        val boundUser = db.getQqBoundUser(userOpenid)
        val effUserId = boundUser?.id ?: 0L
        val effIsAdmin = (boundUser?.role == "admin" || boundUser?.role == "agent") ||
            ((db.getQqUserByGroup(userOpenid, groupOpenid)?.get("permLevel") as? Number)?.toInt() ?: 1) >= 3
        val isAdmin = effIsAdmin
        val baseSys = when {
            sandboxOn -> com.qitong.gateway.sandbox.SandboxEngine.SYSTEM_PROMPT  // 沙盒完整版（含知识库）
            !groupPrompt.isNullOrBlank() -> groupPrompt  // 群专属提示词最高优先（非沙盒时）
            !userPersona.isNullOrBlank() -> userPersona
            bot.systemPrompt.isNotBlank() -> bot.systemPrompt
            else -> ""
        }
// 长期大脑记忆：把该用户最近的记忆注入 system，跨天记得对方；v47 加高频记忆提升（access>=5 自动进系统提示词）
        val mems = db.getQqBrainMemories(userOpenid, limit = 6)
        val promoted = db.getQqBrainPromoted(userOpenid, minHits = 5, limit = 8)
        var memSection = ""
        if (mems.isNotEmpty()) {
            memSection += "\n\n【你对这位用户的长期记忆】\n" + mems.reversed().joinToString("\n") { "- " + it }
        }
        if (promoted.isNotEmpty()) {
            memSection += "\n\n【这位用户的重要信息（高价值记忆）】\n" + promoted.joinToString("\n") { "- " + it }
        }
        val sysFull = baseSys + memSection
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
                var content = obj.optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")?.trim().orEmpty()
                // ★ 沙盒执行：qtai-sj 模式下解析回复中的函数调用并执行（兼容 [[沙盒:]] 与 <dots_function_call> 原生格式）
                // ★ v46 Agent 循环：每步执行结果立即推送 QQ（过程可见），执行完回填给模型继续下一步
                var loopGuard = 0
                while (sandboxOn && content.isNotBlank() && loopGuard < 8) {
                    loopGuard++
                    val calls = com.qitong.gateway.sandbox.SandboxEngine.parseCalls(content)
                    if (calls.isEmpty()) break
                    val results = StringBuilder()
                    var isFirst = true
                    for ((fn, args) in calls) {
                        // 💭 过程推送：执行前告诉用户 AI 在干嘛（精简为一条）
                        val argTxt = args.entries.joinToString(",") { "${it.key}=${it.value}" }
                        smartSend("💭 qtai-sj 正在调用：${fn}(${argTxt}) …", false)
                        val r = com.qitong.gateway.sandbox.SandboxEngine.execute(fn, args, isAdmin, effUserId, db)
                        results.append(if (isFirst) "" else "\n").append("【$fn 执行结果】\n$r")
                        isFirst = false
                        // 📤 执行结果推送（结果简洁展示，不再重复"执行完成"）
                        smartSend("✅ ${fn}：${r.take(500)}", false)
                        db.addQqLog(bot.appid, groupOpenid, userOpenid, "sandbox", "[$fn] $args -> ${r.take(80)}", 0)
                    }
                    // 清洗调用标签，把结果回填给模型继续规划下一步（Agent 循环）
                    val cleanText = cleanFunctionTags(content)
                    val newPrompt = cleanText + "\n\n【沙盒执行结果（已展示给用户）】\n" + results + "\n\n这些结果已经实时推送给用户了。请判断：\n- 如果需要更多操作（用户还没得到完整答案）→ 继续调用函数\n- 如果已经完成 → 直接简短收尾，**不要复述刚才的结果/余额/数字**（用户已看到），最多一句话确认完成，然后结束。"
                    hist.add("assistant" to cleanText)
                    // 再调一次模型，看它是否继续调用函数
                    val nextBody = JSONObject()
                        .put("model", bot.aiModel)
                        .put("messages", JSONArray().put(JSONObject().put("role", "system").put("content", sysFull))
                            .put(JSONObject().put("role", "user").put("content", userText))
                            .put(JSONObject().put("role", "assistant").put("content", cleanText))
                            .put(JSONObject().put("role", "user").put("content", newPrompt)))
                        .put("stream", false)
                        .put("temperature", 0.75)
                        .toString()
                    val nextReq = Request.Builder()
                        .url("http://127.0.0.1:$gatewayPort/v1/chat/completions")
                        .addHeader("Content-Type", "application/json")
                        .post(nextBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
                        .build()
                    val nextContent = try {
                        http.newCall(nextReq).execute().use { resp2 ->
                            if (!resp2.isSuccessful) null
                            else JSONObject(resp2.body?.string().orEmpty())
                                .optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                                ?.optString("content")?.trim().orEmpty()
                        }
                    } catch (e: Exception) { null }
                    if (nextContent.isNullOrBlank()) { content = cleanText + "\n\n" + results; break }
                    content = nextContent
                    // 无限循环保护：若下一轮无调用则结束
                    if (com.qitong.gateway.sandbox.SandboxEngine.parseCalls(content).isEmpty()) break
                }
                if (loopGuard > 0 && content.isNotBlank()) {
                    hist.add("assistant" to content)
                    return content
                }
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
