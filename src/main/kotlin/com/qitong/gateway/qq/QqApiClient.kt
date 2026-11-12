package com.qitong.gateway.qq

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * QQ 开放平台 REST API 客户端（发消息/鉴权）
 * 新版鉴权（Token 已弃用，2026-07 官方切换）：
 *   POST https://api.bot.qq.com/app/getAppAccessToken 用 appId+clientSecret 换 access_token（7200s）
 *   Authorization: QQBot {access_token}
 * 沙箱环境 base 与生产不同：sandbox.api.sgroup.qq.com
 */
class QqApiClient {

    // 生产 base
    private val prodBase = "https://api.sgroup.qq.com"
    // 沙箱 base（换 token 用 bot.qq.com 沙箱地址）
    private val prodTokenBase = "https://api.bot.qq.com"
    private val sandboxBase = "https://sandbox.api.sgroup.qq.com"
    private val sandboxTokenBase = "https://sandbox.api.sgroup.qq.com"
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val jsonCt = "application/json; charset=utf-8".toMediaType()

    // ★ v64 令牌缓存：避免每次消息都现取（过夜掉线根因）+ 失败自动重试用旧 token
    // ★ v1.207 提升为静态伴生共享：QqGatewayClient 的 invalid session 处理器也要能清掉它（否则清的是别的实例缓存，死 token 无限循环）
    private class TokenCache(val token: String, val expireAt: Long)
    companion object {
        private val tokenCache = ConcurrentHashMap<String, TokenCache>()

        /** ★ v1.207 静态强制刷新：清掉指定 bot 的 token 缓存（供 QqGatewayClient invalid session 处理调用） */
        @JvmStatic
        fun forceRefreshTokenKey(appid: String, useSandbox: Boolean) {
            tokenCache.remove("$appid:$useSandbox")
        }
    }

