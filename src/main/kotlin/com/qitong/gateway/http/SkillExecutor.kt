package com.qitong.gateway.http

import com.qitong.gateway.db.Database

/**
 * 技能执行器（对齐原APP ToolExecutor.kt）
 * 根据技能编码执行对应操作，返回结果文本
 */
object SkillExecutor {

    /** 执行技能，返回结果文本 */
    suspend fun execute(
        database: Database,
        code: String,
        param: String,
        userId: Long
    ): String {
        return when (code) {
            // ========== 1xxxxx 测速 ==========
            "100001" -> {
                // 单模型测速
                if (param.isBlank()) "⚠️ 请指定要测速的模型，例如：测速 deepseek-chat"
                else {
                    val models = database.getModels()
                    val target = models.firstOrNull { it.modelId.contains(param, true) || it.displayName.contains(param, true) }
                    if (target == null) "❌ 未找到模型: $param"
                    else {
                        val provider = database.getProviderById(target.providerId)
                        if (provider == null) "❌ 服务商不存在"
                        else {
                            val h = GatewayScheduler.measureModel(target, provider)
                            if (h.isHealthy) "✅ ${target.modelId} 测速完成：TTFT=${h.ttftMs}ms TPS=${"%.2f".format(h.tps)} 总耗时=${h.totalMs}ms"
                            else "❌ ${target.modelId} 测速失败"
                        }
                    }
                }
            }
            "100002" -> {
                // 流水线测速全部
                val results = GatewayScheduler.refreshHealthCache(database, force = true)
                GatewayScheduler.buildPipelineSortedModels(database)
                val ok = results.count { it.isHealthy }
                "✅ 测速完成：$ok/${results.size} 个模型正常"
            }
            "100003" -> "ℹ️ 测速为一次性执行，无需手动停止"

            // ========== 2xxxxx 模型切换 ==========
            "200001", "200002" -> {
                // 切换指定模型（存为用户活跃模型）
                if (param.isBlank()) "⚠️ 请指定模型，例如：切换模型 deepseek-chat"
                else {
                    val models = database.getModels()
                    val target = models.firstOrNull { it.modelId.contains(param, true) || it.displayName.contains(param, true) }
                    if (target == null) "❌ 未找到模型: $param"
                    else {
                        database.setConfig("active_model_key", "${target.providerId}::${target.modelId}")
                        "✅ 已切换活跃模型为: ${target.modelId}"
                    }
                }
            }
            "200005" -> {
                database.setConfig("active_model_key", "")
                "✅ 已恢复自动选择模型"
            }

            // ========== 3xxxxx 网关 ==========
            "300001" -> { database.setConfig("auto_failover", "true"); "✅ 已开启故障转移" }
            "300002" -> { database.setConfig("auto_failover", "false"); "✅ 已关闭故障转移" }
            "300004" -> { GatewayProxy.running = true; "✅ 网关已启动" }
            "300005" -> { GatewayProxy.running = false; "✅ 网关已停止" }
            "300006" -> {
                GatewayProxy.running = !GatewayProxy.running
                if (GatewayProxy.running) "✅ 网关已启动" else "✅ 网关已停止"
            }
            "300008", "300009" -> "ℹ️ 保活功能由部署环境自动管理"

            // ========== 6xxxxx 查询 ==========
            "600001" -> {
                val running = GatewayProxy.running
                val failover = database.getConfig("auto_failover", "true")
                "📊 网关状态：${if (running) "运行中" else "已停止"} | 端口: ${database.getConfig("gateway_port", "18889")} | 故障转移: ${if (failover == "true") "开" else "关"}"
            }
            "600002" -> {
                val keys = GatewayScheduler.pipelineSortedModelKeys
                if (keys.isNotEmpty()) {
                    "📈 测速排行（共${keys.size}个）：\n" + keys.mapIndexed { i, k -> "  #${i + 1} · ${k}" }.joinToString("\n")
                } else "📋 暂无测速数据，请先运行测速"
            }
            "600003" -> {
                val active = database.getConfig("active_model_key", "")
                if (active.isNotBlank()) "🧠 当前活跃模型: ${active.substringAfter("::", active)}"
                else "🧠 当前为 qtai-sj 自动化切换模式"
            }
            "600004" -> "📊 总上行: ${fmt(GatewayProxy.totalUploadBytes)} | 总下行: ${fmt(GatewayProxy.totalDownloadBytes)}"
            "600005" -> {
                val rows = database.getTokenUsageSummary()
                val total = rows.sumOf { (it["total_tokens"] as? Number)?.toLong() ?: 0 }
                "📊 总Token消耗: $total"
            }
            "600007" -> {
                val providers = database.getProviders()
                if (providers.isEmpty()) "📋 暂无服务商" else "📋 服务商列表（${providers.size}个）：\n" + providers.map { "  · ${it.name} (${it.type})" }.joinToString("\n")
            }
            "600008" -> {
                val models = database.getModels()
                if (models.isEmpty()) "📋 暂无模型" else "📋 模型列表（${models.size}个）：\n" + models.map { "  · ${it.modelId}" }.joinToString("\n")
            }

            // ========== 8xxxxx 服务商&模型管理 ==========
            "800001" -> "ℹ️ 请到服务商管理页面添加服务商"
            "800002" -> "ℹ️ 请到服务商管理页面同步模型"
            "800003" -> {
                if (param.isBlank()) "⚠️ 请指定要启用的模型"
                else {
                    val m = database.getModels().firstOrNull { it.modelId.contains(param, true) || it.displayName.contains(param, true) }
                    if (m == null) "❌ 未找到模型: $param"
                    else { database.updateModel(m.copy(isEnabled = true)); "✅ 已启用模型: ${m.modelId}" }
                }
            }
            "800004" -> {
                if (param.isBlank()) "⚠️ 请指定要禁用的模型"
                else {
                    val m = database.getModels().firstOrNull { it.modelId.contains(param, true) || it.displayName.contains(param, true) }
                    if (m == null) "❌ 未找到模型: $param"
                    else { database.updateModel(m.copy(isEnabled = false)); "✅ 已禁用模型: ${m.modelId}" }
                }
            }
            "800006" -> "ℹ️ 请到服务商管理页面删除服务商"

            // ========== 9xxxxx 高级功能 ==========
            "900005" -> {
                val p = database.getPersona(userId)
                if (p != null) { database.savePersona(p.copy(memoryEnabled = true)); "✅ 已开启大脑记忆" } else "✅ 已开启大脑记忆"
            }
            "900006" -> {
                val p = database.getPersona(userId)
                if (p != null) { database.savePersona(p.copy(memoryEnabled = false)); "✅ 已关闭大脑记忆" } else "✅ 已关闭大脑记忆"
            }
            "900009" -> "ℹ️ qtai-sj 自动化切换始终可用"
            "900010" -> { database.setConfig("require_api_key", "true"); "✅ 已开启API密钥验证" }
            "900011" -> { database.setConfig("require_api_key", "false"); "✅ 已关闭API密钥验证" }

            else -> "❌ 未知技能编码: $code"
        }
    }

    private fun fmt(b: Long): String {
        if (b < 1024) return "$b B"
        if (b < 1048576) return "%.1f KB".format(b / 1024.0)
        return "%.1f MB".format(b / 1048576.0)
    }
}