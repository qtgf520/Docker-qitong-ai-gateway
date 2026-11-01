package com.qitong.gateway.sandbox

import com.qitong.gateway.db.Database

/**
 * v1.166 沙盒补齐 Ext2：工单 / 日志 / 记忆 / QQ用户 / 网关控制 / 通知 / 规则 / 会话
 * 返回值 null = 本文件不处理该函数（交给未知提示）
 */
object V155Ext2 {
    suspend fun execute(fn: String, args: Map<String, String>, isAdmin: Boolean, userId: Long, db: Database, channel: String = "qq"): String? = when (fn) {
        "ticket_list" -> {
            val rows = db.getTickets(if (isAdmin) null else userId, isAdmin, 50)
            if (rows.isEmpty()) "📭 暂无工单"
            else "🎫 【工单列表】\n" + rows.joinToString("\n") { t ->
                "• #${t["id"]}｜${t["title"]}｜${t["status"]}"
            }.take(1600)
        }
        "ticket_create" -> {
            val title = args["title"]?.trim().orEmpty()
            if (title.isBlank()) "⚠️ 语法：ticket_create(title=工单标题)"
            else if (userId <= 0) "🔓 未绑定网关账号，先 account_bind 绑定"
            else {
                val id = db.addTicket(userId, title)
                "✅ 工单 #$id 已创建（内容可用 ticket_detail 查看/回复）"
            }
        }
        "ticket_detail" -> {
            if (!isAdmin) return "⛔ 仅管理员可查任意工单"
            val id = args["ticket_id"]?.toLongOrNull() ?: 0
            val msgs = if (id > 0) db.getTicketMessages(id) else emptyList()
            if (msgs.isEmpty()) "⚠️ 工单 #$id 不存在或暂无消息"
            else "🎫 【工单 #$id 消息】\n" + msgs.joinToString("\n") { m ->
                "• [${m["role"]}] ${m["content"]}"
            }.take(1600)
        }
        "op_logs" -> {
            if (!isAdmin) return "⛔ 仅管理员"
            val rows = db.getOpLogs(null, 50)
            if (rows.isEmpty()) "📭 暂无操作日志"
            else "🧾 【操作日志(最近50)】\n" + rows.joinToString("\n") { r ->
                "• ${r["username"]}｜${r["action"]}｜${r["detail"]}"
            }.take(1600)
        }
        "login_logs" -> {
            if (!isAdmin) return "⛔ 仅管理员"
            val rows = db.getLoginLogs(null, 50)
            if (rows.isEmpty()) "📭 暂无登录日志"
            else "🔐 【登录日志(最近50)】\n" + rows.joinToString("\n") { r ->
                "• ${r["username"]}｜${if (r["success"] == true) "✅成功" else "❌失败"}｜${r["ip"]}"
            }.take(1600)
        }
        "qq_logs" -> {
            if (!isAdmin) return "⛔ 仅管理员"
            val rows = db.getQqLogs(50)
            if (rows.isEmpty()) "📭 暂无QQ日志"
            else "💬 【QQ日志(最近50)】\n" + rows.joinToString("\n") { r ->
                "• ${r["type"]}｜${r["content"]}"
            }.take(1600)
        }
        "wx_logs" -> {
            if (!isAdmin) return "⛔ 仅管理员"
            val rows = db.getWeixinLogs(50)
            if (rows.isEmpty()) "📭 暂无微信日志"
            else "💬 【微信日志(最近50)】\n" + rows.joinToString("\n") { r ->
                "• ${r["type"]}｜${r["content"]}"
            }.take(1600)
        }
        "memory_list" -> {
            if (!isAdmin) return "⛔ 仅管理员可看全部记忆；普通用户用 memory_search"
            val rows = db.getAllMemories(100)
            if (rows.isEmpty()) "📭 暂无记忆"
            else "🧠 【记忆库(最近100)】\n" + rows.joinToString("\n") { m ->
                "• [${m["sourceNote"]}] ${m["title"]}：${(m["content"] as? String ?: "").take(60)}"
            }.take(1800)
        }
        "memory_add" -> {
            val title = args["title"]?.trim().orEmpty()
            val content = args["content"]?.trim().orEmpty()
            if (title.isBlank() || content.isBlank()) "⚠️ 语法：memory_add(title=标题, content=内容)"
            else if (userId <= 0) "🔓 未绑定账号，先绑定"
            else {
                db.addMemory(userId, title, content, "manual", "", 5, "manual", "", "")
                "✅ 记忆已保存「$title」"
            }
        }
        "memory_clear" -> {
            if (!isAdmin) return "⛔ 仅管理员"
            val n = db.clearAllMemories()
            "✅ 已清空 $n 条记忆"
        }
        "qq_users_list" -> {
            if (!isAdmin) return "⛔ 仅管理员"
            val rows = db.getQqUsers(100)
            if (rows.isEmpty()) "📭 暂无QQ用户"
            else "👥 【QQ用户(最近100)】\n" + rows.joinToString("\n") { r ->
                "• ${(r["displayName"] as? String)?.takeIf { it.isNotBlank() } ?: r["openid"]}｜消息${r["totalMessages"] ?: 0}｜权限${r["permLevel"] ?: 0}"
            }.take(1600)
        }
        "qq_point_adjust" -> {
            if (!isAdmin) return "⛔ 仅管理员"
            val openid = args["openid"]?.trim().orEmpty()
            val delta = args["delta"]?.toIntOrNull()
            if (openid.isBlank() || delta == null) "⚠️ 语法：qq_point_adjust(openid=QQ用户openid, delta=积分增减)"
            else {
                db.adjustQqPoints(openid, delta)
                "✅ QQ用户 $openid 积分已调整 $delta"
            }
        }
        // ★ v1.166 微信用户函数（对齐 QQ：用户列表/记忆查看/记忆清空）
        "wx_users_list" -> {
            if (!isAdmin) return "⛔ 仅管理员"
            val rows = db.getWeixinUsers(100)
            if (rows.isEmpty()) "📭 暂无微信用户"
            else "👥 【微信用户(最近100)】\n" + rows.joinToString("\n") { r ->
                val bound = (r["boundUserId"] as? Long ?: 0L)
                "• ${(r["displayName"] as? String)?.takeIf { it.isNotBlank() } ?: r["openid"]}" +
                    (if (bound > 0) "｜已绑账号#$bound" else "｜未绑定") +
                    "｜权限${r["permLevel"] ?: 1}"
            }.take(1600)
        }
        "wx_user_memory" -> {
            if (!isAdmin) return "⛔ 仅管理员"
            val openid = args["openid"]?.trim().orEmpty()
            if (openid.isBlank()) "⚠️ 语法：wx_user_memory(openid=微信用户openid, botId=可选)"
            else {
                val botId = args["botId"]?.toLongOrNull() ?: 0L
                val rows = db.getWxBrainMemoryItems(openid, limit = 30, botId = botId)
                if (rows.isEmpty()) "📭 该微信用户还没有记忆"
                else "🧠 【微信用户记忆】\n" + rows.joinToString("\n") { m ->
                    val ts = m["timestamp"] as? Long ?: 0L
                    val dateStr = if (ts > 0) {
                        val sdf = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)
                        sdf.format(java.util.Date(ts))
                    } else ""
                    "• [$dateStr] ${m["content"]}"
                }.take(1600)
            }
        }
        "wx_user_memory_clear" -> {
            if (!isAdmin) return "⛔ 仅管理员"
            val openid = args["openid"]?.trim().orEmpty()
            if (openid.isBlank()) "⚠️ 语法：wx_user_memory_clear(openid=微信用户openid)"
            else {
                db.clearWxUserAllMemories(openid)
                "✅ 微信用户 $openid 记忆已清空"
            }
        }
        "gateway_toggle" -> {
            if (!isAdmin) return "⛔ 仅管理员可控制网关全局启停"
            val action = args["action"] ?: "toggle"
            val newState = com.qitong.gateway.http.GatewayProxy.toggleGateway(action, db)
            if (newState) "✅ 网关已启动" else "🛑 网关已停止"
        }
        "gateway_user_toggle" -> {
            if (userId <= 0) return "🔓 未绑定账号"
            val newState = db.getUserConfig(userId, "api_enabled", "true") == "false"
            db.setUserConfig(userId, "api_enabled", newState.toString())
            if (newState) "✅ 我的 API 已启用" else "🛑 我的 API 已暂停"
        }
        "gateway_active_model" -> {
            val key = args["modelKey"]?.trim().orEmpty()
            if (key.isBlank()) {
                val cur = if (isAdmin) db.getConfig("active_model_key", "") else db.getUserConfig(userId, "active_model_key", "")
                "🎯 当前活跃模型：${if (cur.isBlank()) "qtai-sj 自动" else cur.substringAfter("::", cur)}"
            } else {
                if (isAdmin) db.setConfig("active_model_key", key)
                else db.setUserConfig(userId, "active_model_key", key)
                "✅ 活跃模型已切换为 ${key.substringAfter("::", key)}"
            }
        }
        "gateway_forced_pool" -> {
            val action = args["action"] ?: "add"
            val modelKey = args["modelKey"]?.trim().orEmpty()
            val dir = args["dir"]?.trim() ?: "down"   // ★ v1.166 move 用：up=往前(提高优先)/down=往后
            if (modelKey.isBlank() && action != "clear") return "⚠️ 语法：gateway_forced_pool(action=add/remove/move/clear, modelKey=模型Key, dir=up/down)"
            val key = "forced_pool_keys"
            val current = db.getConfig(key, "").split(",").filter { it.isNotBlank() }.toMutableList()
            when (action) {
                "add" -> if (modelKey.isNotBlank()) { current.remove(modelKey); current.add(0, modelKey); db.setConfig("active_model_key", modelKey) }
                "remove" -> current.remove(modelKey)
                "move" -> {
                    // ★ v1.166 调池内顺序（序号=切换优先）：up=往前移一位 / down=往后移一位
                    val idx = current.indexOf(modelKey)
                    val target = if (dir == "up") idx - 1 else idx + 1
                    if (idx >= 0 && target >= 0 && target < current.size) {
                        val item = current.removeAt(idx)
                        current.add(target, item)
                    }
                }
                "clear" -> current.clear()
                else -> return "⚠️ action 必须是 add/remove/move/clear"
            }
            db.setConfig(key, current.joinToString(","))
            if (action == "move") "✅ 顺序已调整（序号=切换优先）：${if (current.isEmpty()) "空（自动选择）" else current.joinToString("、")}"
            else "✅ 强制故障池已更新：${if (current.isEmpty()) "空（自动选择）" else current.joinToString("、")}"
        }
        "gateway_auto_speedtest" -> {
            val enabled = (args["enabled"] ?: "").toBooleanStrictOrNull() ?: false
            val interval = (args["intervalMin"] ?: "240").toIntOrNull() ?: 240
            if (isAdmin) {
                db.setConfig("auto_speedtest", enabled.toString())
                db.setConfig("speed_interval_min", interval.toString())
                com.qitong.gateway.http.GatewayProxy.setAutoSpeedTest(enabled, interval, db)
                if (enabled) "✅ 全局自动测速已开启（每 $interval 分钟）" else "🛑 全局自动测速已停止"
            } else {
                db.setUserConfig(userId, "auto_speedtest", enabled.toString())
                db.setUserConfig(userId, "speed_interval_min", interval.toString())
                if (enabled) "✅ 我的自动测速已开启（每 $interval 分钟）" else "🛑 我的自动测速已停止"
            }
        }
        "notify_config" -> {
            if (!isAdmin) return "⛔ 仅管理员"
            val cfg = com.qitong.gateway.notify.NotificationManager.getConfig(db)
            "🔔 【通知配置】\n" + cfg.map { (k, v) -> "• $k = $v" }.joinToString("\n").take(1600)
        }
        "notify_test" -> {
            if (!isAdmin) return "⛔ 仅管理员"
            val msg = args["message"]?.takeIf { it.isNotBlank() } ?: "【綦桐AI网关】测试通知，配置成功！"
            com.qitong.gateway.notify.NotificationManager.notify(db, "綦桐AI网关通知", msg)
            "✅ 测试通知已发送（钉钉/邮箱）"
        }
        "rule_list" -> {
            if (!isAdmin) return "⛔ 仅管理员"
            val rows = db.getRoutingRules()
            if (rows.isEmpty()) "📭 暂无路由规则"
            else "🚦 【路由规则】\n" + rows.joinToString("\n") { r ->
                "• [${r.id}] ${r.name}（${r.pathPattern.ifBlank { "*" }}→${r.targetModelKey.ifBlank { "默认" }}）${if (r.enabled) "✅" else "⛔"}"
            }.take(1600)
        }
        "rule_add" -> {
            if (!isAdmin) return "⛔ 仅管理员"
            val name = args["name"]?.trim().orEmpty()
            val targetModelKey = args["target_model"]?.trim().orEmpty()
            if (name.isBlank() || targetModelKey.isBlank()) "⚠️ 语法：rule_add(name=规则名, target_model=目标模型Key)"
            else {
                db.addRoutingRule(com.qitong.gateway.model.RoutingRule(
                    name = name,
                    enabled = true,
                    priority = (args["priority"] ?: "0").toIntOrNull() ?: 0,
                    pathPattern = args["path_pattern"] ?: "",
                    modelPattern = args["model_pattern"] ?: "",
                    apiKeyPattern = args["api_key_pattern"] ?: "",
                    providerId = null,
                    targetModelKey = targetModelKey,
                    action = "route",
                    createdAt = System.currentTimeMillis(),
                    ownerId = 0
                ))
                "✅ 路由规则「$name」已添加"
            }
        }
        "rule_delete" -> {
            if (!isAdmin) return "⛔ 仅管理员"
            val id = args["id"]?.toLongOrNull() ?: 0
            if (id <= 0) "⚠️ 语法：rule_delete(id=规则ID)"
            else {
                db.deleteRoutingRule(id)
                "✅ 路由规则 #$id 已删除"
            }
        }
        "conversation_list" -> {
            val rows = db.getConversationsByUser(userId)
            if (rows.isEmpty()) "📭 暂无会话记录"
            else "💬 【我的会话】\n" + rows.take(30).joinToString("\n") { c ->
                "• [${c.id}] ${c.title}"
            }.take(1400)
        }
        else -> null
    }
}