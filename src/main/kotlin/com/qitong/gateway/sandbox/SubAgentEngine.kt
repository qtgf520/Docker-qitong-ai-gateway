package com.qitong.gateway.sandbox

import com.qitong.gateway.db.Database
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * ★ v1.195 多子 Agent 调度引擎（对标业界"主 Agent 派生子 Agent"模式，自研实现）
 *
 * 核心能力：
 * - 主 Agent 通过沙盒函数 subagent_run 派发子任务（指定任务描述/模型/后台与否）
 * - 子 Agent 拥有独立沙盒执行环境：复用 SandboxEngine.execute（真实工具/真实接口）
 * - 后台子任务：立即返回任务ID，子 Agent 后台跑，完成后结果存库，可通过 subagent_result 查询
 * - 前台子任务：主 Agent 阻塞等待子结果（适合给子 Agent 拆分的小任务）
 * - 多 Agent 并发：线程池隔离，互不干扰；每个子任务独立上下文
 *
 * 设计：纯 Kotlin + 网关现有 SandboxEngine，无第三方框架。
 */
object SubAgentEngine {

    /** 子 Agent 执行线程池（默认最多 4 个并发子 Agent） */
    private val executor = Executors.newFixedThreadPool(4)

    /** 运行中子任务计数（防止无限制派发） */
    private val runningCount = AtomicInteger(0)

    /** 最大并发子任务数 */
    private const val MAX_CONCURRENT = 8

    /**
     * 派发一个子任务。
     * @param db 数据库
     * @param parentUserId 派发者（主 Agent）用户ID
     * @param channel 来源渠道
     * @param session 来源会话标识
     * @param taskDesc 子任务描述（给子 Agent 的核心指令）
     * @param model 子 Agent 模型（默认 qtai-sj）
     * @param waitMs 前台等待毫秒（>0 阻塞等结果；<=0 后台立即返回）
     * @return 子任务ID
     */
    fun dispatch(
        db: Database, parentUserId: Long, channel: String, session: String,
        taskDesc: String, model: String = "qtai-sj", waitMs: Long = 0
    ): Long {
        if (taskDesc.isBlank()) return 0
        if (runningCount.get() >= MAX_CONCURRENT) return -1  // 并发满了

        val taskId = db.addSubagentTask(parentUserId, channel, session, "subagent", taskDesc, model)
        runningCount.incrementAndGet()

        // 子 Agent 执行体：后台线程跑完整沙盒对话多次（自主调工具直到完成）
        val future = executor.submit {
            try {
                val result = runSubAgent(db, taskId, taskDesc, model, parentUserId, channel)
                db.finishSubagentTask(taskId, success = true, result = result)
            } catch (e: Exception) {
                db.finishSubagentTask(taskId, success = false, error = e.message ?: "未知错误")
            } finally {
                runningCount.decrementAndGet()
            }
        }

        // 前台模式：阻塞等结果（最多 waitMs）
        if (waitMs > 0) {
            try { future.get(waitMs, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: Exception) {}
            return taskId
        }
        return taskId
    }

    /**
     * ★ v1.204 承诺闭环：同步执行一个简洁子任务并等结果（供机器人「承诺后必达」兜底用）。
     * 与 dispatch 的区别：直接等结果、不落库、可指定会话 key，用于 Agent 承诺了要干活却没输出工具标签时强制真执行。
     * @return 子Agent最终结论文本（失败返回错误说明）
     */
    fun runSync(db: Database, parentUserId: Long, channel: String, session: String, taskDesc: String, model: String = "qtai-sj", timeoutMs: Long = 45_000L): String {
        if (taskDesc.isBlank()) return "（空任务）"
        if (runningCount.get() >= MAX_CONCURRENT) return "⛔ 子Agent并发已满，稍后再试"
        runningCount.incrementAndGet()
        return try {
            val taskId = db.addSubagentTask(parentUserId, channel, session, "subagent_sync", taskDesc, model)
            val future = executor.submit<String> {
                try {
                    val result = runSubAgent(db, taskId, taskDesc, model, parentUserId, channel)
                    db.finishSubagentTask(taskId, success = true, result = result)
                    result
                } catch (e: Exception) {
                    db.finishSubagentTask(taskId, success = false, error = e.message ?: "未知错误")
                    "❌ 子Agent执行失败：${e.message}"
                }
            }
            try {
                future.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (e: java.util.concurrent.TimeoutException) {
                "⏱️ 子Agent执行超时（${timeoutMs / 1000}s），请稍后重试"
            } catch (e: Exception) {
                "❌ 子Agent执行异常：${e.message}"
            }
        } finally {
            runningCount.decrementAndGet()
        }
    }

    /**
     * 子 Agent 主体：用独立上下文跑多轮沙盒（复用 SandboxEngine 的真实工具）。
     * 子 Agent 与主 Agent 的区别：上下文独立、任务聚焦、最多 8 轮。
     */
    private fun runSubAgent(db: Database, taskId: Long, taskDesc: String, model: String, userId: Long, channel: String): String {
        var content = taskDesc
        val executedSigs = mutableSetOf<String>()
        val sb = StringBuilder()
        var rounds = 0
        var lastStep = ""

        // 子 Agent 系统提示（聚焦任务、自主调工具、干完才停）
        val subPrompt = com.qitong.gateway.sandbox.SandboxEngine.SYSTEM_PROMPT +
            "\n\n【子Agent任务模式】你现在是一个专注执行子任务的智能体。任务：$taskDesc\n" +
            "规则：1. 自主拆解步骤并用沙盒工具真执行（不编造）；2. 需要数据就调工具获取；3. 全部干完输出最终结论（含关键结果数字/事实）；4. 只输出最终结论，不要复述过程。\n"

        // 复用子 Agent 独立历史
        val histKey = "subagent:$taskId:$userId"
        com.qitong.gateway.sandbox.SandboxEngine.ensureHist(histKey)

        while (rounds < 8) {
            rounds++
            // 调模型（带子 Agent 专属 system prompt）
            val reply = try {
                com.qitong.gateway.sandbox.SandboxEngine.askLite(db, subPrompt, content, model, histKey)
            } catch (e: Exception) {
                sb.append("❌ 子Agent模型调用失败：${e.message}\n")
                break
            }
            if (reply.isBlank()) { sb.append("⚠️ 子Agent无回复\n"); break }

            // 解析工具调用
            val calls = com.qitong.gateway.sandbox.SandboxEngine.parseCalls(reply, executedSigs)
            if (calls.isEmpty()) {
                // 没有工具调用 = 最终答案
                sb.append(com.qitong.gateway.sandbox.SandboxEngine.cleanCallTags(reply).trim())
                break
            }

            // 执行所有工具调用（真实执行）
            val results = StringBuilder()
            for ((fn, args) in calls) {
                val result = try {
                    com.qitong.gateway.sandbox.SandboxEngine.execute(fn, args, true, userId, db, channel)
                } catch (e: Exception) {
                    "❌ 工具执行异常：${e.message}"
                }
                executedSigs.add(com.qitong.gateway.sandbox.SandboxEngine.callSignature(fn, args))
                results.append("[$fn] ").append(result.take(300)).append("\n")
                lastStep = fn
            }
            // 回填给子 Agent 继续
            content = reply + "\n\n【工具执行结果】\n" + results
        }

        val final = sb.toString().trim()
        return if (final.isBlank()) "（子Agent执行 ${rounds} 轮无有效输出，最后步骤：$lastStep）" else final.take(2000)
    }
}