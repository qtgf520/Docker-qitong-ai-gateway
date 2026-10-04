package com.qitong.gateway.sandbox

import com.qitong.gateway.db.Database
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 自主心跳引擎（v47，对齐 Kai 9000 Heartbeat）
 * ============================
 * 每 30 分钟（8:00-22:00）后台自检一次：
 *  1. 检查网关运行状态（GatewayProxy.running / 活跃模型）
 *  2. 检查最近是否有模型故障（失败日志）
 *  3. 检查系统磁盘/内存压力（粗略）
 *  4. 检查是否有待办（记忆 importance 高未处理）
 * 发现问题通过回调推送（QQ 群/管理员），没问题保持沉默。
 */
object HeartbeatEngine {

    @Volatile private var started = false
    private var job: kotlinx.coroutines.Job? = null
    private val running = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 心跳间隔：默认 60 分钟（可从系统配置 heartbeat_interval_minutes 覆盖） */
    @Volatile var intervalMs: Long = 60 * 60 * 1000L

    /** 自检回调：发现问题时推送（传入检查结果文本） */
    @Volatile var onIssue: ((String) -> Unit)? = null

    /** 心跳提示词（可从配置 heartbeat_prompt 覆盖，写入自检输出） */
    @Volatile var prompt: String = ""

    /** 活跃时段（24h 制，默认 5:00-23:00，可从配置 heartbeat_active_start/heartbeat_active_end 覆盖） */
    @Volatile var activeStartHour: Int = 5
    @Volatile var activeEndHour: Int = 23

    /** 是否处于活跃时段（供循环判断） */
    private fun inActiveWindow(): Boolean {
        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return if (activeStartHour <= activeEndHour) h >= activeStartHour && h < activeEndHour
        else h >= activeStartHour || h < activeEndHour  // 跨午夜
    }

    /** 启动心跳（幂等）：从系统配置读 heartbeat_enabled / heartbeat_interval_minutes / 活跃时段 / 提示词 */
    fun start(db: Database) {
        if (started) return
        val enabled = db.getConfig("heartbeat_enabled", "true") != "false"
        if (!enabled) {
            println("[Heartbeat] 心跳未启用（heartbeat_enabled=false），跳过")
            return
        }
        val minutes = db.getConfig("heartbeat_interval_minutes", "60").toIntOrNull() ?: 60
        intervalMs = minutes.coerceIn(5, 24 * 60) * 60 * 1000L
        prompt = db.getConfig("heartbeat_prompt", "")
        activeStartHour = db.getConfig("heartbeat_active_start", "5").toIntOrNull() ?: 5
        activeEndHour = db.getConfig("heartbeat_active_end", "23").toIntOrNull() ?: 23
        started = true
        job = scope.launch {
            // 启动后立即自检一次
            runCatching { checkOnce(db) }
            while (isActive) {
                delay(intervalMs)
                // ★ v76 活跃时段内才自检（无用户输入时检查记忆+任务）
                if (inActiveWindow()) runCatching { checkOnce(db) }
                else println("[Heartbeat] 非活跃时段，跳过本轮自检")
            }
        }
        println("[Heartbeat] 自主心跳已启动，间隔 $minutes 分钟（活跃时段 ${activeStartHour}:00-${activeEndHour}:00）")
    }

    /** 停止心跳（可从沙盒/设置页调用） */
    fun stop() {
        started = false
        job?.cancel()
        job = null
        println("[Heartbeat] 自主心跳已停止")
    }

    /** 是否运行中 */
    fun isRunning(): Boolean = started

    /** 当前配置描述 */
    fun statusText(): String = if (started) "✅ 心跳运行中（间隔 ${intervalMs / 60000} 分钟）" else "⛔ 心跳已停止"

