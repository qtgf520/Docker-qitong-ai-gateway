package com.qitong.gateway.http

import com.qitong.gateway.db.Database
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

    // ★ v1.232 P0-B 持久化：attach 后状态异步落盘，重启不丢（落盘失败只打日志不影响主流程）
    @Volatile private var db: Database? = null
    private val persistPool = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "agent-runs-persist").apply { isDaemon = true }
    }

    /** 绑定数据库实例（启动时调用；此后每次状态变更异步 upsert） */
    fun attach(database: Database) {
        db = database
        println("[AgentRunTracker] 已挂载持久化（agent_runs 表）")
    }

    /** 解绑（测试/关闭用） */
    fun detach() {
        db = null
        println("[AgentRunTracker] 已解除持久化")
    }

    /** 异步 upsert 当前 run 状态到库（单线程池串行，失败只打日志） */
    private fun persist(r: RunState) {
        val d = db ?: return
        persistPool.submit {
            try {
                d.upsertRun(
                    r.id, r.channel, r.userKey, r.userName, r.status, r.round,
                    r.currentAction, r.toolCall, r.toolResult, r.startedAt, r.updatedAt
                )
            } catch (e: Exception) {
                println("[AgentRunTracker] 持久化失败(忽略): ${e.message}")
            }
        }
    }

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
        persist(runs[id]!!) // ★ v1.232 P0-B 立即落盘（新建运行）
        return id
    }

    /** 更新：轮次/动作 */
    fun update(id: String, round: Int? = null, action: String? = null) {
        val r = runs[id] ?: return
        if (round != null) r.round = round
        if (action != null) r.currentAction = action
        r.updatedAt = System.currentTimeMillis()
        persist(r) // ★ v1.232 P0-B
    }

    /** 上报工具调用 */
    fun toolCall(id: String, fn: String, args: String = "") {
        val r = runs[id] ?: return
        r.toolCall = fn
        r.toolResult = ""
        r.currentAction = "调用工具 $fn"
        r.updatedAt = System.currentTimeMillis()
        persist(r) // ★ v1.232 P0-B
    }

    /** 上报工具结果摘要 */
    fun toolResult(id: String, fn: String, result: String) {
        val r = runs[id] ?: return
        r.toolResult = result.take(120)
        r.currentAction = "工具 $fn 已返回"
        r.updatedAt = System.currentTimeMillis()
        persist(r) // ★ v1.232 P0-B
    }

    // ★ v1.190 模型回复到达上报：后台不再一直"等待模型回复"——模型一回复实时刷新
    /** 上报模型回复摘要（回复到达即调用，面板从"等模型回复"变为"模型已回复：…"） */
    fun modelReply(id: String, replyText: String) {
        val r = runs[id] ?: return
        r.toolResult = replyText.take(120)   // 复用结果列显示模型回复内容
        r.currentAction = "模型已回复：${replyText.take(50)}"
        r.updatedAt = System.currentTimeMillis()
        persist(r) // ★ v1.232 P0-B
    }

    /** 收尾（带最终动作文案，面板显示"完成/停止/错误"而不是一直 running） */
    fun finish(id: String, status: String = "done", action: String = "") {
        val r = runs[id] ?: return
        r.status = status
        if (action.isNotBlank()) r.currentAction = action
        r.updatedAt = System.currentTimeMillis()
        persist(r) // ★ v1.232 P0-B
    }

    /** 当前运行列表（按用户隔离；userId=0 管理看全部；★v1.232 P0-B includeInterrupted=true 时合并库里 interrupted 历史） */
    fun snapshot(userId: Long = 0L, includeInterrupted: Boolean = false): List<Map<String, Any?>> {
        cleanup()
        val now = System.currentTimeMillis()
        val memoryList = runs.values
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
        // ★ v1.232 P0-B：重启后内存空，从库里读 interrupted 历史（上次崩溃残留）补充展示
        if (includeInterrupted) {
            val db = this.db
            if (db != null) {
                runCatching {
                    val hist = db.listRunsByStatus("interrupted", limit = 50)
                    if (hist.isNotEmpty()) {
                        val memIds = memoryList.mapNotNull { it["id"] as? String }.toSet()
                        val extra = hist.filter { it["id"] as? String !in memIds }.map {
                            mapOf(
                                "id" to (it["id"] ?: ""), "channel" to (it["channel"] ?: ""),
                                "userKey" to (it["userKey"] ?: ""), "userName" to (it["userName"] ?: ""),
                                "status" to (it["status"] ?: "interrupted"),
                                "startedAt" to (it["startedAt"] ?: 0L), "updatedAt" to (it["updatedAt"] ?: 0L),
                                "round" to (it["round"] ?: 0), "currentAction" to (it["currentAction"] ?: "（上次中断）"),
                                "toolCall" to (it["toolCall"] ?: ""), "toolResult" to (it["toolResult"] ?: ""),
                                "messagePreview" to "", "elapsedSec" to -1L
                            )
                        }
                        return (memoryList + extra).sortedByDescending { it["startedAt"] as? Long ?: 0L }
                    }
                }
            }
        }
        return memoryList
    }

    /** 清理已完成超过 30 分钟的运行 */
    private fun cleanup() {
        val now = System.currentTimeMillis()
        runs.entries.removeIf { (_, r) ->
            r.status != "running" && now - r.updatedAt > 30 * 60 * 1000L
        }
        // ★ v1.175 卡顿可见：运行中但 90 秒没任何状态更新（模型/工具超时卡住）→ 标记超时结束，面板能看到"卡在哪"
        runs.entries.forEach { (_, r) ->
            if (r.status == "running" && now - r.updatedAt > 90 * 1000L) {
                r.status = "done"
                r.currentAction = r.currentAction + "（⏱ 超时结束：90秒无进展）"
            }
        }
        // 运行中但超过 2 小时强制标记超时
        runs.entries.forEach { (_, r) ->
            if (r.status == "running" && now - r.startedAt > 2 * 60 * 60 * 1000L) r.status = "done"
        }
    }
}