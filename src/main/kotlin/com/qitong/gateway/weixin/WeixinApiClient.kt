package com.qitong.gateway.weixin

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 微信 ilink 官方 Bot API 客户端（协议实测自 @tencent-weixin/openclaw-weixin@2.4.9）
 *  - Base: https://ilinkai.weixin.qq.com
 *  - 登录: POST ilink/bot/get_bot_qrcode?bot_type=3 → 长轮询 GET ilink/bot/get_qrcode_status?qrcode=xxx
 *  - 收消息: POST ilink/bot/getupdates（长轮询 35s，回传 get_updates_buf 游标）
 *  - 发消息: POST ilink/bot/sendmessage（回传 context_token）
 *  - 请求头: iLink-App-Id=bot / iLink-App-ClientVersion / AuthorizationType=ilink_bot_token
 *            X-WECHAT-UIN=随机base64 / Authorization: Bearer <token>
 */
class WeixinApiClient {

    companion object {
        const val BASE_URL = "https://ilinkai.weixin.qq.com"
        const val ILINK_APP_ID = "bot"
        const val BOT_TYPE = "3"
        // 版本号 -> uint32 编码 0x00MMNNPP（对齐官方 buildClientVersion）
        fun clientVersion(v: String): String {
            val parts = v.split(".").map { it.toIntOrNull() ?: 0 }
            val major = parts.getOrElse(0) { 0 }; val minor = parts.getOrElse(1) { 0 }; val patch = parts.getOrElse(2) { 0 }
            val n = ((major and 0xff) shl 16) or ((minor and 0xff) shl 8) or (patch and 0xff)
            return n.toString()
        }
    }

    private val jsonCt = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()
    private val random = SecureRandom()

    // ★ v69 令牌缓存（对齐 QQ v64 经验）：token 不每次重取，失败降级旧 token
    private class TokenCache(val token: String, val expireAt: Long)
    private val tokenCache = ConcurrentHashMap<String, TokenCache>()

    private fun randomWechatUin(): String {
        val bytes = ByteArray(4)
        random.nextBytes(bytes)
        val uint32 = ((bytes[0].toLong() and 0xFF) shl 24) or ((bytes[1].toLong() and 0xFF) shl 16) or
            ((bytes[2].toLong() and 0xFF) shl 8) or (bytes[3].toLong() and 0xFF)
        return Base64.getEncoder().encodeToString(uint32.toString().toByteArray())
    }

    private fun commonHeaders(extra: Map<String, String> = emptyMap()): Map<String, String> {
        val h = mutableMapOf(
            "iLink-App-Id" to ILINK_APP_ID,
            "iLink-App-ClientVersion" to clientVersion("2.4.9"),
            "Content-Type" to "application/json",
            "AuthorizationType" to "ilink_bot_token",
            "X-WECHAT-UIN" to randomWechatUin()
        )
        h.putAll(extra)
        return h
    }

    private fun apiPost(endpoint: String, body: String, token: String? = null, timeoutMs: Long = 40000): JSONObject {
        val reqBuilder = Request.Builder()
            .url(BASE_URL + "/" + endpoint)
            .post(body.toRequestBody(jsonCt))
        commonHeaders(if (token.isNullOrBlank()) emptyMap() else mapOf("Authorization" to "Bearer $token"))
            .forEach { (k, v) -> reqBuilder.header(k, v) }
        val req = reqBuilder.build()
        val c = if (timeoutMs > 0) {
            OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(timeoutMs, TimeUnit.SECONDS).build()
        } else client
        c.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}: ${text.take(200)}")
            return JSONObject(if (text.isBlank()) "{}" else text)
        }
    }

    private fun apiGet(endpoint: String, timeoutMs: Long = 40000): JSONObject {
        val reqBuilder = Request.Builder()
            .url(BASE_URL + "/" + endpoint)
            .get()
        commonHeaders().forEach { (k, v) -> reqBuilder.header(k, v) }
        val req = reqBuilder.build()
        val c = if (timeoutMs > 0) {
            OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(timeoutMs, TimeUnit.SECONDS).build()
        } else client
        c.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}: ${text.take(200)}")
            return JSONObject(if (text.isBlank()) "{}" else text)
        }
    }

    /** 拉取登录二维码：返回 (qrcode, qrcodeUrl) */
    fun fetchQrCode(localTokenList: List<String> = emptyList()): Pair<String, String> {
        val body = JSONObject().put("local_token_list", JSONArray(localTokenList)).toString()
        val resp = apiPost("ilink/bot/get_bot_qrcode?bot_type=$BOT_TYPE", body)
        val qrcode = resp.optString("qrcode")
        val url = resp.optString("qrcode_url").ifBlank { resp.optString("url") }
        if (qrcode.isBlank()) throw RuntimeException("二维码获取失败: ${resp.toString().take(200)}")
        return qrcode to url
    }

    /** 长轮询二维码状态（wait/scaned/confirmed/expired/need_verifycode/scaned_but_redirect/binded_redirect） */
    fun pollQrStatus(qrcode: String, verifyCode: String? = null, timeoutMs: Long = 35000): JSONObject {
        var ep = "ilink/bot/get_qrcode_status?qrcode=${java.net.URLEncoder.encode(qrcode, "UTF-8")}"
        if (!verifyCode.isNullOrBlank()) ep += "&verify_code=${java.net.URLEncoder.encode(verifyCode, "UTF-8")}"
        return apiGet(ep, timeoutMs)
    }

    /**
     * 长轮询收消息。返回解析后的响应（ret/msgs/get_updates_buf）
     * 35s 无消息超时属正常：返回 ret=0 空消息，调用方直接重试
     */
    fun getUpdates(token: String, buf: String, timeoutMs: Long = 35000): JSONObject {
        val body = JSONObject()
            .put("get_updates_buf", buf)
            .put("base_info", JSONObject().put("channel_version", "2.4.9").put("bot_agent", "QitongAI/1.0"))
            .toString()
        return try {
            apiPost("ilink/bot/getupdates", body, token, timeoutMs)
        } catch (e: Exception) {
            // 长轮询超时 = 正常控制流，返回空消息
            if (e.message?.contains("timeout", true) == true || e.message?.contains("HTTP 504", true) == true) {
                JSONObject().put("ret", 0).put("msgs", JSONArray()).put("get_updates_buf", buf)
            } else throw e
        }
    }

    /** 发文本消息：回传 context_token 表示成功 */
    fun sendMessage(token: String, to: String, text: String, contextToken: String? = null): JSONObject {
        val item = JSONObject().put("type", 1).put("content", text.take(2000))
        val body = JSONObject()
            .put("to", to)
            .put("context_token", contextToken ?: "")
            .put("item_list", JSONArray().put(item))
            .toString()
        return apiPost("ilink/bot/sendmessage", body, token, 15000)
    }

    /** 发送输入状态（1=正在输入） */
    fun sendTyping(token: String, to: String): Boolean = runCatching {
        val body = JSONObject().put("to", to).put("type", 1).toString()
        apiPost("ilink/bot/sendtyping", body, token, 10000)
        true
    }.getOrDefault(false)
}
