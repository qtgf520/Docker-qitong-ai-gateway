package com.qitong.gateway.http

import com.qitong.gateway.db.Database
import com.qitong.gateway.model.RoutingRule

/**
 * 自定义路由规则引擎 —— 对齐原APP RoutingRuleManager.kt
 * 支持：路径/模型名(*通配符)/API密钥/服务商匹配，route转发 + block拒绝
 */
object RoutingRuleManager {

    fun getAllRules(database: Database): List<RoutingRule> = database.getRoutingRules()

    /** 匹配当前请求命中的规则（按优先级降序，返回第一个匹配） */
    fun matchRule(
        database: Database,
        path: String,
        authHeader: String,
        queryModel: String
    ): RoutingRule? {
        val rules = database.getRoutingRules().filter { it.enabled }.sortedByDescending { it.priority }
        if (rules.isEmpty()) return null

        val apiKey = authHeader.removePrefix("Bearer ").trim()

        for (rule in rules) {
            if (matchesPath(rule.pathPattern, path) &&
                matchesModel(rule.modelPattern, queryModel) &&
                matchesApiKey(rule.apiKeyPattern, apiKey)
            ) {
                return rule
            }
        }
        return null
    }

    private fun matchesPath(pattern: String, path: String): Boolean {
        if (pattern.isBlank()) return false  // 空=不匹配（对齐APP）
        return path.contains(pattern, ignoreCase = true)
    }

    private fun matchesModel(pattern: String, model: String): Boolean {
        if (pattern.isBlank()) return true   // 空=不限制
        return wildcardMatch(pattern, model)
    }

    private fun matchesApiKey(pattern: String, apiKey: String): Boolean {
        if (pattern.isBlank()) return true
        return apiKey.startsWith(pattern, ignoreCase = true)
    }

    /** 通配符匹配：* 匹配任意字符序列（对齐APP：先转义 . 再替换 *） */
    private fun wildcardMatch(pattern: String, text: String): Boolean {
        if (pattern == "*") return true
        if (!pattern.contains("*")) return text.equals(pattern, ignoreCase = true)
        val regex = pattern
            .replace(".", "\\.")
            .replace("*", ".*")
            .toRegex(RegexOption.IGNORE_CASE)
        return regex.matches(text)
    }
}