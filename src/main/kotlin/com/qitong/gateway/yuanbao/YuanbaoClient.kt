package com.qitong.gateway.yuanbao

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 元宝 Bot 客户端 —— WebSocket 直连腾讯元宝开放平台（对齐 OpenClaw 插件协议）
 *
 * 协议链路（已实测验证）：
 *  1. POST /api/v5/robotLogic/sign-token（HMAC-SHA256 签名）→ 拿 bot_id + token
 *  2. wss://bot-wss.yuanbao.tencent.com/wss/connection → AuthBind（bizId=ybBot, uid=bot_id）
 *  3. 心跳 ping（30s 间隔）
 *  4. PushMsg 收消息（InboundMessagePush），send_c2c_message / send_group_message 发消息
 */
class YuanbaoClient(
    val botId: Long,
    val name: String,
    val appKey: String,
    val appSecret: String,
    val aiModel: String,
    val systemPrompt: String,
    private val onMessage: (YuanbaoInbound) -> Unit,
    private val onStatus: (String, String) -> Unit   // (状态, 说明)
) {
    companion object {
        private const val API_DOMAIN = "bot.yuanbao.tencent.com"
        private const val WS_URL = "wss://bot-wss.yuanbao.tencent.com/wss/connection"
        private const val MAX_RETRY_MS = 60_000L
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Volatile private var ws: WebSocket? = null
    @Volatile private var connected = false
    private val stopped = AtomicBoolean(false)
    @Volatile private var backoffMs = 2000L
    @Volatile private var signTokenCache: Pair<String, Long>? = null   // (token, expireAt)
    @Volatile var botUid: String = ""
    @Volatile var botSource: String = "web"
    private val seqNo = AtomicInteger(0)
    private val callbacks = ConcurrentHashMap<String, (String) -> Unit>()  // msgId -> onRsp

    /** 认证：HMAC-SHA256 签名获取临时 token（30 天有效） */
    private fun signToken(): Pair<String, String> {  // return (bot_id, token)
        signTokenCache?.let { (tok, exp) -> if (exp > System.currentTimeMillis() + 60_000) return botUid to tok }
        val nonce = java.util.UUID.randomUUID().toString().replace("-", "")
        val bj = java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Shanghai"))
        val ts = bj.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX"))
        val plain = nonce + ts + appKey + appSecret
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(appSecret.toByteArray(), "HmacSHA256"))
        val signature = mac.doFinal(plain.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val body = JSONObject()
            .put("app_key", appKey).put("nonce", nonce)
            .put("signature", signature).put("timestamp", ts)
            .toString()
        val req = Request.Builder()
            .url("https://$API_DOMAIN/api/v5/robotLogic/sign-token")
            .addHeader("Content-Type", "application/json")
            .addHeader("X-AppVersion", "2.18.3")
            .addHeader("X-OperationSystem", "Linux")
            .addHeader("X-Instance-Id", "16")
            .addHeader("X-Bot-Version", "2026.9.9")
            .post(body.toRequestBody(JSON))
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw RuntimeException("sign-token HTTP ${resp.code}: $text")
            val j = JSONObject(text)
            if (j.optInt("code") != 0) throw RuntimeException("sign-token code=${j.optInt("code")} msg=${j.optString("msg")}")
            val data = j.optJSONObject("data") ?: throw RuntimeException("sign-token no data")
            val bid = data.optString("bot_id")
            val token = data.optString("token")
            botUid = bid
            botSource = data.optString("source").ifBlank { "web" }
            signTokenCache = token to (System.currentTimeMillis() + (data.optLong("duration", 259200L) * 1000) - 60_000L)
            return bid to token
        }
    }

    fun start(useExistingToken: String? = null) {
        stopped.set(false)
        Thread { runLoop(useExistingToken) }.start()
    }

    fun stop() {
        stopped.set(true)
        try { ws?.close(1000, "stopped") } catch (_: Exception) {}
        ws = null
    }

    private fun runLoop(preToken: String?) {
        while (!stopped.get()) {
            try {
                onStatus("连接中", "正在获取凭证…")
                val token = preToken?.takeIf { it.isNotBlank() } ?: signToken().second
                connectAndServe(token)
                backoffMs = 2000L
            } catch (e: Exception) {
                onStatus("离线", "连接异常: ${e.message}")
                try { Thread.sleep(backoffMs) } catch (_: InterruptedException) { break }
                backoffMs = (backoffMs * 2).coerceAtMost(MAX_RETRY_MS)
            }
        }
    }

    private fun connectAndServe(token: String) {
        val req = Request.Builder().url(WS_URL).build()
        val listener = object : WebSocketListener() {
            override fun onOpen(w: WebSocket, response: Response) {
                connected = true
                onStatus("连接中", "已连接，认证中…")
                // AuthBind
                val authData = YuanbaoProto.encodeAuthBind(botUid, botSource, token)
                val head = YuanbaoProto.Head(cmdType = 0, cmd = "auth-bind", seqNo = seqNo.incrementAndGet(), msgId = "auth-${System.currentTimeMillis()}", module = "conn_access")
                w.send(YuanbaoProto.encodeConnMsg(head, authData).toByteString())
            }
            override fun onMessage(w: WebSocket, bytes: ByteString) {
                handleFrame(bytes.toByteArray(), w)
            }
            override fun onMessage(w: WebSocket, text: String) { /* 二进制协议，忽略文本 */ }
            override fun onFailure(w: WebSocket, t: Throwable, response: Response?) {
                connected = false
                onStatus("离线", "连接断开: ${t.message}")
            }
            override fun onClosing(w: WebSocket, code: Int, reason: String) {
                connected = false
                w.close(1000, null)
            }
            override fun onClosed(w: WebSocket, code: Int, reason: String) {
                connected = false
                onStatus("离线", "连接已关闭")
            }
        }
        ws = http.newWebSocket(req, listener)
        // 阻塞等待直到停止
        while (!stopped.get()) {
            try { Thread.sleep(1000) } catch (_: InterruptedException) { return }
            if (!connected) return  // 断线 → 让外层重连
        }
    }

    private fun handleFrame(data: ByteArray, w: WebSocket) {
        try {
            val (head, payload) = YuanbaoProto.decodeConnMsg(data)
            when {
                head.cmd == "auth-bind" -> {
                    val (code, msg) = YuanbaoProto.decodeAuthBindRsp(payload)
                    if (code == 0) {
                        onStatus("在线", "认证成功")
                        // 推送一条 sync_information（保持对齐官方插件行为，非必需）
                        sendBiz(w, "sync_information", "ybBot", ByteArray(0))
                    } else {
                        onStatus("认证失败", "auth-bind code=$code msg=$msg")
                        w.close(1000, "auth-fail")
                    }
                }
                head.cmd == "ping" -> { /* ping 由 OkHttp pingInterval 兜底 + 服务端回包忽略 */ }
                head.cmd == "kickout" -> { onStatus("被踢下线", "kickout") }
                head.cmdType == 2 -> {  // Push
                    val push = YuanbaoProto.decodePushMsg(payload)
                    if (push.cmd == "inbound_message_push" || push.data.isNotEmpty()) {
                        val inbound = YuanbaoProto.decodeInboundMessagePush(push.data)
                        if (inbound != null && (inbound.text.isNotBlank() || inbound.imageDesc.isNotBlank())) {
                            // 转成对外 YuanbaoInbound（对齐微信 WeixinMessage 语义）
                            onMessage(
                                YuanbaoInbound(
                                    msgId = inbound.msgId,
                                    fromAccount = inbound.fromAccount,
                                    toAccount = inbound.toAccount,
                                    senderNickname = inbound.senderNickname,
                                    groupCode = inbound.groupCode,
                                    groupName = inbound.groupName,
                                    isGroup = inbound.clawMsgType == 1 || inbound.groupCode.isNotBlank(),
                                    text = inbound.text,
                                    imageDesc = inbound.imageDesc
                                )
                            )
                        }
                    }
                }
                head.cmdType == 1 -> {  // Response
                    callbacks.remove(head.msgId)?.invoke(String(payload.map { if (it == 0.toByte()) 0 else it }.toByteArray()))
                }
            }
        } catch (e: Exception) {
            // 忽略未知帧
        }
    }

    private fun sendBiz(w: WebSocket, cmd: String, module: String, data: ByteArray) {
        try {
            val head = YuanbaoProto.Head(cmdType = 0, cmd = cmd, seqNo = seqNo.incrementAndGet(), msgId = "biz-${System.currentTimeMillis()}-${seqNo.get()}", module = if (module == "ybBot") "yuanbao_openclaw_proxy" else module)
            w.send(YuanbaoProto.encodeConnMsg(head, data).toByteString())
        } catch (_: Exception) {}
    }

    /** 发送私聊消息 */
    fun sendC2C(toAccount: String, text: String): Boolean {
        if (!connected) return false
        val w = ws ?: return false
        val msgId = "c2c-${System.currentTimeMillis()}"
        val data = YuanbaoProto.encodeSendC2C(msgId, toAccount, botUid, text)
        sendBiz(w, "send_c2c_message", "yuanbao_openclaw_proxy", data)
        return true
    }

    /** 发送群消息（toAccount 传 fromAccount，groupCode 指定群） */
    fun sendGroup(toAccount: String, text: String, groupCode: String): Boolean {
        if (!connected) return false
        val w = ws ?: return false
        val msgId = "grp-${System.currentTimeMillis()}"
        val data = YuanbaoProto.encodeSendGroupMsg(msgId, groupCode, botUid, toAccount, text)
        sendBiz(w, "send_group_message", "yuanbao_openclaw_proxy", data)
        return true
    }

    fun isOnline(): Boolean = connected
}

/** 元宝入站消息（对齐微信 WeixinMessage 语义） */
data class YuanbaoInbound(
    val msgId: String,
    val fromAccount: String,
    val toAccount: String,
    val senderNickname: String,
    val groupCode: String,
    val groupName: String,
    val isGroup: Boolean,          // clawMsgType=1 群 / 2 私聊
    val text: String,
    val imageDesc: String
)