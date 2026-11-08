package com.qitong.gateway.mpx

import com.qitong.gateway.db.Database
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.security.MessageDigest

/**
 * ★ v1.193 微信公众号（webhook 回调模式）管理器
 *
 * 与 QQ（WebSocket）、微信 ilink（长轮询）完全不同：公众号是微信服务器 → 我们的服务器「推消息」模式。
 * - URL 验证：公众平台配置 URL 时 GET 过来带 signature/timestamp/nonce，用 sha1(token+ts+nonce) 比对
 * - 消息接收：微信 POST XML（明文/兼容/安全三模式），5 秒内返回被动回复 XML（关键词命中/默认语）
 * - 智能回答：处理耗时长，走「客服消息」异步推送（认证服务号可用；订阅号借用用户 48h 会话窗口主动下发）
 * - 关键词：每个公众号独立规则（exact/contains/regex → 固定回复 / AI / 忽略）
 * - 步骤显示：默认 quiet（不推过程），用户发「显示步骤」→ verbose 后 AI 干活过程实时推送（客服消息）
 *
 * 无外部依赖，只用 JDK 自带 crypto/XML 解析，避免引第三方库。
 */
object MpBotManager {

    // ============ URL 验证（微信服务器首次配置时的 GET 校验） ============
    /** 校验微信签名：sha1(sort(token, timestamp, nonce)) == signature */
    fun verifySignature(token: String, signature: String, timestamp: String, nonce: String): Boolean {
        if (signature.isBlank()) return false
        val sorted = listOf(token, timestamp, nonce).sorted().joinToString("")
        val digest = MessageDigest.getInstance("SHA-1").digest(sorted.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return hex == signature
    }

    /** URL 验证 GET：成功返回 echostr 原样；失败返回空 */
    fun handleVerify(token: String, signature: String, timestamp: String, nonce: String, echostr: String): String =
        if (verifySignature(token, signature, timestamp, nonce)) echostr else ""

    // ============ 消息处理（POST 进入） ============
    /**
     * 处理一条公众号消息（微信服务器 POST 的 XML）。
     * @return 被动回复 XML（5 秒内交给微信）；空串表示「不回复」（等客服消息异步推）
     */
    fun handleMessage(db: Database, bot: Map<String, Any?>, xmlText: String, isEncrypted: Boolean, msgSignature: String? = null, timestamp: String = "", nonce: String = ""): String {
        val botId = (bot["id"] as? Number)?.toLong() ?: 0L
        val appid = (bot["appid"] as? String) ?: ""
        try {
            val xml = parseXml(xmlText)
            val msgType = xml["MsgType"] ?: "text"
            val fromUser = xml["FromUserName"] ?: ""
            val toUser = xml["ToUserName"] ?: ""
            val content = (xml["Content"] ?: "").trim()
            val event = xml["Event"] ?: ""

            // 事件消息（关注/取关/菜单等）
            if (msgType == "event") {
                when (event) {
                    "subscribe" -> {
                        val welcome = (bot["welcome"] as? String)?.takeIf { it.isNotBlank() } ?: "感谢关注！🎉 回复关键词或直接发消息即可体验 AI 智能回答。"
                        return makePassiveReply(toUser, fromUser, "text", welcome)
                    }
                    "unsubscribe" -> { db.addMpLogSafe(botId, fromUser, "event", "取消关注") }
                }
                return ""
            }

            if (msgType != "text") {
                // 非文本（图片/语音等）：提示用文字
                return makePassiveReply(toUser, fromUser, "text", "👋 我目前只支持文字消息，请用文字向我提问～")
            }

            if (content.isBlank()) return ""

            db.addMpLogSafe(botId, fromUser, "receive", "$content".take(200))

            // ★ 步骤显示开关：默认 quiet，用户发「显示步骤」→ verbose（用客服消息确认，不占被动回复）
            val boundUserId = db.getMpFanBound(botId, fromUser)
            if (content.contains("显示步骤", true) || content.contains("显示过程", true) || content.contains("看步骤", true)) {
                db.setUserConfig(boundUserId, "mp_progress_mode", "verbose")
                db.addMpLogSafe(botId, fromUser, "progress", "开启显示步骤")
                // 异步客服消息确认（不阻塞被动回复）
                kotlinx.coroutines.GlobalScope.launch { MpPush.sendText(db, bot, fromUser, "👁️ 已开启「显示步骤」：之后我干活会把每一步过程推给你 💭→🔧→✅。想隐藏就发「隐藏步骤」。") }
                return ""
            }
            if (content.contains("隐藏步骤", true) || content.contains("隐藏过程", true) || content.contains("别发步骤", true)) {
                db.setUserConfig(boundUserId, "mp_progress_mode", "quiet")
                db.addMpLogSafe(botId, fromUser, "progress", "开启隐藏步骤")
                kotlinx.coroutines.GlobalScope.launch { MpPush.sendText(db, bot, fromUser, "🫥 已开启「隐藏步骤」：之后只回最终结果，过程不再推送。想恢复就发「显示步骤」。") }
                return ""
            }

            // ★ 关键词回答（每公众号独立规则，命中即被动回复，秒回）
            val keywordEnabled = (bot["keywordEnabled"] as? Boolean) ?: true
            if (keywordEnabled) {
                val kw = matchKeyword(db, botId, content)
                if (kw != null) {
                    db.addMpLogSafe(botId, fromUser, "keyword", "[${kw["trigger"]}] → ${(kw["content"] as? String).orEmpty().take(100)}")
                    when (kw["action"] as? String ?: "reply") {
                        "reply" -> return makePassiveReply(toUser, fromUser, "text", (kw["content"] as? String).orEmpty())
                        "ai" -> { /* 关键词也走 AI：继续下方 AI 链路 */ }
                        "none" -> return ""
                        else -> return makePassiveReply(toUser, fromUser, "text", (kw["content"] as? String).orEmpty())
                    }
                }
            }

            // ★ 默认语 / AI 智能回答
            val aiEnabled = (bot["aiEnabled"] as? Boolean) ?: true
            if (!aiEnabled) {
                val dr = (bot["defaultReply"] as? String)?.takeIf { it.isNotBlank() }
                    ?: "🤖 收到！关键词未命中，且该公众号已关闭智能回答。"
                return makePassiveReply(toUser, fromUser, "text", dr)
            }

            // AI 智能回答：先回「处理中」占位提示（防 5s 超时），异步跑 Agent → 客服消息推结果
            val aiModel = (bot["aiModel"] as? String) ?: "qtai-sj"
            val systemPrompt = (bot["systemPrompt"] as? String) ?: ""
            val bindId = ((bot["bindAccountId"] as? Number)?.toLong() ?: 0L)
            // 处理中的占位被动回复（既是防超时，也是用户体验提示）
            kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                try {
                    val reply = MpBrain.ask(db, bot, fromUser, content, aiModel, systemPrompt, boundUserId, bindId)
                    MpPush.sendText(db, bot, fromUser, reply)
                    db.addMpLogSafe(botId, fromUser, "ai_reply", reply.take(100))
                } catch (e: Exception) {
                    kotlinx.coroutines.GlobalScope.launch { MpPush.sendText(db, bot, fromUser, "⚠️ AI 处理失败：${e.message}") }
                }
            }
            val placeholder = (bot["defaultReply"] as? String)?.takeIf { it.isNotBlank() } ?: "🤖 收到，正在思考中…请稍候"
            return makePassiveReply(toUser, fromUser, "text", placeholder)
        } catch (e: Exception) {
            System.err.println("[MpBot] 处理消息异常: ${e.message}")
            return ""
        }
    }

