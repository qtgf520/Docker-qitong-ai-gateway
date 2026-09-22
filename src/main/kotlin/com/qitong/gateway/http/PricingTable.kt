package com.qitong.gateway.http

/**
 * 模型默认价格表 —— 元/百万Token（prompt 输入价，输出价为 2x）
 * 参考主流模型公开定价，未知模型按默认低价
 */
object PricingTable {

    /** 默认价格（元/百万token）：输入价 */
    const val DEFAULT_PRICE = 2.0
    const val OUTPUT_MULTIPLIER = 2.0

    private val table = mapOf(
        // OpenAI
        "gpt-4o" to 15.0,
        "gpt-4o-mini" to 1.2,
        "gpt-4-turbo" to 75.0,
        "gpt-4" to 225.0,
        "gpt-3.5-turbo" to 4.0,
        "o1" to 105.0,
        "o1-mini" to 14.0,
        "o3" to 84.0,
        // Claude
        "claude-3-5-sonnet" to 23.4,
        "claude-3-5-haiku" to 6.5,
        "claude-3-opus" to 112.5,
        "claude-sonnet-4-20250514" to 23.4,
        // Gemini
        "gemini-2.5-pro" to 17.5,
        "gemini-2.5-flash" to 4.5,
        "gemini-2.0-flash" to 4.0,
        "gemini-1.5-pro" to 8.75,
        "gemini-1.5-flash" to 1.4,
        // DeepSeek
        "deepseek-chat" to 3.0,
        "deepseek-reasoner" to 5.0,
        "deepseek-v3" to 3.0,
        "deepseek-r1" to 4.0,
        // Qwen
        "qwen-max" to 15.0,
        "qwen-plus" to 5.0,
        "qwen-turbo" to 2.0,
        "qwen2.5-72b" to 7.0,
        "qwen2.5-7b" to 2.0,
        "qwen-omni-turbo" to 15.0,
        // GLM
        "glm-4" to 4.5,
        "glm-4-plus" to 14.4,
        "glm-4-flash" to 1.0,
        // Kimi / Moonshot
        "kimi-k2" to 7.2,
        "kimi-k2.5" to 7.2,
        "moonshot-v1-8k" to 7.2,
        "moonshot-v1-32k" to 10.8,
        // Doubao
        "doubao-pro" to 0.7,
        "doubao-lite" to 0.35,
        // Yi / MiniMax / 讯飞
        "yi-lightning" to 3.5,
        "yi-large" to 12.0,
        "abab6.5s" to 4.0,
        "spark-max" to 7.0,
        "spark-lite" to 0.7,
        // 开源通用
        "llama-3.3-70b" to 7.5,
        "llama-3.1-8b" to 2.0,
        "mistral-large" to 14.4,
        "mistral-small" to 2.5,
        "mixtral-8x7b" to 4.0,
        "qwen2.5-coder-32b" to 7.0,
        "qwen2.5-coder-7b" to 2.0,
        "deepseek-coder" to 3.0,
        "grok-2" to 4.2,
        "grok-3" to 10.5
    )

    /** 按模型ID查默认输入价（元/百万token），未收录返回默认价 */
    fun priceOf(modelId: String): Double {
        if (modelId.isBlank()) return DEFAULT_PRICE
        // 精确匹配
        table[modelId]?.let { return it }
        // 前缀模糊匹配（如 gpt-4o-2024-08-06 命中 gpt-4o）
        val lower = modelId.lowercase()
        val matched = table.entries.find { (key, _) ->
            key.length >= 4 && lower.startsWith(key)
        }?.value
        return matched ?: DEFAULT_PRICE
    }

    /** 计算一次调用的预估成本（元），tokens 按输入/输出拆分 */
    fun estimateCost(modelId: String, promptTokens: Long, completionTokens: Long): Double {
        val inPrice = priceOf(modelId)
        val promptCost = promptTokens / 1_000_000.0 * inPrice
        val completionCost = completionTokens / 1_000_000.0 * inPrice * OUTPUT_MULTIPLIER
        return promptCost + completionCost
    }
}