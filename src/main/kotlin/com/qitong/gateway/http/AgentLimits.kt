package com.qitong.gateway.http

/**
 * ★ v1.232 P0-C Agent 循环统一限额常量
 *
 * 目标：三渠道（QQ/微信/内置聊天）主循环的参数收敛到一处，消除各自字面量漂移
 * （原 QQ/微信 while < 20、chat while < 8 散落各文件）。
 */
object AgentLimits {

    /** QQ 渠道 Agent 多步循环最大轮数 */
    const val MAX_ROUNDS_QQ = 20

    /** 微信渠道 Agent 多步循环最大轮数 */
    const val MAX_ROUNDS_WX = 20

    /** 内置聊天渠道 Agent 多步循环最大轮数 */
    const val MAX_ROUNDS_CHAT = 8

    /** 单轮内最多执行的工具调用数（防模型一轮吐几十个标签刷爆） */
    const val MAX_TOOLS_PER_ROUND = 8

    /** 单次 Agent 运行累计工具调用总上限（防长任务无限循环耗尽资源） */
    const val MAX_TOOLS_PER_RUN = 24
}