    /** 立即执行一次自检（暴露给测试/手动触发） */
    fun checkOnce(db: Database): String {
        if (!running.compareAndSet(false, true)) return "⏳ 心跳检查正在进行中"
        return try {
            val issues = mutableListOf<String>()
            val okLines = mutableListOf<String>()

            // 1. 网关运行状态
            val gwRunning = com.qitong.gateway.http.GatewayProxy.running
            if (!gwRunning) issues.add("⚠️ 网关已停止运行！")
            else okLines.add("✅ 网关运行中")

            // 2. 活跃模型
            val active = db.getConfig("active_model_key", "")
            if (active.isNotBlank()) okLines.add("✅ 活跃模型: ${active.substringAfter("::", active)}")
            else okLines.add("ℹ️ 当前 qtai-sj 自动化切换模式")

            // 3. 最近故障（30 分钟内失败日志 > 阈值）
            val recentFail = com.qitong.gateway.http.GatewayProxy.totalUploadBytes // 占位
            val failCount = db.getConfig("heartbeat_last_fail_count", "0").toIntOrNull() ?: 0
            if (failCount > 3) issues.add("⚠️ 最近模型故障较多（$failCount 次），建议检查服务商配置")

            // 4. 磁盘压力（容器内 /tmp 使用量粗查）
            runCatching {
                val proc = ProcessBuilder("sh", "-c", "df -P / | tail -1 | awk '{print $5}'").redirectErrorStream(true).start()
                val pct = proc.inputStream.bufferedReader().readText().trim()
                if (pct.isNotBlank() && pct.replace("%", "").toIntOrNull()?.let { it > 85 } == true) {
                    issues.add("⚠️ 根分区使用率 $pct，建议清理")
                }
            }

            // 5. 高重要性未处理记忆（importance >= 9 视为待办） + 到期计划任务
            runCatching {
                val todos = db.getMemories(0, limit = 50).filter { (it["importance"] as? Number)?.toInt() ?: 0 >= 9 }
                if (todos.isNotEmpty()) okLines.add("📌 有 ${todos.size} 条高优先级记忆待回顾")
            }
            // ★ v76 到期计划任务检查（无用户输入时也能跑，受 scheduled_tasks_enabled 开关控制）
            runCatching {
                if (db.getConfig("scheduled_tasks_enabled", "true") != "false") {
                    val now = System.currentTimeMillis()
                    val dueTasks = db.getDueScheduledTasks(now)
                    if (dueTasks.isNotEmpty()) {
                        okLines.add("⏰ 有 ${dueTasks.size} 条计划任务到期：")
                        dueTasks.forEach { task ->
                            okLines.add("  · [${task["channel"]}] ${task["content"]}")
                            val id = (task["id"] as? Number)?.toLong() ?: 0L
                            val cronExpr = (task["cronExpr"] as? String) ?: ""
                            if (cronExpr.isNotBlank()) {
                                // ★ v84 cron 周期任务：执行后推进到下一匹配时刻（继续 pending）
                                db.advanceScheduledTask(id, cronExpr, now)
                            } else {
                                // 一次性任务：标记完成（推送回调里处理实际送达）
                                db.markScheduledTaskDone(id)
                            }
                        }
                        // 到期的计划任务内容放进 result（有实质内容就推送）
                        dueTasks.forEach { task ->
                            issues.add("⏰ 计划任务到期：${task["content"]}")
                        }
                    }
                }
            }
            // ★ v76 心跳提示词注入（用户自定义）
            if (prompt.isNotBlank()) okLines.add("💬 心跳提示词：$prompt")

            val result = if (issues.isNotEmpty()) {
                "🔔 【自主心跳自检 ${java.text.SimpleDateFormat("MM-dd HH:mm").format(java.util.Date())}】\n" +
                    issues.joinToString("\n") + (if (okLines.isNotEmpty()) "\n" + okLines.joinToString("\n") else "")
            } else null

            if (result != null) onIssue?.invoke(result)
            result ?: "✅ 心跳自检正常（${java.text.SimpleDateFormat("MM-dd HH:mm").format(java.util.Date())}）"
        } finally {
            running.set(false)
        }
    }
}