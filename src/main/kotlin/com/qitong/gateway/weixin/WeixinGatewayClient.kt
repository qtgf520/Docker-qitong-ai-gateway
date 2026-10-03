package com.qitong.gateway.weixin

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 微信 ilink 长轮询连接器（对齐 QqGatewayClient 风格）
 *  - getupdates 35s 长轮询：无消息超时=正常，立即重试
 *  - get_updates_buf 游标续传：断线不丢消息
 *  - ret=-14 风控：自动暂停 1 小时（对齐官方）
 *  - 指数退避重连（2s→60s）
 */
class WeixinGatewayClient(
    val bot: WeixinBot,
    private val api: WeixinApiClient,
    private val onMessage: (WeixinMessage) -> Unit,
    private val onStatusChange: (WeixinBotStatus, String) -> Unit
) {
    private val stopped = AtomicBoolean(false)
    @Volatile private var backoffMs = 2000L
    @Volatile private var buf = ""
    @Volatile private var pausedUntil = 0L

    fun start() {
        stopped.set(false)
        Thread { loop() }.start()
    }

    fun stop() {
        stopped.set(true)
        onStatusChange(WeixinBotStatus.OFFLINE, "已停止")
    }

    private fun loop() {
        while (!stopped.get()) {
            // 风控暂停：ret=-14 后暂停 1 小时
            if (pausedUntil > System.currentTimeMillis()) {
                onStatusChange(WeixinBotStatus.PAUSED, "风控暂停中（剩余 ${(pausedUntil - System.currentTimeMillis()) / 1000}s）")
                try { Thread.sleep(15000) } catch (_: InterruptedException) {}
                continue
            }
            try {
                onStatusChange(WeixinBotStatus.CONNECTING, "长轮询连接中…")
                val resp = api.getUpdates(bot.botToken, buf)
                // ret=-14 风控
                if (resp.optInt("ret", 0) == -14) {
                    pausedUntil = System.currentTimeMillis() + 3600_000L
                    onStatusChange(WeixinBotStatus.PAUSED, "风控 ret=-14，暂停 1 小时")
                    continue
                }
                // 游标续传
                resp.optString("get_updates_buf").takeIf { it.isNotBlank() }?.let { buf = it }
                val msgs = resp.optJSONArray("msgs") ?: JSONArray()
                if (msgs.length() > 0) {
                    onStatusChange(WeixinBotStatus.ONLINE, "在线")
                    for (i in 0 until msgs.length()) {
                        val m = msgs.optJSONObject(i) ?: continue
                        val parsed = parseMessage(m) ?: continue
                        runCatching { onMessage(parsed) }
                    }
                } else {
                    onStatusChange(WeixinBotStatus.ONLINE, "在线（无新消息）")
                }
                backoffMs = 2000L // 成功后重置退避
            } catch (e: Exception) {
                onStatusChange(WeixinBotStatus.ERROR, e.message ?: "长轮询异常")
                // 指数退避重连
                try { Thread.sleep(backoffMs) } catch (_: InterruptedException) {}
                backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
            }
        }
    }

    private fun parseMessage(m: JSONObject): WeixinMessage? {
        // 字段名以官方返回为准（兼容 msg_id/from/content/context_token）
        val msgId = m.optString("msg_id").ifBlank { m.optString("id") }
        if (msgId.isBlank()) return null
        val from = m.optString("from").ifBlank { m.optString("openid") }
        // 文本内容：content 或 item_list[0].content
        var content = m.optString("content")
        if (content.isBlank()) {
            val items = m.optJSONArray("item_list")
            if (items != null && items.length() > 0) content = items.optJSONObject(0)?.optString("content").orEmpty()
        }
        val ctx = m.optString("context_token")
        return WeixinMessage(
            msgId = msgId,
            from = from,
            content = content,
            contextToken = ctx,
            isGroup = m.optBoolean("is_group", false),
            groupOpenid = m.optString("group_openid")
        )
    }
}