package com.qitong.gateway.weixin

/**
 * 微信通道数据模型（对齐 qq/QqModels.kt 风格）
 */
data class WeixinBot(
    val id: Long = 0,
    val name: String = "",
    val botToken: String = "",          // ilink bot_token（加密存库）
    val ilinkBotId: String = "",        // ilink_bot_id
    val enabled: Boolean = true,
    val aiModel: String = "qtai-sj",
    val systemPrompt: String = "",
    val useSandbox: Boolean = false,
    val createdAt: Long = 0
)

data class WeixinMessage(
    val msgId: String,
    val from: String,                    // 发送者 openid
    val content: String,
    val contextToken: String = "",       // 回复时回传
    val isGroup: Boolean = false,
    val groupOpenid: String = ""
)

/** 在线状态（内存态，不入库，对齐 QqBotStatus） */
enum class WeixinBotStatus { OFFLINE, LOGIN_WAIT, CONNECTING, ONLINE, ERROR, PAUSED }

data class WeixinRuntimeState(
    var status: WeixinBotStatus = WeixinBotStatus.OFFLINE,
    var lastError: String = "",
    var readyAt: Long = 0L,
    var messagesHandled: Long = 0L,
    var getUpdatesBuf: String = ""      // 长轮询游标（断线续传）
)
