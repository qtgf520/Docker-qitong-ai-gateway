/*
 * ★ v1.178 统一消息管道 —— 把学习到的「责任链式消息处理」架构思想落进网关主项目。
 *
 * 设计要点（自研表述，无外部来源痕迹）：
 * - 消息处理 = 一串可插拔 Stage：白名单 → 限流 → Agent → 回复
 * - 每段可独立开关、可独立替换；新增能力只需加一个 Stage 并注册
 * - 三渠道（内置聊天/QQ/微信）统一走管道，核心逻辑只写一次
 * - 与现有 askModel 并存：现有逻辑不动，管道作为「统一编排层」供三渠道调用
 * - Agent Stage 不自己实现 Agent 循环：由调用方注入 lambda，复用各渠道现有 askModel 逻辑
 */
package com.qitong.gateway.pipeline

import com.qitong.gateway.db.Database
import com.qitong.gateway.sandbox.SandboxEngine
import com.qitong.gateway.rate.RateLimiter
import com.qitong.gateway.http.AgentRunTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory

private val log: Logger = LoggerFactory.getLogger("MessagePipeline")

// ============ 统一消息模型 ============
data class MsgContext(
    val channel: String,          // chat | qq | weixin
    val userId: Long,             // 网关用户 ID（0=未绑定）
    val userOpenid: String,       // 渠道侧用户标识
    val groupOpenid: String = "", // 群 ID（私聊为空）
    val text: String,             // 消息原文
    val isAdmin: Boolean = false, // 是否管理员/代理
    val convKey: String = ""      // 会话隔离 key（含 botId 前缀）
)

data class PipelineResult(
    val reply: String,
    val toolCalls: List<String> = emptyList(),
    val rounds: Int = 0,
    val blocked: Boolean = false,   // 是否被白名单/限流拦截
    val blockReason: String = ""
)

// ============ Stage 接口（可插拔） ============
interface MessageStage {
    /** Stage 名称（用于日志/开关） */
    val name: String

    /**
     * 处理消息。返回 true=继续管道，false=停止（后续 Stage 不再执行）。
     * ctx 可被 Stage 修改（如限流 Stage 可以设置 blocked）。
     */
    suspend fun process(ctx: MsgContext, db: Database, state: PipelineState): StageResult
}

enum class StageAction { CONTINUE, STOP, SKIP }
data class StageResult(val action: StageAction, val reply: String? = null, val reason: String = "")

/** 管道内共享状态（跨 Stage 传递中间结果） */
class PipelineState {
    var reply: String? = null
    var toolCalls: MutableList<String> = mutableListOf()
    var rounds: Int = 0
    var blocked: Boolean = false
    var blockReason: String = ""
    val extra = mutableMapOf<String, Any?>()
}

// ============ 内置 Stage 实现 ============

/** 白名单 Stage：未绑定用户仅放行管理指令 */
class WhitelistStage : MessageStage {
    override val name = "whitelist"
    override suspend fun process(ctx: MsgContext, db: Database, state: PipelineState): StageResult {
        // 管理员/已绑定用户直接放行
        if (ctx.isAdmin || ctx.userId > 0) return StageResult(StageAction.CONTINUE)
        // 未绑定：仅放行绑定相关指令
        val t = ctx.text.trim()
        val pass = t.startsWith("绑定账号") || t.startsWith("绑定码 ") ||
            t == "绑定码" || t == "我的账号" || t == "退出账号" || t == "解绑"
        if (!pass) {
            state.blocked = true
            state.blockReason = "未绑定账号"
            state.reply = "🔓 当前未绑定网关账号\n发「绑定账号」或网页生成绑定码后发「绑定码 123456」"
            return StageResult(StageAction.STOP, state.reply, "未绑定")
        }
        return StageResult(StageAction.CONTINUE)
    }
}

