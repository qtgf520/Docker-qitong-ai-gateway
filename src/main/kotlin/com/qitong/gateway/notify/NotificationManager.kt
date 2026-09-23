package com.qitong.gateway.notify

import com.qitong.gateway.db.Database
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Properties
import jakarta.mail.Authenticator
import jakarta.mail.Message
import jakarta.mail.PasswordAuthentication
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage

/**
 * 通知管理器 —— 支持 钉钉Webhook + 邮箱SMTP
 * 对齐 dingtalk-notification.php 的钉钉实现
 * 配置存于 gateway_config（管理员可设置）
 */
object NotificationManager {

    // ============ 配置读写 ============

    /** 读取管理员通知配置 */
    fun getConfig(database: Database): Map<String, String> = mapOf(
        "dingtalk_webhook" to database.getConfig("notify_dingtalk_webhook", ""),
        "email_smtp_host" to database.getConfig("notify_email_smtp_host", ""),
        "email_smtp_port" to database.getConfig("notify_email_smtp_port", "465"),
        "email_username" to database.getConfig("notify_email_username", ""),
        "email_password" to database.getConfig("notify_email_password", ""),
        "email_to" to database.getConfig("notify_email_to", ""),
        "email_tls" to database.getConfig("notify_email_tls", "true"),
        "enable_dingtalk" to database.getConfig("notify_enable_dingtalk", "false"),
        "enable_email" to database.getConfig("notify_enable_email", "false")
    )

    /** 保存管理员通知配置 */
    fun saveConfig(database: Database, cfg: Map<String, String>) {
        cfg["dingtalk_webhook"]?.let { database.setConfig("notify_dingtalk_webhook", it) }
        cfg["email_smtp_host"]?.let { database.setConfig("notify_email_smtp_host", it) }
        cfg["email_smtp_port"]?.let { database.setConfig("notify_email_smtp_port", it) }
        cfg["email_username"]?.let { database.setConfig("notify_email_username", it) }
        cfg["email_password"]?.let { database.setConfig("notify_email_password", it) }
        cfg["email_to"]?.let { database.setConfig("notify_email_to", it) }
        cfg["email_tls"]?.let { database.setConfig("notify_email_tls", it) }
        cfg["enable_dingtalk"]?.let { database.setConfig("notify_enable_dingtalk", it) }
        cfg["enable_email"]?.let { database.setConfig("notify_enable_email", it) }
    }

    // ============ 发送通知 ============

    /** 发送通知（钉钉+邮箱都按配置发） */
    suspend fun notify(database: Database, title: String, message: String) {
        withContext(Dispatchers.IO) {
            val cfg = getConfig(database)
            if (cfg["enable_dingtalk"] == "true" && cfg["dingtalk_webhook"]?.isNotBlank() == true) {
                try { sendDingtalk(cfg["dingtalk_webhook"]!!, "$title\n$message") } catch (_: Exception) {}
            }
            if (cfg["enable_email"] == "true" && cfg["email_smtp_host"]?.isNotBlank() == true && cfg["email_to"]?.isNotBlank() == true) {
                try { sendEmail(cfg, title, message) } catch (_: Exception) {}
            }
        }
    }

    /** 发送钉钉机器人Webhook通知（对齐 dingtalk-notification.php） */
    private fun sendDingtalk(webhook: String, text: String) {
        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        val json = """
            {
              "msgtype": "text",
              "text": { "content": ${kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.json.JsonPrimitive(text))} }
            }
        """.trimIndent()
        val body = json.toByteArray(Charsets.UTF_8).toRequestBody("application/json; charset=utf-8".toMediaType())
        val req = okhttp3.Request.Builder().url(webhook).post(body).build()
        client.newCall(req).execute().use { resp -> resp.body?.string() }
    }

    /** 发送邮件（SMTP，支持 SSL/TLS） */
    private fun sendEmail(cfg: Map<String, String>, title: String, message: String) {
        val host = cfg["email_smtp_host"] ?: ""
        val port = cfg["email_smtp_port"]?.toIntOrNull() ?: 465
        val username = cfg["email_username"] ?: ""
        val password = cfg["email_password"] ?: ""
        val to = cfg["email_to"] ?: ""
        val tls = cfg["email_tls"] != "false"
        if (host.isBlank() || username.isBlank() || to.isBlank()) return

        val props = Properties().apply {
            put("mail.smtp.host", host)
            put("mail.smtp.port", port.toString())
            put("mail.smtp.auth", "true")
            put("mail.smtp.ssl.enable", if (tls) "true" else "false")
            put("mail.smtp.ssl.trust", host)
            put("mail.smtp.timeout", "10000")
            put("mail.smtp.connectiontimeout", "10000")
        }
        val session = Session.getInstance(props, object : Authenticator() {
            override fun getPasswordAuthentication() = PasswordAuthentication(username, password)
        })
        val msg = MimeMessage(session).apply {
            setFrom(InternetAddress(username))
            setRecipients(Message.RecipientType.TO, InternetAddress.parse(to))
            subject = title
            setText(message, "UTF-8", "html")
        }
        Transport.send(msg)
    }
}