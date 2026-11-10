package com.qitong.gateway.mpx

import com.qitong.gateway.db.Database
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ConcurrentHashMap

/**
 * ★ v1.194 公众号消息推送器
 * - 客服消息 API：POST https://api.weixin.qq.com/cgi-bin/message/custom/send?access_token=XXX
 *   认证服务号可用；订阅号借用「48 小时会话窗口」，用户发消息后 48h 内可主动下发
 * - access_token 管理：内存缓存 7000 秒（官方 7200s 过期，留 200s 余量），自动刷新
 * - 发送失败自动重试 1 次（token 过期时强制刷新后重发）
 */
object MpPush {

    data class TokenCache(val token: String, val expireAt: Long)

    private val tokenCache = ConcurrentHashMap<String, TokenCache>()

    private val http = okhttp3.OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    /** 获取 access_token（appid+secret），带内存缓存与自动刷新 */
    fun getAccessToken(appid: String, secret: String): String? {
        if (appid.isBlank() || secret.isBlank()) return null
        tokenCache[appid]?.let { if (System.currentTimeMillis() < it.expireAt) return it.token }
        synchronized(this) {
            tokenCache[appid]?.let { if (System.currentTimeMillis() < it.expireAt) return it.token }
            try {
                val url = "https://api.weixin.qq.com/cgi-bin/token?grant_type=client_credential&appid=$appid&secret=$secret"
                val req = okhttp3.Request.Builder().url(url).get().build()
                http.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    val json = org.json.JSONObject(body)
                    if (json.has("access_token")) {
                        val token = json.getString("access_token")
                        tokenCache[appid] = TokenCache(token, System.currentTimeMillis() + 7000_000L)
                        return token
                    }
                    System.err.println("[MpPush] 获取 access_token 失败: ${json.optString("errmsg")}")
                }
            } catch (e: Exception) {
                System.err.println("[MpPush] 获取 access_token 异常: ${e.message}")
            }
        }
        return null
    }

    /** 发送客服文本消息（失败重试 1 次，自动刷新 token） */
    fun sendText(db: Database, bot: Map<String, Any?>, openid: String, text: String): Boolean {
        if (openid.isBlank() || text.isBlank()) return false
        val appid = (bot["appid"] as? String) ?: ""
        val secret = (bot["appSecret"] as? String) ?: ""
        if (appid.isBlank() || secret.isBlank()) {
            System.err.println("[MpPush] 公众号未配置 appid/secret，无法客服消息推送")
            return false
        }
        val content = text.take(600)  // 客服消息文本最长 600 字符（微信限制）
        try {
            var token = getAccessToken(appid, secret) ?: return false
            // 尝试最多 2 次（第二次强制刷新 token）
            for (attempt in 1..2) {
                val ok = doSend(token, openid, content)
                if (ok) return true
                if (attempt == 1) {
                    tokenCache.remove(appid)   // 缓存失效，强制刷新
                    token = getAccessToken(appid, secret) ?: return false
                }
            }
        } catch (e: Exception) {
            System.err.println("[MpPush] 发送客服消息异常: ${e.message}")
        }
        return false
    }

    private fun doSend(token: String, openid: String, content: String): Boolean {
        return try {
            val bodyJson = """{"touser":"$openid","msgtype":"text","text":{"content":"$content"}}"""
            val req = okhttp3.Request.Builder()
                .url("https://api.weixin.qq.com/cgi-bin/message/custom/send?access_token=$token")
                .post(bodyJson.toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                val json = org.json.JSONObject(body)
                val errcode = json.optInt("errcode", 0)
                if (errcode == 0) true else {
                    // 40001=token 失效/过期；45015=回复超时；48001=未认证服务号不可用客服消息
                    if (errcode == 40001) false else {
                        if (errcode == 48001) System.err.println("[MpPush] 未认证服务号不可用客服消息（errcode=48001）：建议换认证服务号或降级被动回复）")
                        false
                    }
                }
            }
        } catch (_: Exception) { false }
    }
}