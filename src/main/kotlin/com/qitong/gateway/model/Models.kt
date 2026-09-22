package com.qitong.gateway.model

import kotlinx.serialization.Serializable

/** 服务商实体（对齐原APP providers 表） */
@Serializable
data class Provider(
    val id: Long = 0,
    val name: String,
    val type: String,            // "OpenAI Compatible" / "Ollama" / "Custom"
    val baseUrl: String,
    val port: String = "",
    val apiKey: String? = null,
    val isEnabled: Boolean = true,
    val orderIndex: Int = 0,
    val chatPath: String? = null,          // e.g. /v1/chat/completions
    val supportsSystemRole: Boolean = false,
    val customId: String = ""             // pID 自定义ID
) {
    /** 合并端口后的完整 Base URL */
    val resolvedBaseUrl: String
        get() {
            if (port.isBlank()) return baseUrl
            val portPattern = Regex("://[^:]+:\\d+")
            if (portPattern.containsMatchIn(baseUrl)) return baseUrl
            return baseUrl.trimEnd('/') + ":" + port.trim()
        }
}

/** 模型实体（对齐原APP models 表） */
@Serializable
data class AiModel(
    val id: Long = 0,
    val providerId: Long,
    val modelId: String,
    val displayName: String,
    val isDefault: Boolean = false,
    val syncStatus: String = "Pending",   // Synced / Pending / Failed
    val isEnabled: Boolean = true,
    val customAlias: String = "",
    val useProxy: Boolean = true,
    val contextWindow: Int = 4096
)

/** 会话（对齐 conversations 表） */
@Serializable
data class Conversation(
    val id: Long = 0,
    val title: String = "新对话",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val userId: Long? = null
)

/** 聊天消息（对齐 chat_messages 表） */
@Serializable
data class ChatMessage(
    val id: Long = 0,
    val conversationId: Long,
    val role: String,            // user / assistant / system
    val content: String,
    val modelId: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

/** 用量统计（对齐 token_usage 表） */
@Serializable
data class TokenUsage(
    val id: Long = 0,
    val modelKey: String,
    val modelName: String = "",
    val providerId: Long = 0,
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val totalTokens: Long = 0,
    val uploadBytes: Long = 0,
    val downloadBytes: Long = 0,
    val apiKeyLabel: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

/** 测速历史（对齐 speed_history 表） */
@Serializable
data class SpeedHistory(
    val id: Long = 0,
    val modelKey: String,
    val modelName: String,
    val providerId: Long,
    val ttftMs: Int,
    val tps: Double,
    val totalMs: Int,
    val success: Boolean,
    val measuredAt: Long = System.currentTimeMillis()
)

/** 路由规则（对齐 routing_rule 表） */
@Serializable
data class RoutingRule(
    val id: Long = 0,
    val name: String,
    val enabled: Boolean = true,
    val priority: Int = 0,
    val pathPattern: String = "",
    val modelPattern: String = "",
    val apiKeyPattern: String = "",
    val providerId: Long? = null,
    val targetModelKey: String = "",
    val action: String = "route",        // route / block
    val blockMessage: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

/** 后台用户表（新增） */
@Serializable
data class User(
    val id: Long = 0,
    val username: String,
    val passwordHash: String,           // BCrypt
    val role: String = "admin",         // admin / user
    val displayName: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val lastLoginAt: Long = 0,
    val quotaLimit: Long = 0,           // 额度上限（token数，0=不限）
    val quotaUsed: Long = 0,            // 已用额度（token数）
    val bindModels: List<String> = emptyList()   // 绑定模型ID列表（空=全部）
)

/** 人格配置（对齐原APP人设系统） */
@Serializable
data class Persona(
    val id: Long = 0,
    val userId: Long = 0,
    val name: String = "",
    val age: String = "",
    val personality: String = "",
    val tone: String = "",
    val background: String = "",
    val openness: Double = 0.5,
    val conscientiousness: Double = 0.5,
    val extraversion: Double = 0.5,
    val agreeableness: Double = 0.5,
    val neuroticism: Double = 0.5,
    val memoryEnabled: Boolean = true,
    val updatedAt: Long = System.currentTimeMillis()
)

/** API 密钥条目（对齐原APP KeyManager） */
@Serializable
data class ApiKeyEntry(
    val key: String,
    val label: String = "",
    val enabled: Boolean = true,
    val allowedModels: List<String> = emptyList(),
    val qtaiSjAccess: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

/** 网关配置项 */
@Serializable
data class GatewayConfig(
    val key: String,
    val value: String
)

/** 网关状态（健康检查） */
@Serializable
data class GatewayStatus(
    val status: String = "ok",
    val service: String = "qitong-ai-gateway-docker",
    val version: String = "3.18.22",
    val running: Boolean = true,
    val port: Int,
    val failover: Boolean,
    val modelsCount: Int,
    val uptimeSeconds: Long,
    val requireApiKey: Boolean
)