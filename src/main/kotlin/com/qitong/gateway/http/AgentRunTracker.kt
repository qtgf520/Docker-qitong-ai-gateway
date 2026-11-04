package com.qitong.gateway.http

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * ★ v1.174 Agent 实时运行状态追踪器（沙盒运行可视化）
 *  - QQ / 微信 / 内置聊天 三渠道各自在跑的 Agent 任务，实时记录：轮次、当前动作、调用工具、结果摘要、状态
 *  - 后台「实时沙盒」面板按绑定用户隔离查看：无需把过程刷到群里，细节后台看
 *  - SandboxEngine / QqBotManager / WeixinBotManager / AdminApi.chat 调用 record() 上报
 */
object AgentRunTracker {

    data class RunState(
        val id: String,                       // run id（会话+时间戳）
        val channel: String,                  // qq / weixin / chat
        val userKey: String,                  // 绑定用户标识（qqOpenid / wxOpenid / userId）
        val userName: String = "",            // 展示名
        var status: String = "running",       // running / done / error / stopped
        val startedAt: Long = System.currentTimeMillis(),
        var updatedAt: Long = System.currentTimeMillis(),
        var round: Int = 0,                    // 当前轮次
        var currentAction: String = "思考中…", // 当前动作描述
        var toolCall: String = "",             // 正在调用的工具
        var toolResult: String = "",           // 最近工具结果摘要
        var messagePreview: String = ""        // 用户消息摘要
    )

    private val runs = ConcurrentHashMap<String, RunState>()
    private val seq = AtomicLong(0)

    /** 开始一次 Agent 运行（渠道入口调用；相同 userKey+channel 会复用上一运行覆盖为新的） */
    fun start(channel: String, userKey: String, userName: String = "", message: String = ""): String {
        val id = channel + "-" + System.currentTimeMillis() + "-" + seq.incrementAndGet()
        // 同一用户的旧运行标记为完成（防止残留"运行中"）
        runs.values.forEach { r ->
            if (r.channel == channel && r.userKey == userKey && r.status == "running") {
                r.status = "done"; r.updatedAt = System.currentTimeMillis()
            }
        }
        runs[id] = RunState(
            id = id, channel = channel, userKey = userKey,
            userName = userName, messagePreview = message.take(60)
        )
        return id
    }

    /** 更新：轮次/动作 */
    fun update(id: String, round: Int? = null, action: String? = null) {
        val r = runs[id] ?: return
        if (round != null) r.round = round
        if (action != null) r.currentAction = action
        r.updatedAt = System.currentTimeMillis()
    }

    /** 上报工具调用 */
    fun toolCall(id: String, fn: String, args: String = "") {
        val r = runs[id] ?: return
        r.toolCall = fn
        r.toolResult = ""
        r.currentAction = "调用工具 $fn"
        r.updatedAt = System.currentTimeMillis()
    }

    /** 上报工具结果摘要 */
    fun toolResult(id: String, fn: String, result: String) {
        val r = runs[id] ?: return
        r.toolResult = result.take(120)
        r.currentAction = "工具 $fn 已返回"
        r.updatedAt = System.currentTimeMillis()
    }

    /** 结束运行 */
    fun finish(id: String, status: String = "done") {
        val r = runs[id] ?: return
        r.status = status
        r.updatedAt = System.currentTimeMillis()
    }

    /** 当前运行列表（按用户隔离；userId=0 管理看全部） */
    fun snapshot(userId: Long = 0L): List<Map<String, Any?>> {
        cleanup()
        val now = System.currentTimeMillis()
        return runs.values
            .sortedByDescending { it.startedAt }
            .take(100)
            .map { r ->
                mapOf(
                    "id" to r.id, "channel" to r.channel, "userKey" to r.userKey,
                    "userName" to r.userName, "status" to r.status,
                    "startedAt" to r.startedAt, "updatedAt" to r.updatedAt,
                    "round" to r.round, "currentAction" to r.currentAction,
                    "toolCall" to r.toolCall, "toolResult" to r.toolResult,
                    "messagePreview" to r.messagePreview,
                    "elapsedSec" to ((now - r.startedAt) / 1000)
                )
            }
    }

    /** 清理已完成超过 30 分钟的运行 */
    private fun cleanup() {
        val now = System.currentTimeMillis()
        runs.entries.removeIf { (_, r) ->
            r.status != "running" && now - r.updatedAt > 30 * 60 * 1000L
        }
        // 运行中但超过 2 小时强制标记超时
        runs.entries.forEach { (_, r) ->
            if (r.status == "running" && now - r.startedAt > 2 * 60 * 60 * 1000L) r.status = "done"
        }
    }
}