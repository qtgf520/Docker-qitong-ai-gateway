package com.qitong.gateway.qq

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * QQ 开放平台 REST API 客户端（发消息）
 * Base: https://api.sgroup.qq.com
 */
class QqApiClient {

    private val base = "https://api.sgroup.qq.com"
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val jsonCt = "application/json; charset=utf-8".toMediaType()

    /**
     * 回复群消息（被动：带 msg_id，5 分钟内有效，不占主动额度）。
     * @param msgId 事件里的 id，被动回复必带；主动消息留空则走主动额度。
     */
    fun sendGroupMessage(bot: QqBot, groupOpenid: String, content: String, msgId: String?): Boolean {
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
            .url("$base/v2/groups/$groupOpenid/messages")
            .addHeader("Authorization", bot.authHeader())
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

    /** 回复私聊消息。 */
    fun sendC2cMessage(bot: QqBot, userOpenid: String, content: String, msgId: String?): Boolean {
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
            .url("$base/v2/users/$userOpenid/messages")
            .addHeader("Authorization", bot.authHeader())
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

    /** 全员禁言/解除（机器人需为群管理员）。enable=true 开启全员禁言。 */
    fun setGroupMute(bot: QqBot, groupOpenid: String, enable: Boolean): Boolean {
        val mode = if (enable) "always" else "none"
        val payload = "{\"global_mute\":{\"mode\":\"$mode\"}}"
        val req = Request.Builder()
            .url("$base/v2/groups/$groupOpenid/restrict_chat_setting")
            .addHeader("Authorization", bot.authHeader())
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
}
