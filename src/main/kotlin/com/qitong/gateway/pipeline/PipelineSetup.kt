/*
 * ★ v1.178 管道装配 —— 把统一消息管道、平台适配器、事件总线三件套装配进网关主项目。
 *
 * 设计要点（自研表述，无外部来源痕迹）：
 * - 启动时一次性装配：注册三渠道适配器 → 构建管道（注入各渠道 Agent executor）→ 订阅事件
 * - 现有 askModel 逻辑不动：各渠道把现有 askModel 用 lambda 包装后注入管道
 * - 新增渠道只需：实现 PlatformAdapter + 注册到 PipelineSetup
 */
package com.qitong.gateway.pipeline

import com.qitong.gateway.db.Database
import com.qitong.gateway.platform.PlatformAdapter
import com.qitong.gateway.platform.PlatformRegistry

/**
 * 管道装配入口。Main.kt 启动时调用一次，之后三渠道统一走管道。
 */
object PipelineSetup {

    /** 是否已安装（防止重复初始化） */
    var installed = false
        private set

    /**
     * 装配管道。幂等：多次调用只生效一次。
     *
     * @param database 数据库实例
     * @param onChat 内置聊天渠道的 Agent executor（由调用方注入，复用 AdminApi.chat 的 Agent 循环）
     * @param onQq QQ 渠道的 Agent executor（由调用方注入，复用 QqBotManager.askModel）
     * @param onWx 微信渠道的 Agent executor（由调用方注入，复用 WeixinBotManager.askModel）
     */
    fun install(
        database: Database,
        onChat: (suspend (MsgContext, Database) -> AgentRunResult)? = null,
        onQq: (suspend (MsgContext, Database) -> AgentRunResult)? = null,
        onWx: (suspend (MsgContext, Database) -> AgentRunResult)? = null
    ) {
        if (installed) return
        installed = true

        // 1) 注册三渠道适配器（差异收敛在适配层，核心逻辑只写一次）
        registerDefaultAdapters()

        // 2) 构建管道：按渠道注入各自的 Agent executor（可空→兜底占位）
        val chatPipeline = buildPipeline("chat", onChat)
        val qqPipeline = buildPipeline("qq", onQq)
        val wxPipeline = buildPipeline("weixin", onWx)
        PipelineRegistry.pipeline = chatPipeline // 默认管道（内置聊天优先）

        // 3) 订阅事件：消息进入/回复/工具执行/记忆沉淀，各模块解耦
        subscribeEvents(database)

        println("[PipelineSetup] v1.209 管道装配完成: chat=${chatPipeline.stages.size}Stages, qq=${qqPipeline.stages.size}Stages, wx=${wxPipeline.stages.size}Stages")
    }

    /** 构建指定渠道的管道（注入该渠道的 Agent executor；可空→兜底占位） */
    private fun buildPipeline(channel: String, agentExecutor: (suspend (MsgContext, Database) -> AgentRunResult)?): MessagePipeline {
        val exec: suspend (MsgContext, Database) -> AgentRunResult = { ctx, db ->
            agentExecutor?.invoke(ctx, db) ?: AgentRunResult(reply = "⚠️ 当前渠道未接入智能体")
        }
        return MessagePipeline(MessagePipeline.defaultStages(exec))
    }

    /** 注册三渠道适配器（差异收敛在适配层） */
    private fun registerDefaultAdapters() {
        // 内置聊天适配器
        PlatformRegistry.register(object : PlatformAdapter {
            override val channelName = "chat"
            override fun adapt(raw: Any, db: Database): MsgContext? = null // 由调用方直接构造
            override fun sendReply(ctx: MsgContext, reply: String, toolCalls: List<String>, rounds: Int) {
                GatewayEventBus.publish(GatewayEventBus.Events.MESSAGE_REPLIED,
                    mapOf("ctx" to ctx, "reply" to reply, "toolCalls" to toolCalls, "rounds" to rounds))
            }
        })
        // QQ 适配器
        PlatformRegistry.register(object : PlatformAdapter {
            override val channelName = "qq"
            override fun adapt(raw: Any, db: Database): MsgContext? = null
            override fun sendReply(ctx: MsgContext, reply: String, toolCalls: List<String>, rounds: Int) {
                GatewayEventBus.publish(GatewayEventBus.Events.MESSAGE_REPLIED,
                    mapOf("ctx" to ctx, "reply" to reply, "toolCalls" to toolCalls, "rounds" to rounds))
            }
        })
        // 微信适配器
        PlatformRegistry.register(object : PlatformAdapter {
            override val channelName = "weixin"
            override fun adapt(raw: Any, db: Database): MsgContext? = null
            override fun sendReply(ctx: MsgContext, reply: String, toolCalls: List<String>, rounds: Int) {
                GatewayEventBus.publish(GatewayEventBus.Events.MESSAGE_REPLIED,
                    mapOf("ctx" to ctx, "reply" to reply, "toolCalls" to toolCalls, "rounds" to rounds))
            }
        })
    }

