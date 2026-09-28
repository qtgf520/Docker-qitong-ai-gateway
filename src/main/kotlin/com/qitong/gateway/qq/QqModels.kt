package com.qitong.gateway.qq

/**
 * QQ 开放平台机器人 —— 数据模型
 * 事件字段对齐 https://bot.q.qq.com/wiki/develop/api-v2/
 */

/** 一个已配置的机器人（来自 qq_bots 表） */
data class QqBot(
    val id: Long,
    val appid: String,
    val token: String,
    val name: String,
    val enabled: Boolean,
    val aiModel: String,
    val systemPrompt: String,
    val welcome: String
) {
    /** 鉴权头：Bot {appid}.{token} */
    fun authHeader(): String = "Bot $appid.$token"
}

/** 收到的一条群@消息 GROUP_AT_MESSAGE_CREATE */
data class QqGroupMessage(
    val msgId: String,
    val groupOpenid: String,
    val content: String,
    val memberOpenid: String
)

/** 收到的一条私聊消息 C2C_MESSAGE_CREATE */
data class QqC2cMessage(
    val msgId: String,
    val userOpenid: String,
    val content: String
)

/** 在线状态（内存态，不入库） */
enum class QqBotStatus { OFFLINE, CONNECTING, ONLINE, ERROR }

data class QqRuntimeState(
    var status: QqBotStatus = QqBotStatus.OFFLINE,
    var lastError: String = "",
    var readyAt: Long = 0L,
    var groupsSeen: Int = 0,
    var messagesHandled: Long = 0L
)
