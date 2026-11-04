/*
 * ★ v1.178 平台适配器 —— 把学习到的「平台适配器模式」架构思想落进网关主项目。
 *
 * 设计要点（自研表述，无外部来源痕迹）：
 * - 三渠道（内置聊天/QQ/微信）差异收敛到适配层，核心逻辑只写一次
 * - 新增渠道（如 Telegram/飞书）只需实现一个适配器
 * - 适配器负责：原始消息 → MsgContext（统一消息模型）；回复 → 渠道侧发送
 */
package com.qitong.gateway.platform

import com.qitong.gateway.pipeline.MsgContext
import com.qitong.gateway.db.Database

/**
 * 平台适配器接口。三渠道各自实现，差异收敛在适配层。
 */
interface PlatformAdapter {
    /** 渠道名（chat / qq / weixin） */
    val channelName: String

    /**
     * 把平台原始消息转成统一 MsgContext。
     * @return null 表示该消息不走管道（如纯心跳/系统消息）
     */
    fun adapt(raw: Any, db: Database): MsgContext?

    /**
     * 把管道结果发回渠道。
     */
    fun sendReply(ctx: MsgContext, reply: String, toolCalls: List<String>, rounds: Int)
}

/** 适配器注册表 */
object PlatformRegistry {
    private val _adapters = mutableMapOf<String, PlatformAdapter>()

    fun register(adapter: PlatformAdapter) {
        _adapters[adapter.channelName] = adapter
    }

    fun get(channel: String): PlatformAdapter? = _adapters[channel]

    fun all(): Map<String, PlatformAdapter> = _adapters.toMap()

    fun names(): Collection<String> = _adapters.keys
}