    /** 订阅事件：消息进入/回复/工具执行/记忆沉淀，各模块解耦 */
    private fun subscribeEvents(database: Database) {
        // 消息进入管道：供日志/统计/审计订阅
        GatewayEventBus.subscribe(GatewayEventBus.Events.MESSAGE_RECEIVED) { p ->
            runCatching {
                val ctx = p["ctx"] as? MsgContext
                if (ctx != null) {
                    println("[EventBus] message.received channel=${ctx.channel} user=${ctx.userId} text=${ctx.text.take(30)}")
                }
            }
        }

        // 消息回复完成：供推送/日志/统计订阅
        GatewayEventBus.subscribe(GatewayEventBus.Events.MESSAGE_REPLIED) { p ->
            runCatching {
                val reply = p["reply"] as? String ?: return@subscribe
                val rounds = p["rounds"] as? Int ?: 0
                println("[EventBus] message.replied rounds=$rounds replyLen=${reply.length}")
            }
        }

        // 工具执行成功：供审计/统计订阅 → 同时写入首页系统动态（实时反馈核心价值）
        GatewayEventBus.subscribe(GatewayEventBus.Events.TOOL_EXECUTED) { p ->
            runCatching {
                val fn = p["fn"] as? String ?: return@subscribe
                val channel = p["channel"] as? String ?: "?"
                val args = (p["args"] as? String)?.take(60) ?: ""
                val resultBrief = (p["result"] as? String)?.take(80) ?: ""
                val ok = !resultBrief.startsWith("❌") && !resultBrief.startsWith("⛔") && !resultBrief.startsWith("⚠️") && !resultBrief.startsWith("⏱️")
                println("[EventBus] tool.executed fn=$fn channel=$channel $args ${if (ok) "✅" else "❌"}")
                // ★ v1.182 重要工具执行写入系统动态（过滤高频只读查询，避免刷屏：只记 modify 类/失败类）
                val modifyFns = listOf("model_enable", "model_disable", "user_recharge", "user_deduct", "terminal_run", "terminal_create",
                    "linux_run", "linux_create", "code_run", "todo_add", "todo_done", "todo_del", "file_write", "skill_learn", "skill_add", "skill_del", "skill_toggle",
                    "provider_add", "provider_update", "provider_delete", "provider_enable", "provider_disable", "model_delete", "api_key_create", "api_key_delete",
                    "user_set_balance", "user_set_role", "config_set", "announcement_add", "mail_send", "image_gen", "card_gen", "backup_create")
                if (fn in modifyFns || !ok) {
                    try { database.addGatewayLog("tool", "🔧 [$channel] $fn $args ${if (ok) "✅" else resultBrief}") } catch (_: Exception) {}
                }
            }
        }

        // 记忆沉淀：供记忆分析/用户画像订阅 → 高价值记忆写入首页系统动态
        GatewayEventBus.subscribe(GatewayEventBus.Events.MEMORY_SAVED) { p ->
            runCatching {
                val userId = p["userId"] as? Long ?: return@subscribe
                val content = p["content"] as? String ?: return@subscribe
                println("[EventBus] memory.saved userId=$userId content=${content.take(30)}")
                // ★ v1.183 高价值长期记忆（importance>=7，如 v1.177 个人信息）写入系统动态，首页实时可见
                val imp = (p["importance"] as? Int) ?: 0
                val title = p["title"] as? String ?: ""
                if (imp >= 7) {
                    try { database.addGatewayLog("memory", "🧠 记住用户(userId=$userId)：${if (title.isNotBlank()) "$title · " else ""}${content.take(60)}") } catch (_: Exception) {}
                }
            }
        }
    }

    /** 重置管道（测试/配置热更新用） */
    fun reset() {
        PipelineRegistry.reset()
        GatewayEventBus.clear()
        installed = false
    }
}