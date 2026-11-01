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
    private val onStatusChange: (QqBotStatus, String) -> Unit,
    private val onTokenRefresh: () -> String? = { null }
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
    @Volatile private var consecutiveFailures = 0   // ★ v1.166 连续失败计数（防退避死循环）

    companion object {
        // 生产 / 沙箱 WebSocket 网关
        private const val URL_PROD = "wss://api.sgroup.qq.com/websocket"
        private const val URL_SANDBOX = "wss://sandbox.api.sgroup.qq.com/websocket"
        // GROUP_AND_C2C_EVENT = 1 << 25
        private const val INTENT_GROUP_C2C = 33554432L
        // ★ v1.166 重连策略：指数退避 2s→60s，连续失败超过 10 次后固定 60s 周期重试（不无限叠加）
        private const val MAX_BACKOFF_MS = 60_000L
        private const val MAX_CONSECUTIVE_FAILURES = 10
    }

    @Volatile private var accessToken: String = ""
    // ★ v1.166 心跳 ACK 超时检测：QQ 官方可能静默断连（WS 还在但心跳不回），连续 N 次无 ACK 主动重连
    @Volatile private var heartbeatSentWithoutAck = 0
    @Volatile private var lastHeartbeatAckAt = System.currentTimeMillis()
    private val HEARTBEAT_ACK_TIMEOUT_MS = 45_000L     // 超过 45s 无 ACK 判定心跳超时
    private val HEARTBEAT_MAX_MISSED = 3               // 连续 3 次心跳无 ACK 触发重连

    fun start(accessToken: String) {
        this.accessToken = accessToken
        stopped.set(false)
        heartbeatSentWithoutAck = 0
        lastHeartbeatAckAt = System.currentTimeMillis()
        connect()
    }

    fun start() = start("")

    fun stop() {
        stopped.set(true)
        runCatching { heartbeatTimer?.cancel() }
        runCatching { reconnectTimer?.cancel() }
        runCatching { ws?.close(1000, "bye") }
        onStatusChange(QqBotStatus.OFFLINE, "已停止")
    }

    private fun connect() {
        if (stopped.get()) return
        // ★ 掉线重连：每次连接前尝试重新换取 AccessToken（AppID+AppSecret），避免用过期 token 重连失败
        //   未提供 onTokenRefresh（返回 null）时保持原行为用旧 token；提供但换取失败则暂缓重连
        val fresh = onTokenRefresh()
        if (fresh != null) {
            if (fresh.isNotBlank()) {
                this.accessToken = fresh
            } else {
                // 换取失败：保持离线，等待下次重试（指数退避）
                System.err.println("[QQBot] ${bot.appid} 重连时刷新 AccessToken 失败，稍后重试")
                scheduleReconnect()
                return
            }
        }
        onStatusChange(QqBotStatus.CONNECTING, "连接中…")
        val url = if (bot.useSandbox) URL_SANDBOX else URL_PROD
        val req = Request.Builder().url(url).build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // ★ v1.168 幽灵连接过滤：回调回来时若已被新连接替换/已停止 → 关掉响应体防泄漏，直接返回
                if (stopped.get() || webSocket !== ws) {
                    runCatching { response.body?.close() }
                    runCatching { webSocket.cancel() }
                    return
                }
                System.out.println("[QQBot] ${bot.appid} WebSocket 已连接，等待 HELLO")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (stopped.get() || webSocket !== ws) return
                try {
                    handleFrame(text)
                } catch (e: Exception) {
                    System.err.println("[QQBot] ${bot.appid} 帧处理异常: ${e.message}")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                // ★ v1.168 旧连接的延迟关闭回调不再触发重连（否则与新连接的重连叠加 → 反复抖动）
                if (stopped.get() || webSocket !== ws) return
                System.out.println("[QQBot] ${bot.appid} 连接关闭 code=$code reason=$reason")
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // ★ v1.168 修复「连接失败: null 刷屏 + 连接泄漏」根因：
                //   旧 WS 被替换后其延迟失败回调仍回来，触发 scheduleReconnect 与新连接叠加，
                //   形成「失败→重连→READY→旧连接再报失败→再重连」死循环。
                //   对策：非当前连接的回调一律丢弃，并关闭响应体防泄漏。
                runCatching { response?.body?.close() }
                if (stopped.get() || webSocket !== ws) return
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
                        consecutiveFailures = 0   // ★ v1.166 连上即复位，下次掉线从 2s 重新退避
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
            11 -> { // HeartbeatACK：服务器确认心跳，重置计数
                lastHeartbeatAckAt = System.currentTimeMillis()
                heartbeatSentWithoutAck = 0
            }
            9 -> { // Invalid Session → 重新 Identify
                System.out.println("[QQBot] ${bot.appid} invalid session，重连")
                scheduleReconnect()
            }
        }
    }

    private fun identify() {
        // 新版鉴权：用 AppID+AppSecret 换来的 access_token
        val tok = if (accessToken.isNotBlank()) "QQBot $accessToken" else bot.authHeader(accessToken)
        val payload = JSONObject()
            .put("op", 2)
            .put("d", JSONObject()
                .put("token", tok)
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
            // ★ v1.166 心跳 ACK 超时检测：QQ 静默断连时 WS 不报错，靠 ACK 缺失主动发现并重连
            heartbeatSentWithoutAck++
            val sinceAck = System.currentTimeMillis() - lastHeartbeatAckAt
            if (heartbeatSentWithoutAck >= HEARTBEAT_MAX_MISSED && sinceAck > HEARTBEAT_ACK_TIMEOUT_MS) {
                System.err.println("[QQBot] ${bot.appid} 连续 $heartbeatSentWithoutAck 次心跳无 ACK（${sinceAck / 1000}s），判定静默断连，主动重连")
                runCatching { ws?.close(1001, "heartbeat ack timeout") }
                onStatusChange(QqBotStatus.OFFLINE, "心跳无响应，主动重连（连续 $heartbeatSentWithoutAck 次）")
                heartbeatSentWithoutAck = 0
                scheduleReconnect()
            }
        }
    }

    private fun scheduleReconnect() {
        // ★ v91 修复：先取消旧的重连 Timer，再新建——避免 onClosed/onFailure/invalid 连续触发时叠加多个 Timer 导致反复连接/掉线抖动
        runCatching { heartbeatTimer?.cancel() }
        runCatching { reconnectTimer?.cancel() }
        if (stopped.get()) return
        // ★ v1.166 退避衰减：连续失败 10 次后固定 60s 周期（不再无限翻倍到离谱值），避免「掉线后越等越久」
        consecutiveFailures++
        val delay = if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) MAX_BACKOFF_MS else backoffMs
        onStatusChange(QqBotStatus.OFFLINE, "${delay / 1000}s 后重连（第 $consecutiveFailures 次）")
        reconnectTimer = java.util.Timer("qq-reconnect-${bot.appid}", true).apply {
            schedule(object : java.util.TimerTask() {
                override fun run() { connect() }
            }, delay)
        }
        backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
    }
}
