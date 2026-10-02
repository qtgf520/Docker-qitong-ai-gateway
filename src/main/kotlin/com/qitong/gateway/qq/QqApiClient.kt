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
    private class TokenCache(val token: String, val expireAt: Long)
    private val tokenCache = ConcurrentHashMap<String, TokenCache>()

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
                    // 换 token 失败 → 若有旧 token 缓存，降级用旧 token（可能仍有效，避免掉线）
                    return cached?.token
                }
                val token = JSONObject(body).optString("access_token").ifBlank { null }
                if (token != null) {
                    tokenCache[key] = TokenCache(token, System.currentTimeMillis() + 7200_000L - 300_000L)
                    return token
                }
                cached?.token
            }
        } catch (e: Exception) {
            System.err.println("[QQBot] 获取AccessToken异常 ${bot.appid}: ${e.message}")
            cached?.token  // 网络异常也降级用旧 token，避免掉线
        }
    }

    private fun base(bot: QqBot): String = if (bot.useSandbox) sandboxBase else prodBase

    /** 发送群消息（被动回复带 msg_id，5 分钟内有效；主动消息留空走主动额度） */
    fun sendGroupMessage(bot: QqBot, accessToken: String, groupOpenid: String, content: String, msgId: String?): Boolean {
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
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                System.err.println("[QQBot] 群消息发送失败 ${bot.appid} -> $groupOpenid : HTTP ${resp.code} $body")
            }
            return resp.isSuccessful
        }
    }

    /** 发送私聊消息 */
    fun sendC2cMessage(bot: QqBot, accessToken: String, userOpenid: String, content: String, msgId: String?): Boolean {
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
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                System.err.println("[QQBot] 私聊消息发送失败 ${bot.appid} -> $userOpenid : HTTP ${resp.code} $body")
            }
            return resp.isSuccessful
        }
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
