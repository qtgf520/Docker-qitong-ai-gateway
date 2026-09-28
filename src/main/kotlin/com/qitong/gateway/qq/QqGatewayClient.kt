package com.qitong.gateway.qq

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.fixedRateTimer

/**
 * 单个 QQ 官方机器人的 Gateway WebSocket 长连接。
 * 协议：https://bot.q.qq.com/wiki/develop/api-v2/dev-prepare/interface-framework/api-use.html
 *
 * op 约定：
 *  1  Heartbeat            客户端 -> 服务器
 *  2  Identify            客户端 -> 服务器
 *  6  Resume              客户端 -> 服务器
 * 10  Hello               服务器 -> 客户端（带 heartbeat_interval）
 * 11  HeartbeatACK        服务器 -> 客户端
 *  0  Dispatch(Event)      服务器 -> 客户端（s=t序列, t=事件名, d=数据）
 */
class QqGatewayClient(
    val bot: QqBot,
    private val onGroupMessage: (QqGroupMessage) -> Unit,
    private val onC2cMessage: (QqC2cMessage) -> Unit,
    private val onStatusChange: (QqBotStatus, String) -> Unit
) {
    private val client = OkHttpClient.Builder()
        .pingInterval(0, TimeUnit.SECONDS) // 用业务心跳，不用 OkHttp ping
        .retryOnConnectionFailure(false)
        .build()

    @Volatile private var ws: WebSocket? = null
    @Volatile private var lastSeq: Long? = null
    @Volatile private var sessionId: String? = null
    @Volatile private var heartbeatTimer: java.util.Timer? = null
    @Volatile private var reconnectTimer: java.util.Timer? = null
    private val stopped = AtomicBoolean(false)
    @Volatile private var backoffMs = 2000L

    companion object {
        private const val URL = "wss://api.sgroup.qq.com/websocket"
        // GROUP_AND_C2C_EVENT = 1 << 25
        private const val INTENT_GROUP_C2C = 33554432L
    }

    fun start() {
        stopped.set(false)
        connect()
    }

    fun stop() {
        stopped.set(true)
        runCatching { heartbeatTimer?.cancel() }
        runCatching { reconnectTimer?.cancel() }
        runCatching { ws?.close(1000, "bye") }
        onStatusChange(QqBotStatus.OFFLINE, "已停止")
    }

    private fun connect() {
        if (stopped.get()) return
        onStatusChange(QqBotStatus.CONNECTING, "连接中…")
        val req = Request.Builder().url(URL).build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                System.out.println("[QQBot] ${bot.appid} WebSocket 已连接，等待 HELLO")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    handleFrame(text)
                } catch (e: Exception) {
                    System.err.println("[QQBot] ${bot.appid} 帧处理异常: ${e.message}")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                System.out.println("[QQBot] ${bot.appid} 连接关闭 code=$code reason=$reason")
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                System.err.println("[QQBot] ${bot.appid} 连接失败: ${t.message}")
                onStatusChange(QqBotStatus.ERROR, t.message ?: "连接失败")
                scheduleReconnect()
            }
        })
    }

    private fun handleFrame(raw: String) {
        val obj = JSONObject(raw)
        val op = obj.optInt("op", -1)
        when (op) {
            10 -> { // HELLO
                val interval = obj.optJSONObject("d")?.optLong("heartbeat_interval", 30000L) ?: 30000L
                startHeartbeat(interval)
                identify()
            }
            0 -> { // Dispatch
                lastSeq = if (obj.isNull("s")) null else obj.optLong("s", -1L).takeIf { it >= 0 }
                when (obj.optString("t")) {
                    "READY" -> {
                        sessionId = obj.optJSONObject("d")?.optString("session_id")
                        backoffMs = 2000L
                        onStatusChange(QqBotStatus.ONLINE, "上线 session=$sessionId")
                        System.out.println("[QQBot] ${bot.appid} READY，机器人上线")
                    }
                    "GROUP_AT_MESSAGE_CREATE" -> {
                        val d = obj.optJSONObject("d") ?: return
                        onGroupMessage(
                            QqGroupMessage(
                                msgId = d.optString("id"),
                                groupOpenid = d.optString("group_openid"),
                                content = d.optString("content"),
                                memberOpenid = d.optJSONObject("author")?.optString("member_openid") ?: ""
                            )
                        )
                    }
                    "C2C_MESSAGE_CREATE" -> {
                        val d = obj.optJSONObject("d") ?: return
                        onC2cMessage(
                            QqC2cMessage(
                                msgId = d.optString("id"),
                                userOpenid = d.optJSONObject("author")?.optString("user_openid") ?: "",
                                content = d.optString("content")
                            )
                        )
                    }
                }
            }
            11 -> { /* HeartbeatACK，无需处理 */ }
            9 -> { // Invalid Session → 重新 Identify
                System.out.println("[QQBot] ${bot.appid} invalid session，重连")
                scheduleReconnect()
            }
        }
    }

    private fun identify() {
        val payload = JSONObject()
            .put("op", 2)
            .put("d", JSONObject()
                .put("token", bot.authHeader())
                .put("intents", INTENT_GROUP_C2C)
                .put("shard", org.json.JSONArray("[0,1]"))
            )
        ws?.send(payload.toString())
    }

    private fun startHeartbeat(intervalMs: Long) {
        runCatching { heartbeatTimer?.cancel() }
        heartbeatTimer = fixedRateTimer(name = "qq-hb-${bot.appid}", daemon = true, initialDelay = intervalMs, period = intervalMs) {
            val seq = lastSeq
            val body = JSONObject().put("op", 1).put("d", if (seq == null) JSONObject.NULL else seq)
            runCatching { ws?.send(body.toString()) }
        }
    }

    private fun scheduleReconnect() {
        runCatching { heartbeatTimer?.cancel() }
        if (stopped.get()) return
        onStatusChange(QqBotStatus.OFFLINE, "${backoffMs / 1000}s 后重连")
        reconnectTimer = java.util.Timer("qq-reconnect-${bot.appid}", true).apply {
            schedule(object : java.util.TimerTask() {
                override fun run() { connect() }
            }, backoffMs)
        }
        backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
    }
}
