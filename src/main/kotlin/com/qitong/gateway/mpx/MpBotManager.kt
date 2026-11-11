package com.qitong.gateway.mpx

import com.qitong.gateway.db.Database
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

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

    /** ★ v1.193 超时兜底：AI 没在 5s 窗口内答完时缓存结果，用户下一条消息被动回复带回（未认证号也连续对话） */
    private val pendingReplies = java.util.concurrent.ConcurrentHashMap<String, String>()

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
            // ★ v1.193 安全/兼容模式：收到的消息体是 <Encrypt>base64</Encrypt>，先解密成明文 XML 再解析
            var effectiveXml = xmlText
            if (isEncrypted) {
                val encXml = parseXml(xmlText)
                val encryptMsg = encXml["Encrypt"] ?: ""
                val encodingAesKey = (bot["encodingAesKey"] as? String) ?: ""
                // 解密（自动校验 msg_signature）
                effectiveXml = decryptMsg(encodingAesKey, encryptMsg, appid, msgSignature ?: "", timestamp, nonce, (bot["token"] as? String) ?: "")
                if (effectiveXml.isBlank()) {
                    // 安全模式解密失败：静默返回空（微信会重试几次；日志记录原因）
                    db.addMpLogSafe(botId, "", "error", "安全模式解密失败（EncodingAESKey/签名校验失败）")
                    return ""
                }
            }
            val xml = parseXml(effectiveXml)
            val msgType = xml["MsgType"] ?: "text"
            val fromUser = xml["FromUserName"] ?: ""
            val toUser = xml["ToUserName"] ?: ""
            val content = (xml["Content"] ?: "").trim()
            val event = xml["Event"] ?: ""

            // ★ v1.198 消息记录中心：公众号入站留存（文本消息；事件也留一条）
            runCatching {
                if (msgType == "text" && content.isNotBlank()) db.addPlatformMessage("mp", botId, fromUser, "", "in", content, (bot["aiModel"] as? String) ?: "")
                else if (msgType == "event") db.addPlatformMessage("mp", botId, fromUser, "", "in", "【事件】$event", "")
            }

            // ★ 统一被动回复出口：安全/兼容模式自动加密返回，明文模式原样
            val encryptMode = (bot["encryptMode"] as? String) ?: "plain"
            val encKey = (bot["encodingAesKey"] as? String) ?: ""
            val mpToken = (bot["token"] as? String) ?: ""
            fun replyText(replyContent: String): String {
                // ★ v1.198 消息记录中心：公众号出站留存（被动回复）
                runCatching { db.addPlatformMessage("mp", botId, fromUser, "", "out", replyContent, (bot["aiModel"] as? String) ?: "") }
                val plain = makePassiveReply(toUser, fromUser, "text", replyContent)
                return if (isEncrypted && encKey.isNotBlank() && encryptMode != "plain")
                    encryptReplyMsg(encKey, plain, appid, timestamp, nonce, mpToken)
                else plain
            }

            // ★ v1.198 平台设置：AI 总开关（关=普通文本直接回默认语，仅拦截 AI 智能回答；关键词回答仍可用）
            runCatching {
                val ps = db.getPlatformSettings("mp")
                val aiEnabled = (ps["aiEnabled"] as? Boolean) ?: true
                if (!aiEnabled && msgType == "text" && content.isNotBlank()) {
                    if (!content.contains("显示步骤", true) && !content.contains("隐藏步骤", true)) {
                        val dr = (bot["defaultReply"] as? String)?.takeIf { it.isNotBlank() } ?: "🤖 收到！该公众号已关闭智能回答（后台平台设置）。"
                        return replyText(dr)
                    }
                }
            }

            // 事件消息（关注/取关/菜单等）
            if (msgType == "event") {
                when (event) {
                    "subscribe" -> {
                        val welcome = (bot["welcome"] as? String)?.takeIf { it.isNotBlank() } ?: "感谢关注！🎉 回复关键词或直接发消息即可体验 AI 智能回答。"
                        return replyText(welcome)
                    }
                    "unsubscribe" -> { db.addMpLogSafe(botId, fromUser, "event", "取消关注") }
                }
                return ""
            }

            if (msgType != "text") {
                // 非文本（图片/语音等）：提示用文字
                return replyText("👋 我目前只支持文字消息，请用文字向我提问～")
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
                        "reply" -> return replyText((kw["content"] as? String).orEmpty())
                        "ai" -> { /* 关键词也走 AI：继续下方 AI 链路 */ }
                        "none" -> return ""
                        else -> return replyText((kw["content"] as? String).orEmpty())
                    }
                }
            }

            // ★ 默认语 / AI 智能回答
            val aiEnabled = (bot["aiEnabled"] as? Boolean) ?: true
            if (!aiEnabled) {
                val dr = (bot["defaultReply"] as? String)?.takeIf { it.isNotBlank() }
                    ?: "🤖 收到！关键词未命中，且该公众号已关闭智能回答。"
                return replyText(dr)
            }

            // AI 智能回答：★ v1.193 三步策略——
            //   ① 先进来先检查上一条超时的缓存结果，有则直接被动回复带回（未认证号连续对话）
            //   ② 同步等最多 4.5s（微信 5s 窗口内压线）拿到本次 AI 结果则直接被动回复
            //   ③ 仍超时：占位 + 异步继续等 AI → 结果缓存/客服消息（认证号直接推，未认证号下条带回）
            val pendingKey = "$botId:$fromUser"
            val cachedReply = pendingReplies.remove(pendingKey)
            if (!cachedReply.isNullOrBlank()) {
                db.addMpLogSafe(botId, fromUser, "ai_reply", "（上一条补发）$cachedReply".take(100))
                return replyText(cachedReply)
            }
            val aiModel = (bot["aiModel"] as? String) ?: "qtai-sj"
            val systemPrompt = (bot["systemPrompt"] as? String) ?: ""
            val bindId = ((bot["bindAccountId"] as? Number)?.toLong() ?: 0L)
            val aiFuture = java.util.concurrent.CompletableFuture.supplyAsync {
                try {
                    MpBrain.ask(db, bot, fromUser, content, aiModel, systemPrompt, boundUserId, bindId)
                } catch (e: Exception) {
                    "⚠️ AI 处理失败：${e.message}"
                }
            }
            val aiReply: String? = try {
                aiFuture.get(4, java.util.concurrent.TimeUnit.SECONDS)
            } catch (e: Exception) {
                null  // 4s 超时：回占位，后台继续跑 AI
            }
            if (aiReply != null && aiReply.isNotBlank() && !aiReply.startsWith("⚠️ AI")) {
                db.addMpLogSafe(botId, fromUser, "ai_reply", aiReply.take(100))
                return replyText(aiReply)
            }
            // 超时：异步继续等 AI → 结果缓存（下条被动回复带回）+ 客服消息双推（认证号立即到，未认证号被 48001 拦但缓存兜底）
            kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                try {
                    val reply = aiFuture.get(60, java.util.concurrent.TimeUnit.SECONDS)
                    if (reply.isNotBlank() && !reply.startsWith("⚠️ AI")) {
                        pendingReplies[pendingKey] = reply.take(600)
                        MpPush.sendText(db, bot, fromUser, reply)
                        db.addMpLogSafe(botId, fromUser, "ai_reply", reply.take(100))
                    }
                } catch (e: Exception) {
                    kotlinx.coroutines.GlobalScope.launch { MpPush.sendText(db, bot, fromUser, "⚠️ AI 处理失败：${e.message}") }
                }
            }
            val placeholder = (bot["defaultReply"] as? String)?.takeIf { it.isNotBlank() } ?: "🤖 收到，正在思考中…请稍候，稍后再发条消息即可获取结果"
            return replyText(placeholder)
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

    // ============ 微信官方 AES 加解密（安全/兼容模式） ============
    /**
     * 解密微信加密消息（安全/兼容模式）。
     * 微信格式：AES-256-CBC(PKCS7)，key = Base64Decode(EncodingAESKey + "=")，iv = key 前 16 字节
     * 明文结构：<16字节随机串><4字节网络序消息长度><消息体XML><AppID>
     * @return 解密后的明文 XML 文本；失败返回空串
     */
    fun decryptMsg(encodingAesKey: String, encryptMsg: String, appid: String, msgSignature: String, timestamp: String, nonce: String, token: String): String {
        try {
            if (encodingAesKey.isBlank() || encryptMsg.isBlank()) return ""
            // 安全模式需校验 msg_signature = sha1(sort(token, timestamp, nonce, encryptMsg))
            if (msgSignature.isNotBlank()) {
                val expect = sha1(listOf(token, timestamp, nonce, encryptMsg).sorted().joinToString(""))
                if (!expect.equals(msgSignature, ignoreCase = true)) {
                    System.err.println("[MpBot] 安全模式 msg_signature 校验失败")
                    return ""
                }
            }
            val aesKey = Base64.getDecoder().decode(encodingAesKey + "=")
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val iv = aesKey.copyOfRange(0, 16)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(iv))
            val decrypted = cipher.doFinal(Base64.getDecoder().decode(encryptMsg))

            // 解包：前16字节随机串 + 4字节网络序长度 + 消息体 + appid
            val msgLenBytes = decrypted.copyOfRange(16, 20)
            val msgLen = ((msgLenBytes[0].toInt() and 0xFF) shl 24) or
                ((msgLenBytes[1].toInt() and 0xFF) shl 16) or
                ((msgLenBytes[2].toInt() and 0xFF) shl 8) or
                (msgLenBytes[3].toInt() and 0xFF)
            if (msgLen <= 0 || 20 + msgLen > decrypted.size) {
                System.err.println("[MpBot] 解密消息体长度异常: $msgLen / ${decrypted.size}")
                return ""
            }
            val msg = String(decrypted, 20, msgLen, Charsets.UTF_8)
            // 校验尾部 appid 是否一致（防止加密串串号）
            val tail = String(decrypted, 20 + msgLen, decrypted.size - 20 - msgLen, Charsets.UTF_8)
            if (appid.isNotBlank() && tail != appid) {
                System.err.println("[MpBot] 解密尾部 appid 不一致: 期望[$appid] 实际[$tail]")
                return ""
            }
            return msg
        } catch (e: Exception) {
            System.err.println("[MpBot] 解密异常: ${e.message}")
            return ""
        }
    }

    /** 加密被动回复 XML（安全/兼容模式返回给微信用） */
    fun encryptReplyMsg(encodingAesKey: String, replyXml: String, appid: String, timestamp: String, nonce: String, token: String): String {
        try {
            if (encodingAesKey.isBlank()) return ""
            val aesKey = Base64.getDecoder().decode(encodingAesKey + "=")

            // 16 字节随机串 + 4 字节网络序长度 + 消息体 + appid
            val rand = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val msgBytes = replyXml.toByteArray(Charsets.UTF_8)
            val lenBytes = byteArrayOf(
                ((msgBytes.size shr 24) and 0xFF).toByte(),
                ((msgBytes.size shr 16) and 0xFF).toByte(),
                ((msgBytes.size shr 8) and 0xFF).toByte(),
                (msgBytes.size and 0xFF).toByte()
            )
            val appidBytes = appid.toByteArray(Charsets.UTF_8)
            val plain = ByteArray(rand.size + lenBytes.size + msgBytes.size + appidBytes.size)
            System.arraycopy(rand, 0, plain, 0, rand.size)
            System.arraycopy(lenBytes, 0, plain, 16, 4)
            System.arraycopy(msgBytes, 0, plain, 20, msgBytes.size)
            System.arraycopy(appidBytes, 0, plain, 20 + msgBytes.size, appidBytes.size)

            // PKCS7 补位（PKCS5Padding 等价，块16）
            val padded = ByteArray(plain.size + 16 - plain.size % 16)
            System.arraycopy(plain, 0, padded, 0, plain.size)
            val pad = (16 - plain.size % 16).toByte()
            for (i in plain.size until padded.size) padded[i] = pad

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val iv = aesKey.copyOfRange(0, 16)
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(iv))
            val encrypted = cipher.doFinal(padded)
            val encryptStr = Base64.getEncoder().encodeToString(encrypted)

            // 组装 <xml><Encrypt>...</Encrypt><MsgSignature>sha1(sort(token,ts,nonce,encrypt))</MsgSignature><TimeStamp>...</TimeStamp><Nonce>...</Nonce></xml>
            val sig = sha1(listOf(token, timestamp, nonce, encryptStr).sorted().joinToString(""))
            return "<xml><Encrypt><![CDATA[$encryptStr]]></Encrypt><MsgSignature><![CDATA[$sig]]></MsgSignature><TimeStamp>$timestamp</TimeStamp><Nonce><![CDATA[$nonce]]></Nonce></xml>"
        } catch (e: Exception) {
            System.err.println("[MpBot] 加密回复异常: ${e.message}")
            return ""
        }
    }

    private fun sha1(input: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}

/** 数据库扩展：安全包裹 addMpLog（避免异常影响主流程） */
fun Database.addMpLogSafe(botId: Long, openid: String, type: String, content: String, latencyMs: Long = 0) {
    runCatching { this.addMpLog(botId, openid, type, content, latencyMs) }
}