    // ============ 关键词匹配 ============
    /** 按规则匹配关键词：返回命中规则；未命中返回 null */
    fun matchKeyword(db: Database, botId: Long, content: String): Map<String, Any?>? {
        val rules = try { db.getMpKeywords(botId).filter { (it["enabled"] as? Boolean) ?: true } } catch (_: Exception) { emptyList() }
        for (r in rules) {
            val t = (r["trigger"] as? String ?: "").trim()
            if (t.isBlank()) continue
            val mtype = (r["matchType"] as? String) ?: "contains"
            val hit = when (mtype) {
                "exact" -> content == t
                "regex" -> runCatching { Regex(t).containsMatchIn(content) }.getOrDefault(false)
                else -> content.contains(t, true)   // contains 不区分大小写
            }
            if (hit) return r
        }
        return null
    }

    // ============ 被动回复 XML 构建 ============
    /** 构造被动回复文本消息 XML（5 秒内返回给微信） */
    fun makePassiveReply(toUser: String, fromUser: String, msgType: String, content: String): String {
        val xml = StringBuilder()
        xml.append("<xml>")
        xml.append("<ToUserName><![CDATA[").append(toUser).append("]]></ToUserName>")
        xml.append("<FromUserName><![CDATA[").append(fromUser).append("]]></FromUserName>")
        xml.append("<CreateTime>").append(System.currentTimeMillis() / 1000).append("</CreateTime>")
        xml.append("<MsgType><![CDATA[").append(msgType).append("]]></MsgType>")
        xml.append("<Content><![CDATA[").append(content).append("]]></Content>")
        xml.append("</xml>")
        return xml.toString()
    }