/** 限流 Stage：走现有 RateLimiter，按用户/API Key 隔离 */
class RateLimitStage : MessageStage {
    override val name = "ratelimit"
    override suspend fun process(ctx: MsgContext, db: Database, state: PipelineState): StageResult {
        if (ctx.isAdmin || ctx.userId <= 0) return StageResult(StageAction.CONTINUE)
        val bucketKey = "pipeline:${ctx.channel}:${ctx.userOpenid}"
        val limited = RateLimiter.check(db, bucketKey, ctx.userId, ctx.isAdmin)
        if (limited != null) {
            state.blocked = true
            state.blockReason = "限流"
            state.reply = "⚠️ $limited"
            return StageResult(StageAction.STOP, state.reply, "限流")
        }
        return StageResult(StageAction.CONTINUE)
    }
}

/** Agent 执行结果（AgentStage 与调用方之间的契约） */
data class AgentRunResult(
    val reply: String,
    val toolCalls: List<String> = emptyList(),
    val rounds: Int = 0
)

/**
 * Agent Stage：不自己实现 Agent 循环，由调用方注入现有 askModel 逻辑。
 * 三渠道各自的 askModel（内置聊天/QQ/微信）用 lambda 包装后注入，复用已验证的 Agent 循环。
 */
class AgentStage(
    private val executor: suspend (MsgContext, Database) -> AgentRunResult
) : MessageStage {
    override val name = "agent"
    override suspend fun process(ctx: MsgContext, db: Database, state: PipelineState): StageResult {
        return withContext(Dispatchers.IO) {
            val result = executor(ctx, db)
            state.reply = result.reply
            state.toolCalls.addAll(result.toolCalls)
            state.rounds = result.rounds
            StageResult(StageAction.CONTINUE)
        }
    }
}

/** 回复 Stage：把 state.reply 作为最终输出 */
class RespondStage : MessageStage {
    override val name = "respond"
    override suspend fun process(ctx: MsgContext, db: Database, state: PipelineState): StageResult {
        val r = state.reply ?: "⚠️ 抱歉，我这边处理失败了，请再试一次"
        return StageResult(StageAction.CONTINUE, r)
    }
}

// ============ 管道编排器 ============
class MessagePipeline(
    stages: List<MessageStage> = defaultStages()
) {
    private val _stages = stages.toMutableList()
    val stages: List<MessageStage> get() = _stages

    fun addStage(stage: MessageStage) { _stages.add(stage) }
    fun removeStage(name: String) { _stages.removeAll { it.name == name } }
    fun getStage(name: String): MessageStage? = _stages.find { it.name == name }

    /**
     * 执行完整管道。返回 PipelineResult。
     * 三渠道（内置聊天/QQ/微信）统一调用此方法，核心逻辑只写一次。
     */
    suspend fun run(ctx: MsgContext, db: Database): PipelineResult = withContext(Dispatchers.IO) {
        val state = PipelineState()
        for (stage in _stages) {
            if (state.blocked) break
            try {
                val r = stage.process(ctx, db, state)
                if (r.reply != null) state.reply = r.reply
                if (r.action == StageAction.STOP) break
            } catch (e: Exception) {
                log.error("[管道] Stage {} 异常: {}", stage.name, e.message)
                state.reply = "⚠️ 处理异常: ${e.message}"
                break
            }
        }
        PipelineResult(
            reply = state.reply ?: "",
            toolCalls = state.toolCalls,
            rounds = state.rounds,
            blocked = state.blocked,
            blockReason = state.blockReason
        )
    }

    companion object {
        /** 默认管道：agentExecutor 由各渠道注册时提供；未提供时用占位实现（提示未接入） */
        fun defaultStages(
            agentExecutor: suspend (MsgContext, Database) -> AgentRunResult = { _, _ -> AgentRunResult(reply = "⚠️ 当前渠道未接入智能体") }
        ): List<MessageStage> = listOf(
            WhitelistStage(),
            RateLimitStage(),
            AgentStage(agentExecutor),
            RespondStage()
        )
    }
}

// ============ 默认管道实例（单例，三渠道共用） ============
object PipelineRegistry {
    private var _pipeline: MessagePipeline = MessagePipeline()
    var pipeline: MessagePipeline
        get() = _pipeline
        set(value) { _pipeline = value }

    fun configure(configure: MessagePipeline.() -> MessagePipeline) {
        _pipeline = _pipeline.configure()
    }

    /** 重置为默认管道 */
    fun reset() { _pipeline = MessagePipeline() }
}