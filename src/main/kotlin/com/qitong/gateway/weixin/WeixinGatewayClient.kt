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
                // ★ v87 状态稳定化：只在真正断线/重连时才显示 CONNECTING，长轮询每轮不再跳（避免"连接中/在线"来回闪）
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
                    // 长轮询正常返回（即使无消息）→ 保持 ONLINE，不重复推送"在线（无新消息）"避免刷屏
                    if (backoffMs > 2000L) onStatusChange(WeixinBotStatus.ONLINE, "在线")
                }
                backoffMs = 2000L // 成功后重置退避
            } catch (e: Exception) {
                // ★ v87 显示离线：真正异常才离线（用户要求：报错=离线）
                onStatusChange(WeixinBotStatus.ERROR, e.message ?: "长轮询异常")
                // 指数退避重连
                try { Thread.sleep(backoffMs) } catch (_: InterruptedException) {}
                backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
            }
        }
    }

    private fun parseMessage(m: JSONObject): WeixinMessage? {
        // ★ v69e 对齐官方 inbound.js：from_user_id + item_list[].text_item.text
        val from = m.optString("from_user_id").ifBlank { m.optString("from").ifBlank { m.optString("openid") } }
        val items = m.optJSONArray("item_list")
        var content = ""
        var msgId = m.optString("msg_id").ifBlank { m.optString("id") }
        if (items != null && items.length() > 0) {
            val first = items.optJSONObject(0)
            // 文本：type=1 时 text_item.text
            content = first?.optJSONObject("text_item")?.optString("text").orEmpty()
            if (content.isBlank()) content = first?.optString("content").orEmpty()
            // msg_id 可能在 item 上
            if (msgId.isBlank()) msgId = first?.optString("msg_id").orEmpty()
        }
        if (content.isBlank() && m.has("content")) content = m.optString("content")
        if (msgId.isBlank()) return null
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