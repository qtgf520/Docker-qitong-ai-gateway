package com.qitong.gateway.sandbox

import com.qitong.gateway.db.Database
import com.qitong.gateway.http.PricingTable

/**
 * v1.155 沙盒补齐 Ext1：价格表 / 用量统计 / 系统监控 / 备份 / 套餐卡密 / SSH
 * 返回值 null = 本文件不处理该函数（交给 Ext2 / 未知提示）
 */
object V155Ext1 {
    suspend fun execute(fn: String, args: Map<String, String>, isAdmin: Boolean, userId: Long, db: Database, channel: String = "qq"): String? = when (fn) {
        "price_list" -> {
            val builtin = PricingTable.table()
            val custom = db.getAllModelPrices()
            val sb = StringBuilder("💰 【模型价格表】（元/百万token，输出=输入×2）\n")
            builtin.toSortedMap().forEach { (mid, v) ->
                val input = (v["input"] as? Number)?.toDouble() ?: 0.0
                val customPrice = custom.firstOrNull { (it["modelId"] as? String) == mid }
                if (customPrice != null) {
                    val ci = (customPrice["inputPrice"] as? Number)?.toDouble() ?: 0.0
                    val co = (customPrice["outputPrice"] as? Number)?.toDouble() ?: 0.0
                    sb.append("• $mid：¥").append(fmt(ci)).append("/¥").append(fmt(co)).append("（自定义）\n")
                } else if (input > 0) {
                    sb.append("• $mid：¥").append(fmt(input)).append("\n")
                }
            }
            custom.forEach { c ->
                val mid = (c["modelId"] as? String) ?: return@forEach
                if (builtin.containsKey(mid)) return@forEach
                val ci = (c["inputPrice"] as? Number)?.toDouble() ?: 0.0
                val co = (c["outputPrice"] as? Number)?.toDouble() ?: 0.0
                sb.append("• $mid：¥").append(fmt(ci)).append("/¥").append(fmt(co)).append("（自定义）\n")
            }
            "📊 默认价 ¥" + fmt(PricingTable.DEFAULT_PRICE) + "，共 " + builtin.size + " 模型\n" + sb.toString().take(1800)
        }
        "price_get" -> {
            val mid = args["model_id"]?.trim().orEmpty()
            if (mid.isBlank()) "⚠️ 语法：price_get(model_id=模型ID)"
            else {
                val custom = db.getAllModelPrices().firstOrNull { (it["modelId"] as? String) == mid }
                val builtinPrice = PricingTable.priceOf(mid)
                if (custom != null) {
                    val ci = (custom["inputPrice"] as? Number)?.toDouble() ?: 0.0
                    val co = (custom["outputPrice"] as? Number)?.toDouble() ?: 0.0
                    "💰 $mid：输入 ¥${fmt(ci)} / 输出 ¥${fmt(co)}（自定义价格）"
                } else {
                    "💰 $mid：输入 ¥${fmt(builtinPrice)} / 输出 ¥${fmt(builtinPrice * PricingTable.OUTPUT_MULTIPLIER)}（内置默认）"
                }
            }
        }
        "price_set" -> {
            if (!isAdmin) return "⛔ 仅管理员可维护价格表"
            val mid = args["model_id"]?.trim().orEmpty()
            val input = args["input_price"]?.toDoubleOrNull()
            if (mid.isBlank() || input == null || input < 0) "⚠️ 语法：price_set(model_id=模型ID, input_price=输入价, output_price=可选)"
            else {
                val output = args["output_price"]?.toDoubleOrNull() ?: (input * PricingTable.OUTPUT_MULTIPLIER)
                db.saveModelPrice(mid, input, output)
                "✅ 价格已保存：$mid 输入 ¥${fmt(input)} / 输出 ¥${fmt(output)}"
            }
        }
        "price_delete" -> {
            if (!isAdmin) return "⛔ 仅管理员可维护价格表"
            val mid = args["model_id"]?.trim().orEmpty()
            if (mid.isBlank()) "⚠️ 语法：price_delete(model_id=模型ID)"
            else if (db.deleteModelPrice(mid)) "✅ 已删除 $mid 的自定义价格"
            else "⚠️ 该模型没有自定义价格"
        }
        "price_clear" -> {
            if (!isAdmin) return "⛔ 仅管理员可维护价格表"
            val n = db.clearAllModelPrices()
            "✅ 已清空 $n 条自定义价格（恢复内置默认表）"
        }
        "usage_recent" -> {
            val limit = (args["limit"] ?: "20").toIntOrNull()?.coerceIn(1, 100) ?: 20
            val user = db.getUserById(userId)
            val rows = com.qitong.gateway.http.AdminApi.getUsageRecent(db, user, limit)
            if (rows.isEmpty()) "📭 暂无用量记录"
            else "📊 【最近用量 $limit 条】\n" + rows.joinToString("\n") { t ->
                val cost = (t["cost"] as? Number)?.toDouble() ?: 0.0
                val time = (t["createdAt"] as? Number)?.toLong() ?: 0L
                val ts = if (time > 0) java.text.SimpleDateFormat("MM-dd HH:mm").format(java.util.Date(time)) else ""
                "• ${t["modelKey"]}｜${t["promptTokens"]}+${t["completionTokens"]} tok｜¥${fmt(cost)}｜$ts"
            }.take(1600)
        }
        "usage_stats" -> {
            val user = db.getUserById(userId)
            val rows = if (user?.role == "admin") db.getTokenUsageSummary() else db.getTokenUsageByUser(userId)
            if (rows.isEmpty()) "📭 暂无用量统计"
            else "📊 【用量统计】\n" + rows.joinToString("\n") { r ->
                val mt = (r["model_key"] ?: "?").toString()
                val tt = (r["total_tokens"] ?: 0)
                val tc = (r["cost"] ?: 0)
                val up = (r["upload_bytes"] ?: 0)
                val dn = (r["download_bytes"] ?: 0)
                "• $mt：${tt} tok｜¥${fmt((tc as? Number)?.toDouble() ?: 0.0)}｜↑${fmtBytes((up as? Number)?.toLong() ?: 0)} ↓${fmtBytes((dn as? Number)?.toLong() ?: 0)}"
            }.take(1600)
        }
        "overall_stats" -> {
            val user = db.getUserById(userId)
            val stats = com.qitong.gateway.http.AdminApi.getStats(db, user)
            "📈 【网关总览】\n" + stats.toString().replace("{", "").replace("}", "").replace(",", "\n").replace("\"", "").take(1600)
        }
        "sys_monitor" -> {
            if (!isAdmin) return "⛔ 仅管理员可查看系统监控"
            val snap = com.qitong.gateway.monitor.SystemMonitor.snapshot()
            val sb = StringBuilder("🖥 【系统监控】\n")
            snap.forEach { (k, v) -> if (v != null) sb.append("• $k：$v\n") }
            sb.toString().take(1600)
        }
        "plan_stats" -> {
            if (!isAdmin) return "⛔ 仅管理员可查看套餐统计"
            val users = db.getUsers()
            val now = System.currentTimeMillis()
            val names = mapOf("free" to "免费版", "quota" to "额度版", "subscription" to "订阅版", "agent" to "代理版", "business" to "商业版")
            val dist = LinkedHashMap<String, Int>()
            var activePaid = 0; var expired = 0; var expiringSoon = 0
            for (usr in users) {
                dist[usr.plan] = (dist[usr.plan] ?: 0) + 1
                if (usr.plan != "free") {
                    if (usr.planExpireAt == 0L || usr.planExpireAt > now) activePaid++ else expired++
                    if (usr.planExpireAt > now && usr.planExpireAt - now < 7L * 24 * 3600 * 1000) expiringSoon++
                }
            }
            "📊 【套餐统计】\n" +
                "• 总用户：${users.size}\n" +
                dist.map { (pl, cnt) -> "• ${names[pl] ?: pl}：$cnt 人" }.joinToString("\n") + "\n" +
                "• 活跃付费：$activePaid｜已过期：$expired｜7天内到期：$expiringSoon\n" +
                "• 累计充值：¥${fmt(users.sumOf { it.totalRecharge })}｜总余额：¥${fmt(users.sumOf { it.balance })}"
        }
        "backup_list" -> {
            if (!isAdmin) return "⛔ 仅管理员"
            val list = com.qitong.gateway.monitor.BackupManager.listBackups()
            if (list.isEmpty()) "📭 暂无备份"
            else "🗄 【备份列表】\n" + list.joinToString("\n") { b ->
                val t = (b["time"] as? Number)?.toLong() ?: 0L
                val ts = if (t > 0) java.text.SimpleDateFormat("MM-dd HH:mm").format(java.util.Date(t)) else ""
                "• ${b["name"]}｜${b["sizeMB"]}MB｜$ts"
            }.take(1600)
        }
        "backup_create" -> {
            if (!isAdmin) return "⛔ 仅管理员"
            val path = com.qitong.gateway.monitor.BackupManager.backup(db.jdbcUrl())
            "✅ 备份完成：$path"
        }
        "backup_restore" -> {
            if (!isAdmin) return "⛔ 仅管理员"
            val name = args["name"]?.trim().orEmpty()
            if (name.isBlank()) "⚠️ 语法：backup_restore(name=备份文件名)"
            else if (com.qitong.gateway.monitor.BackupManager.restore(name, db.dbPath())) "✅ 已恢复 $name，重启容器生效"
            else "⚠️ 恢复失败（备份文件不存在）"
        }
        "plan_list" -> {
            val plans = db.getPlanDefs(onlyEnabled = false)
            if (plans.isEmpty()) "📭 暂无套餐"
            else "🎟 【套餐列表】\n" + plans.joinToString("\n") { p ->
                "• ${p["code"]}｜${p["name"]}｜¥${p["price"]}/${(p["months"] ?: 0)}月｜额度${(p["quotaLimit"] ?: 0)}${if ((p["enabled"] as? Boolean) == true) "✅" else "⛔停用"}"
            }.take(1600)
        }
        "plan_save" -> {
            if (!isAdmin) return "⛔ 仅管理员"
            val code = args["code"]?.trim().orEmpty()
            val name = args["name"]?.trim().orEmpty()
            val price = args["price"]?.toDoubleOrNull()
            if (code.isBlank() || name.isBlank() || price == null) "⚠️ 语法：plan_save(code=套餐编码, name=套餐名, price=价格, months=时长, quota_limit=额度)"
            else {
                db.upsertPlanDef(code, name, price, args["months"]?.toIntOrNull() ?: 1, args["quota_limit"]?.toLongOrNull() ?: 0L, 0, 0, args["intro"] ?: "", 0, true)
                "✅ 套餐「$name」已保存"
            }
        }
        "plan_delete" -> {
            if (!isAdmin) return "⛔ 仅管理员"
            val code = args["code"]?.trim().orEmpty()
            if (code.isBlank()) "⚠️ 语法：plan_delete(code=套餐编码)"
            else if (db.deletePlanDef(code)) "✅ 套餐 $code 已删除"
            else "⚠️ 套餐 $code 不存在"
        }
        "card_list" -> {
            if (!isAdmin) return "⛔ 仅管理员"
            val cards = db.getRechargeCards(args["batch"] ?: "", 100)
            if (cards.isEmpty()) "📭 暂无卡密"
            else "🎟 【充值卡密】\n" + cards.joinToString("\n") { c ->
                val usedBy = (c["usedBy"] as? Number)?.toLong() ?: 0L
                "• ${c["code"]}｜¥${c["amount"]}｜${if (usedBy > 0) "已用(用户$usedBy)" else "未使用"}"
            }.take(1800)
        }
        "card_gen" -> {
            if (!isAdmin) return "⛔ 仅管理员"
            val amount = args["amount"]?.toDoubleOrNull()
            val count = (args["count"] ?: "10").toIntOrNull()?.coerceIn(1, 200) ?: 10
            if (amount == null || amount <= 0) "⚠️ 语法：card_gen(amount=面额, count=数量默认10, batch=批次)"
            else {
                val codes = db.genRechargeCards(amount, count, args["batch"] ?: "", userId)
                "✅ 生成 $count 张 ¥${fmt(amount)} 卡密：\n" + codes.joinToString("\n") + "\n（card_list 可查）"
            }
        }
        "card_redeem" -> {
            if (userId <= 0) return "🔓 未绑定网关账号，先 account_bind 绑定后再兑换"
            val code = args["code"]?.trim().orEmpty()
            if (code.isBlank()) "⚠️ 语法：card_redeem(code=卡密)"
            else {
                val amt = db.redeemRechargeCard(code, userId)
                if (amt != null) "✅ 兑换成功！到账 ¥${fmt(amt)}"
                else "❌ 卡密无效或已被使用"
            }
        }
        "ssh_list" -> {
            if (!isAdmin) return "⛔ 仅管理员"
            val list = db.listSshConfigs()
            if (list.isEmpty()) "📭 暂无 SSH 配置"
            else "🔌 【SSH 配置】\n" + list.joinToString("\n") { s -> "• ${s["name"]}｜${s["host"]}:${s["port"]}｜${s["username"]}" }.take(1600)
        }
        "ssh_exec" -> {
            if (!isAdmin) return "⛔ 仅管理员"
            val name = args["name"]?.trim().orEmpty()
            val cmd = args["cmd"]?.trim().orEmpty()
            if (name.isBlank() || cmd.isBlank()) "⚠️ 语法：ssh_exec(name=SSH配置名, cmd=要执行的命令)"
            else {
                val (ok, out) = com.qitong.gateway.http.TerminalManager.sshExec(name, cmd, "")
                if (ok) "🖥 【$name】输出：\n$out".take(1800) else "❌ 执行失败：$out"
            }
        }
        else -> null
    }

    private fun fmt(v: Double): String = if (v < 0.005) "%.4f".format(v) else "%.2f".format(v)
    private fun fmtBytes(b: Long): String {
        if (b >= 1024L * 1024 * 1024) return "%.1fGB".format(b / 1024.0 / 1024 / 1024)
        if (b >= 1024L * 1024) return "%.1fMB".format(b / 1024.0 / 1024)
        if (b >= 1024L) return "%.1fKB".format(b / 1024.0)
        return "${b}B"
    }
}