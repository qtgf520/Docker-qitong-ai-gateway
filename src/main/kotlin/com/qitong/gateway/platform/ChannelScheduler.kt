package com.qitong.gateway.platform

import com.qitong.gateway.db.Database
import com.qitong.gateway.sandbox.CronExpression
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.ConcurrentHashMap

/**
 * ★ v1.248 渠道定时推送：cron 到点把内容推送到飞书/钉钉/Slack/Discord/自定义 Webhook
 *  - 单实例常驻协程，每 20 秒检查一次
 *  - 每条计划记住下次触发时间（启动时从当前时刻重算），重启不补发历史
 *  - 内容支持 {date} {time} {channel} {name} 变量
 */
object ChannelScheduler {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var started = false
    private val nextAt = ConcurrentHashMap<Long, Long>()

    fun start(db: Database) {
        if (started) return
        started = true
        scope.launch {
            println("[ChannelScheduler] 渠道定时推送已启动（cron 5 字段）")
            while (isActive) {
                runCatching { tick(db) }.onFailure { e -> System.err.println("[ChannelScheduler] tick 失败：${e.message}") }
                delay(20_000L)
            }
        }
    }

    fun nextRunOf(db: Database, id: Long, cron: String): Long? =
        runCatching { CronExpression.parse(cron)?.next(System.currentTimeMillis()) }.getOrNull()

    private fun tick(db: Database) {
        val now = System.currentTimeMillis()
        val minKey = now / 60_000
        val schedules = db.getChannelSchedules().filter { (it["enabled"] as? Boolean) == true }
        for (sch in schedules) {
            val id = sch["id"] as Long
            val cron = (sch["cron"] as? String).orEmpty()
            val expr = CronExpression.parse(cron) ?: continue
            val due = nextAt[id] ?: expr.next(now)?.also { nextAt[id] = it } ?: continue
            if (now < due) continue
            // 同一分钟内只触发一次
            if ((sch["lastRun"] as? Long ?: 0L) / 60_000 == minKey) { nextAt[id] = expr.next(now) ?: continue; continue }
            val targetType = (sch["targetType"] as? String ?: "channel").lowercase()
            val targetId = (sch["targetId"] as? String).orEmpty().trim()
            val ch = if (targetType == "channel") db.getChannelById(targetId.toLongOrNull() ?: (sch["channelId"] as Long)) else null
            val label = ch?.get("name")?.toString() ?: "$targetType:$targetId"
            val aiPrompt = (sch["aiPrompt"] as? String).orEmpty().trim()
            val fake = mapOf("name" to label, "type" to targetType, "id" to 0L)
            val text = if (aiPrompt.isNotBlank()) ChannelManager.generate(db, aiPrompt) else render((sch["content"] as? String).orEmpty(), fake, sch)
            if (text.isBlank()) { nextAt[id] = expr.next(now) ?: Long.MAX_VALUE; continue }
            val (ok, detail) = when (targetType) {
                "qq_group" -> {
                    val sent = com.qitong.gateway.qq.QqBotManager.sendActiveGreetToGroup(targetId, text)
                    sent to (if (sent) "QQ群 $targetId 已发送" else "QQ群发送失败（群 openid 不对或机器人未启用）")
                }
                "weixin" -> {
                    val sent = com.qitong.gateway.weixin.WeixinBotManager.sendActiveGreet(targetId, text)
                    sent to (if (sent) "微信 $targetId 已发送" else "微信发送失败（openid 不对或机器人离线）")
                }
                else -> {
                    val c = ch ?: run { nextAt[id] = expr.next(now) ?: Long.MAX_VALUE; continue }
                    ChannelManager.send(c, text)
                }
            }
            if (ch != null) db.addChannelLog(ch["id"] as Long, "sched", text, ok, detail)
            db.markChannelScheduleRun(id, now)
            nextAt[id] = expr.next(now) ?: Long.MAX_VALUE
            println("[ChannelScheduler] 推送 #$id「${sch["name"]}」→ $label：ok=$ok $detail")
        }
    }

    fun render(tpl: String, ch: Map<String, Any?>, sch: Map<String, Any?>): String {
        val df = SimpleDateFormat("yyyy-MM-dd")
        val tf = SimpleDateFormat("HH:mm")
        val d = Date()
        return tpl.replace("{date}", df.format(d)).replace("{time}", tf.format(d))
            .replace("{channel}", (ch["name"] as? String) ?: "")
            .replace("{name}", (sch["name"] as? String) ?: "")
    }
}