    /** 用 AppID + AppSecret 换取 AccessToken（新版鉴权；带缓存，过期自动刷新） */
    @Synchronized
    fun getAccessToken(bot: QqBot): String? {
        val key = "${bot.appid}:${bot.useSandbox}"
        val secret = bot.appSecret.ifBlank { bot.token }
        if (secret.isBlank()) return null
        // 缓存有效期内直接返回（7200s 有效，提前 300s 刷新）
        val cached = tokenCache[key]
        if (cached != null && System.currentTimeMillis() < cached.expireAt) return cached.token
        // 缓存过期/缺失 → 换新 token
        val url = (if (bot.useSandbox) sandboxTokenBase else prodTokenBase) + "/app/getAppAccessToken"
        val payload = JSONObject()
            .put("appId", bot.appid)
            .put("clientSecret", secret)
            .toString()
        return try {
            val req = Request.Builder()
                .url(url)
                .addHeader("Content-Type", "application/json")
                .post(payload.toRequestBody(jsonCt))
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    System.err.println("[QQBot] 获取AccessToken失败 ${bot.appid}: HTTP ${resp.code} $body")
                    // ★ v1.207 断登根治：换 token 失败时绝不降级返回缓存里的死 token！
                    //   死 token 会导致 identify 一直 INVALID_SESSION → 无限重连循环（invalid session 265次/READY 3次）
                    //   直接清缓存返回 null，由上层走「换取失败稍后重试」分支（不再拿死 token 反复 identify）
                    tokenCache.remove(key)
                    return null
                }
                val token = JSONObject(body).optString("access_token").ifBlank { null }
                if (token != null) {
                    tokenCache[key] = TokenCache(token, System.currentTimeMillis() + 7200_000L - 300_000L)
                    return token
                }
                tokenCache.remove(key)
                null
            }
        } catch (e: Exception) {
            System.err.println("[QQBot] 获取AccessToken异常 ${bot.appid}: ${e.message}")
            // ★ v1.207 网络异常也不降级死 token（同上：避免拿失效 token 死循环 identify）
            tokenCache.remove(key)
            null
        }
    }

    private fun base(bot: QqBot): String = if (bot.useSandbox) sandboxBase else prodBase

    /** 发送群消息（★v1.186 401 自动刷新 AccessToken 重试；★v1.207 重试次数 1→2 + 二次刷新退避） */
    fun sendGroupMessage(bot: QqBot, accessToken: String, groupOpenid: String, content: String, msgId: String?): Boolean {
        var code = groupRaw(bot, accessToken, groupOpenid, content, msgId)
        var newTok: String? = null
        if (code == 401) {
            System.err.println("[QQBot] 群消息发送 401，强制刷新 AccessToken 后重试 ${bot.appid} -> $groupOpenid")
            forceRefreshToken(bot)
            newTok = getAccessToken(bot)
            if (!newTok.isNullOrBlank() && newTok != accessToken) {
                try { Thread.sleep(300) } catch (_: Exception) {}
                code = groupRaw(bot, newTok, groupOpenid, content, msgId)
            }
        }
        // ★ v1.207 二次刷新重试：一次刷新后仍 401（token 缓存竞态/官方未即时生效），再刷一次+更长退避
        if (code == 401) {
            System.err.println("[QQBot] 群消息发送仍 401，二次刷新 AccessToken 重试 ${bot.appid} -> $groupOpenid")
            forceRefreshToken(bot)
            try { Thread.sleep(800) } catch (_: Exception) {}
            newTok = getAccessToken(bot)
            if (!newTok.isNullOrBlank()) code = groupRaw(bot, newTok, groupOpenid, content, msgId)
        }
        return code == 200
    }

    /** 群消息发送原始请求，返回 HTTP 状态码 */
    private fun groupRaw(bot: QqBot, accessToken: String, groupOpenid: String, content: String, msgId: String?): Int {
        val payload = buildString {
            append("{\"content\":")
            append(org.json.JSONObject.quote(content))
            append(",\"msg_type\":0")
            if (!msgId.isNullOrBlank()) {
                append(",\"msg_id\":")
                append(org.json.JSONObject.quote(msgId))
            }
            append("}")
        }
        val req = Request.Builder()
            .url("${base(bot)}/v2/groups/$groupOpenid/messages")
            .addHeader("Authorization", bot.authHeader(accessToken))
            .addHeader("Content-Type", "application/json")
            .post(payload.toRequestBody(jsonCt))
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    System.err.println("[QQBot] 群消息发送失败 ${bot.appid} -> $groupOpenid : HTTP ${resp.code} $body")
                }
                resp.code
            }
        } catch (e: Exception) {
            System.err.println("[QQBot] 群发送异常 ${bot.appid} -> $groupOpenid : ${e.message}")
            -1
        }
    }

    /** 发送私聊消息（★v1.186 401 自动刷新 AccessToken 重试；★v1.207 二次刷新重试） */
    fun sendC2cMessage(bot: QqBot, accessToken: String, userOpenid: String, content: String, msgId: String?): Boolean {
        var code = c2cRaw(bot, accessToken, userOpenid, content, msgId)
        var newTok: String? = null
        if (code == 401) {
            // AccessToken 已被 QQ 端吊销/过期（缓存判断 7200s 但不保证官方不提前失效）→ 强制清缓存换新 token 重试一次
            System.err.println("[QQBot] 私聊发送 401，强制刷新 AccessToken 后重试 ${bot.appid} -> $userOpenid")
            forceRefreshToken(bot)
            newTok = getAccessToken(bot)
            if (!newTok.isNullOrBlank() && newTok != accessToken) {
                try { Thread.sleep(300) } catch (_: Exception) {}
                code = c2cRaw(bot, newTok, userOpenid, content, msgId)
            }
        }
        if (code == 401) {
            System.err.println("[QQBot] 私聊发送仍 401，二次刷新 AccessToken 重试 ${bot.appid} -> $userOpenid")
            forceRefreshToken(bot)
            try { Thread.sleep(800) } catch (_: Exception) {}
            newTok = getAccessToken(bot)
            if (!newTok.isNullOrBlank()) code = c2cRaw(bot, newTok, userOpenid, content, msgId)
        }
        return code == 200
    }

    /** 私聊发送原始请求，返回 HTTP 状态码 */
    private fun c2cRaw(bot: QqBot, accessToken: String, userOpenid: String, content: String, msgId: String?): Int {
        val payload = buildString {
            append("{\"content\":")
            append(org.json.JSONObject.quote(content))
            append(",\"msg_type\":0")
            if (!msgId.isNullOrBlank()) {
                append(",\"msg_id\":")
                append(org.json.JSONObject.quote(msgId))
            }
            append("}")
        }
        val req = Request.Builder()
            .url("${base(bot)}/v2/users/$userOpenid/messages")
            .addHeader("Authorization", bot.authHeader(accessToken))
            .addHeader("Content-Type", "application/json")
            .post(payload.toRequestBody(jsonCt))
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    System.err.println("[QQBot] 私聊消息发送失败 ${bot.appid} -> $userOpenid : HTTP ${resp.code} $body")
                }
                resp.code
            }
        } catch (e: Exception) {
            System.err.println("[QQBot] 私聊发送异常 ${bot.appid} -> $userOpenid : ${e.message}")
            -1
        }
    }

    /** ★v1.186 强制清除 token 缓存（401 兜底用，下次 getAccessToken 真正换新）；★v1.207 改走静态共享缓存 */
    fun forceRefreshToken(bot: QqBot) {
        forceRefreshTokenKey(bot.appid, bot.useSandbox)
    }

    /** 发送群卡片消息（markdown 模板，msg_type=2）。content 为 markdown 原文 */
    fun sendGroupCardMessage(bot: QqBot, accessToken: String, groupOpenid: String, content: String, msgId: String?): Boolean {
        val payload = buildString {
            append("{\"msg_type\":2,\"markdown\":{\"content\":")
            append(org.json.JSONObject.quote(content.take(2000)))
            append("}")
            if (!msgId.isNullOrBlank()) {
                append(",\"msg_id\":")
                append(org.json.JSONObject.quote(msgId))
            }
            append("}")
        }
        val req = Request.Builder()
            .url("${base(bot)}/v2/groups/$groupOpenid/messages")
            .addHeader("Authorization", bot.authHeader(accessToken))
            .addHeader("Content-Type", "application/json")
            .post(payload.toRequestBody(jsonCt))
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                System.err.println("[QQBot] 群卡片消息发送失败 ${bot.appid} -> $groupOpenid : HTTP ${resp.code} $body")
            }
            return resp.isSuccessful
        }
    }

    /** 发送私聊卡片消息（markdown） */
    fun sendC2cCardMessage(bot: QqBot, accessToken: String, userOpenid: String, content: String, msgId: String?): Boolean {
        val payload = buildString {
            append("{\"msg_type\":2,\"markdown\":{\"content\":")
            append(org.json.JSONObject.quote(content.take(2000)))
            append("}")
            if (!msgId.isNullOrBlank()) {
                append(",\"msg_id\":")
                append(org.json.JSONObject.quote(msgId))
            }
            append("}")
        }
        val req = Request.Builder()
            .url("${base(bot)}/v2/users/$userOpenid/messages")
            .addHeader("Authorization", bot.authHeader(accessToken))
            .addHeader("Content-Type", "application/json")
            .post(payload.toRequestBody(jsonCt))
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                System.err.println("[QQBot] 私聊卡片消息发送失败 ${bot.appid} -> $userOpenid : HTTP ${resp.code} $body")
            }
            return resp.isSuccessful
        }
    }

    /** ★v1.207 发送群图片（富媒体 msg_type=1）；file_url 传图床地址（无则返回失败）
     * QQ 官方机器人 API：POST /v2/groups/{group_openid}/messages
     * {"msg_type":1,"msg_id":"...","media":{"file_info":"..."}}
     * 注：官方要求 file_info 是上传素材/图片消息后返回的 file_info；直接给 URL 需先走上传接口。
     */
    fun sendGroupImage(bot: QqBot, accessToken: String, groupOpenid: String, imageUrl: String, msgId: String?): Boolean {
        if (imageUrl.isBlank()) return false
        return try {
            // 先尝试按富媒体 msg_type=1 发送（file_info 用 URL 直传兜底，官方可能拒绝）
            val payload = buildString {
                append("{\"msg_type\":1,\"media\":{\"file_info\":")
                append(org.json.JSONObject.quote(imageUrl))
                append("}")
                if (!msgId.isNullOrBlank()) { append(",\"msg_id\":").append(org.json.JSONObject.quote(msgId)) }
                append("}")
            }
            val req = Request.Builder()
                .url("${base(bot)}/v2/groups/$groupOpenid/messages")
                .addHeader("Authorization", bot.authHeader(accessToken))
                .addHeader("Content-Type", "application/json")
                .post(payload.toRequestBody(jsonCt))
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    System.err.println("[QQBot] 群图片发送失败 ${bot.appid} -> $groupOpenid : HTTP ${resp.code} $body")
                }
                resp.isSuccessful
            }
        } catch (e: Exception) {
            System.err.println("[QQBot] 群图片发送异常 ${bot.appid} -> ${e.message}")
            false
        }
    }

    /** ★v1.207 群消息表态（表情包/比心）：POST /v2/groups/{group_openid}/messages/{message_id}/reaction
     * QQ 官方机器人 API（表情表态，类似发个表情包回应），emojiType 1-5
     */
    fun sendGroupReaction(bot: QqBot, accessToken: String, groupOpenid: String, messageId: String, emojiType: Int = 1): Boolean {
        if (messageId.isBlank()) return false
        val type = emojiType.coerceIn(1, 5)
        val payload = "{\"emojiType\":$type}"
        return try {
            val req = Request.Builder()
                .url("${base(bot)}/v2/groups/$groupOpenid/messages/$messageId/reaction")
                .addHeader("Authorization", bot.authHeader(accessToken))
                .addHeader("Content-Type", "application/json")
                .post(payload.toRequestBody(jsonCt))
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    System.err.println("[QQBot] 群表态失败 ${bot.appid} -> $groupOpenid : HTTP ${resp.code} $body")
                }
                resp.isSuccessful
            }
        } catch (e: Exception) {
            System.err.println("[QQBot] 群表态异常 ${bot.appid} -> ${e.message}")
            false
        }
    }

    /** 全员禁言/解除（机器人需为群管理员）。enable=true 开启全员禁言 */
    fun setGroupMute(bot: QqBot, accessToken: String, groupOpenid: String, enable: Boolean): Boolean {
        val mode = if (enable) "always" else "none"
        val payload = "{\"global_mute\":{\"mode\":\"$mode\"}}"
        val req = Request.Builder()
            .url("${base(bot)}/v2/groups/$groupOpenid/restrict_chat_setting")
            .addHeader("Authorization", bot.authHeader(accessToken))
            .addHeader("Content-Type", "application/json")
            .post(payload.toRequestBody(jsonCt))
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                System.err.println("[QQBot] 群禁言失败 ${bot.appid} -> $groupOpenid : HTTP ${resp.code} $body")
            }
            return resp.isSuccessful
        }
    }

    /** 获取群成员列表（机器人需为群主/管理员；返回 openid 数组，可能需分页） */
    fun getGroupMembers(bot: QqBot, accessToken: String, groupOpenid: String, limit: Int = 200): List<String> {
        return try {
            val req = Request.Builder()
                .url("${base(bot)}/v2/groups/$groupOpenid/members?limit=$limit")
                .addHeader("Authorization", bot.authHeader(accessToken))
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use emptyList<String>()
                val body = resp.body?.string().orEmpty()
                val arr = JSONObject(body).optJSONArray("members") ?: JSONObject(body).optJSONArray("data") ?: return@use emptyList<String>()
                (0 until arr.length()).map { arr.optJSONObject(it)?.optString("member_openid") ?: "" }
                    .filter { it.isNotBlank() }
            }
        } catch (e: Exception) { emptyList() }
    }

    /** 踢出群成员（机器人需为群主/管理员） */
    fun kickGroupMember(bot: QqBot, accessToken: String, groupOpenid: String, memberOpenid: String): Boolean {
        val req = Request.Builder()
            .url("${base(bot)}/v2/groups/$groupOpenid/members/$memberOpenid")
            .addHeader("Authorization", bot.authHeader(accessToken))
            .delete()
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                System.err.println("[QQBot] 踢人失败 ${bot.appid} -> $memberOpenid : HTTP ${resp.code} $body")
            }
            return resp.isSuccessful
        }
    }
}