    // ============ 极简 XML 解析（提取最内层标签文本，支持 CDATA） ============
    /** 解析 XML 为 Map：标签 → 文本（自动去 CDATA）。只取单层，嵌套结构用递归兜底。 */
    fun parseXml(xmlText: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        // 先剥离最外层 <xml> 包裹（否则反向引用 \1 会整体吞掉内层标签，导致 MsgType/FromUserName 等全部解析不出）
        val inner = xmlText.substringAfter("<xml>", xmlText).substringBeforeLast("</xml>", xmlText)
        // 匹配 <TagName>CDATA或文本</TagName>（非贪婪，标签名不包含空格；注意排除已闭合的 /> 自闭合标签）
        val pattern = Regex("<([A-Za-z_][A-Za-z0-9_]*)>([\\s\\S]*?)</\\1>")
        for (m in pattern.findAll(inner)) {
            val tag = m.groupValues[1]
            var value = m.groupValues[2].trim()
            // 去 CDATA
            val cdata = Regex("""<!\[CDATA\[([\s\S]*?)\]\]>""").find(value)
            if (cdata != null) value = cdata.groupValues[1]
            result[tag] = value
        }
        return result
    }

    /** 生成公众号 webhook 地址（管理页展示） */
    fun webhookUrl(botId: Long, externalHost: String = ""): String =
        if (externalHost.isNotBlank()) "$externalHost/mp/webhook/$botId" else "/mp/webhook/$botId"

    /** AES 加解密（安全/兼容模式时用，EncodingAESKey；本期先提供骨架，明文模式已可用） */
    fun decryptMsg(encodingAesKey: String, encryptMsg: String): String {
        // 对接标准：aesKey = Base64(EncodingAESKey + "=")；CBC PKCS7；iv = 前16字节；消息取后 20 位随机串 + 4 位网络序长度 + msg + appid
        // 为控制本期复杂度：非明文模式先回退「无法解密」，提示用明文模式（刷新管理页可见提示）
        return ""
    }
}

/** 数据库扩展：安全包裹 addMpLog（避免异常影响主流程） */
fun Database.addMpLogSafe(botId: Long, openid: String, type: String, content: String, latencyMs: Long = 0) {
    runCatching { this.addMpLog(botId, openid, type, content, latencyMs) }
}