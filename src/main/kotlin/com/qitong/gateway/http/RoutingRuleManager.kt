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
        if (pattern.isBlank()) return true
        return wildcardMatch(pattern, path)
    }

    private fun matchesModel(pattern: String, model: String): Boolean {
        if (pattern.isBlank()) return true
        return wildcardMatch(pattern, model)
    }

    private fun matchesApiKey(pattern: String, apiKey: String): Boolean {
        if (pattern.isBlank()) return true
        return wildcardMatch(pattern, apiKey)
    }

    /** 通配符匹配：* 匹配任意字符序列 */
    private fun wildcardMatch(pattern: String, text: String): Boolean {
        return Regex(
            pattern.replace("*", ".*"),
            RegexOption.IGNORE_CASE
        ).matches(text)
    }
}