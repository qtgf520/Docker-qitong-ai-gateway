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

    // ============ 文字游戏状态（每用户独立） ============
    private val gameState = ConcurrentHashMap<String, MutableMap<String, Any>>()

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
            }
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
        db.touchQqUser(msg.memberOpenid)
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
        db.touchQqUser(msg.userOpenid)
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
                    val at = api.getAccessToken(bot)
                    if (at.isNullOrBlank()) { send("禁言失败（获取凭证失败）"); return }
                    if (api.setGroupMute(bot, at, groupOpenid, true)) send("已开启全员禁言") else send("全员禁言失败（需机器人是群管理员）")
                    db.addQqLog(bot.appid, groupOpenid, userOpenid, "command", "全员禁言", System.currentTimeMillis() - t0)
                    return
                }
                "mute_off" -> {
                    if (groupOpenid.isBlank()) return
                    val at = api.getAccessToken(bot)
                    if (at.isNullOrBlank()) { send("解除失败（获取凭证失败）"); return }
                    if (api.setGroupMute(bot, at, groupOpenid, false)) send("已解除全员禁言") else send("解除失败")
                    db.addQqLog(bot.appid, groupOpenid, userOpenid, "command", "解除全员禁言", System.currentTimeMillis() - t0)
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
            val result = com.qitong.gateway.http.SkillExecutor.execute(db, code, param, 0)
            send(result)
            db.addQqLog(bot.appid, groupOpenid, userOpenid, "skill", "[$code] $text", System.currentTimeMillis() - t0)
            return
        }
        // 0.6) 文字游戏（每用户独立，无需权限门槛，娱乐）
        val game = handleGame(userOpenid, text)
        if (game != null) { send(game); db.addQqLog(bot.appid, groupOpenid, userOpenid, "game", text, System.currentTimeMillis() - t0); return }

        // 0.7) 沙盒 Linux 终端操控（需管理级权限，远程执行 shell 命令）
        if (t.startsWith("终端 ", true) || t.startsWith("执行 ", true) || t.startsWith("运行 ", true)) {
            val qqUser = db.getQqUser(userOpenid)
            val perm = (qqUser?.get("permLevel") as? Number)?.toInt() ?: 1
            if (perm < 3) { send("⛔ 终端操控需要管理级权限(3级)，您当前为 ${permLabel(perm)}"); return }
            val cmd = t.substringAfter(" ").trim()
            if (cmd.isBlank()) { send("⚠️ 语法：终端 <命令>（如：终端 ls -la）"); return }
            if (cmd.length > 500) { send("⚠️ 命令太长（限500字符）"); return }
            // 危险命令黑名单
            val dangerous = listOf("rm -rf /", "mkfs", "dd if=", "shutdown", "reboot", ":(){", "format", "fdisk")
            if (dangerous.any { cmd.contains(it) }) { send("⛔ 危险命令已拦截"); return }
            val result = runShell(cmd)
            val shown = result.take(800)
            send("🖥 终端执行：\n$ cmd\n\n```\n$shown\n```")
            db.addQqLog(bot.appid, groupOpenid, userOpenid, "shell", "[$cmd] ${result.take(80)}", System.currentTimeMillis() - t0)
            return
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

        // 2) 大模型对话（人格系统 qtai-sj：需 permLevel>=1 才允许；禁止=0 不响应）
        val u = db.getQqUser(userOpenid)
        val perm = (u?.get("permLevel") as? Number)?.toInt() ?: 1
        if (perm <= 0) { send("⛔ 您已被禁止使用机器人，请联系管理员"); return }
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
