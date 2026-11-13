package com.qitong.gateway.sandbox

import com.qitong.gateway.db.Database

/**
 * v1.166 沙盒补齐 Ext2：工单 / 日志 / 记忆 / QQ用户 / 网关控制 / 通知 / 规则 / 会话
 * 返回值 null = 本文件不处理该函数（交给未知提示）
 */
object V155Ext2 {
    suspend fun execute(fn: String, args: Map<String, String>, isAdmin: Boolean, userId: Long, db: Database, channel: String = "qq"): String? = when (fn) {
        // ★ v1.195 多子 Agent 调度：派发/查询
        "subagent_run" -> {
            val task = (args["task"] ?: "").trim()
            if (task.isBlank()) "⚠️ 语法：subagent_run(task=子任务描述, model=模型可空, wait=1前台等待 or 后台)"
            else {
                val model = (args["model"] ?: "").trim().ifBlank { "qtai-sj" }
                val wait = (args["wait"] ?: "").trim() == "1"
                val session = args["session"] ?: ""
                val taskId = com.qitong.gateway.sandbox.SubAgentEngine.dispatch(
                    db, userId, channel, session, task, model, if (wait) 45_000L else 0L
                )
                when {
                    taskId == -1L -> "⛔ 子Agent并发已满（最多8个），请稍后再试"
                    taskId == 0L -> "⚠️ 子任务描述不能为空"
                    wait -> {
                        val t = db.getSubagentTaskById(taskId)
                        val status = (t?.get("status") as? String) ?: "running"
                        if (status == "done") "✅ 子Agent[$taskId]完成：\n${t?.get("result") as? String ?: "(无结果)"}"
                        else "⏳ 子Agent[$taskId] 处理中（$status）…可用 subagent_result(id=$taskId) 查询"
                    }
                    else -> "🤖 子Agent任务 #$taskId 已派发后台执行（$task）\n完成后可用 subagent_result(id=$taskId) 查结果"
                }
            }
        }
        "subagent_result" -> {
            val id = args["id"]?.trim()?.toLongOrNull()
            if (id == null) "⚠️ 语法：subagent_result(id=子任务ID)"
            else {
                val t = db.getSubagentTaskById(id)
                if (t == null) "❌ 子任务 #$id 不存在"
                else {
                    val st = (t["status"] as? String) ?: "running"
                    when (st) {
                        "done" -> "✅ 子Agent #$id 完成：\n${t["result"]}"
                        "failed" -> "❌ 子Agent #$id 失败：${t["error"]}"
                        else -> "⏳ 子Agent #$id 执行中…（任务：${(t["taskDesc"] as? String ?: "").take(60)}）"
                    }
                }
            }
        }
        // ★ v1.216 主动发消息给用户（学习一流智能体平台 proactive message，自研实现）
        // 支持三渠道：QQ（group:群号 或 user:openid）/ 微信（openid）/ 公众号（粉丝openid）
        "send_message_to_user" -> {
            val text = (args["text"] ?: "").trim()
            val image = (args["image"] ?: "").trim()
            val file = (args["file"] ?: "").trim()
            val target = (args["target"] ?: "").trim()
            if (text.isBlank() && image.isBlank() && file.isBlank())
                "⚠️ 语法：send_message_to_user(text=内容, target=目标可空, image=图片URL可空, file=文件路径可空)"
            else {
                val content = buildString {
                    if (text.isNotBlank()) append(text)
                    if (image.isNotBlank()) { if (isNotEmpty()) append("\n"); append("🖼 $image") }
                    if (file.isNotBlank()) { if (isNotEmpty()) append("\n"); append("📎 $file") }
                }
                when (channel) {
                    "weixin", "wx" -> {
                        val openid = target
                        if (openid.isBlank()) "⚠️ 微信渠道需指定 target=用户openid"
                        else {
                            com.qitong.gateway.weixin.WeixinBotManager.sendActiveGreet(openid, content)
                            "✅ 消息已发送给微信用户 $openid（若机器人离线则未送达）"
                        }
                    }
                    "mp" -> {
                        val openid = target
                        if (openid.isBlank()) "⚠️ 公众号渠道需指定 target=粉丝openid"
                        else {
                            val bots = db.getMpBots()
                            if (bots.isEmpty()) "❌ 未配置任何公众号"
                            else {
                                // getMpBots 返回精简字段，需用 getMpBotById 取完整配置（含 appSecret）
                                val fullBot = db.getMpBotById((bots.first()["id"] as? Number)?.toLong() ?: 0L)
                                if (fullBot == null) "❌ 公众号配置读取失败"
                                else {
                                    val ok = com.qitong.gateway.mpx.MpPush.sendText(db, fullBot, openid, content)
                                    if (ok) "✅ 消息已发送给公众号粉丝 $openid"
                                    else "❌ 发送失败（未认证号无客服消息权限或 token 异常）"
                                }
                            }
                        }
                    }
                    else -> {
                        // QQ 默认渠道：group:群号 / user:openid / c2c:openid / 裸串按群号
                        val tgt = target
                        if (tgt.isBlank()) "⚠️ QQ渠道需指定 target（group:群号 或 user:用户openid）"
                        else if (tgt.startsWith("group:")) {
                            val gid = tgt.removePrefix("group:")
                            com.qitong.gateway.qq.QqBotManager.sendActiveGreetToGroup(gid, content)
                            "✅ 消息已发送到QQ群 $gid"
                        } else if (tgt.startsWith("user:") || tgt.startsWith("c2c:")) {
                            val uid = tgt.substringAfter(":")
                            val ok = com.qitong.gateway.qq.QqBotManager.sendToUserC2C(uid, content)
                            if (ok) "✅ 消息已私聊发送给 $uid"
                            else "❌ 私聊发送失败（机器人未配置或token异常）"
                        } else {
                            com.qitong.gateway.qq.QqBotManager.sendActiveGreetToGroup(tgt, content)
                            "✅ 消息已发送到QQ群 $tgt"
                        }
                    }
                }
            }
        }
        // ★ v1.216 万能表情包：发一条趣味表情组合（QQ 群/私聊/微信都可用）
        //   kw=关键词（大笑/笑哭/点赞/加油/666/比心…），空=随机；支持 channel 指定（qq/weixin/wx/mp），qq 可带 target group:群号
        "send_sticker" -> {
            val kw = (args["kw"] ?: "").trim()
            val tgt = (args["target"] ?: "").trim()
            val sticker = com.qitong.gateway.qq.QqBotManager.buildSticker(kw)
            when (channel) {
                "weixin", "wx" -> {
                    val openid = tgt
                    if (openid.isBlank()) "⚠️ 微信渠道需指定 target=用户openid"
                    else {
                        com.qitong.gateway.weixin.WeixinBotManager.sendActiveGreet(openid, sticker)
                        "✅ 表情包已发送给微信用户 $openid：$sticker"
                    }
                }
                "mp" -> {
                    val openid = tgt
                    if (openid.isBlank()) "⚠️ 公众号渠道需指定 target=粉丝openid"
                    else {
                        val bots = db.getMpBots()
                        if (bots.isEmpty()) "❌ 未配置任何公众号"
                        else {
                            val fullBot = db.getMpBotById((bots.first()["id"] as? Number)?.toLong() ?: 0L)
                            if (fullBot == null) "❌ 公众号配置读取失败"
                            else if (com.qitong.gateway.mpx.MpPush.sendText(db, fullBot, openid, sticker)) "✅ 表情包已发送给公众号粉丝 $openid"
                            else "❌ 发送失败"
                        }
                    }
                }
                else -> {
                    val t = tgt
                    if (t.startsWith("group:")) {
                        com.qitong.gateway.qq.QqBotManager.sendActiveGreetToGroup(t.removePrefix("group:"), sticker)
                        "✅ 表情包已发到QQ群 ${t.removePrefix("group:")}：$sticker"
                    } else if (t.isNotBlank()) {
                        val ok = com.qitong.gateway.qq.QqBotManager.sendToUserC2C(t, sticker)
                        if (ok) "✅ 表情包已私聊发送给 $t"
                        else "❌ 私聊发送失败"
                    } else {
                        "🖼 请在群里发「表情包」或指定 target（group:群号 / 用户openid）：$sticker"
                    }
                }
            }
        }
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
        // ★ v1.216 平台设置遥控（智能体可自主读取/修改 QQ/微信/公众号 三渠道 AI开关/消息记录/主动问候）
        "platform_settings_get" -> {
            val channel = (args["channel"] ?: "").trim().lowercase()
            if (channel !in listOf("qq", "weixin", "mp")) "⚠️ 语法：platform_settings_get(channel=qq/weixin/mp)"
            else {
                val ps = db.getPlatformSettings(channel)
                "⚙️ 【${channel.uppercase()} 平台设置】\n" +
                    "• AI智能回答：${if (ps["aiEnabled"] == true) "✅开" else "⛔关"}\n" +
                    "• 消息记录：${if (ps["msgRecord"] == true) "✅开" else "⛔关"}\n" +
                    "• 主动问候：${if (ps["proactiveEnabled"] == true) "✅开" else "⛔关"}\n" +
                    "• 群聊自动参与：${if (ps["groupJoin"] == true) "✅开" else "⛔关"}${if (channel == "qq") "（参与概率 ${ps["groupJoinProb"] ?: 8}%）" else ""}\n" +
                    // ★ v1.216 唤醒/回话设置展示
                    "• @必回：${if ((ps["atReply"] as? Boolean) ?: true) "✅开" else "⛔关"}\n" +
                    "• 唤醒前缀：${(ps["wakePrefix"] as? String)?.takeIf { it.isNotBlank() } ?: "(未设置，仅@触发)"}\n" +
                    "• 私聊自动回复：${if ((ps["c2cAuto"] as? Boolean) ?: true) "✅开" else "⛔关"}\n" +
                    "• 忽略自己消息：${if ((ps["ignoreSelf"] as? Boolean) == true) "✅开" else "⛔关"}\n" +
                    "• 群聊回复@本人：${if ((ps["atAsker"] as? Boolean) ?: true) "✅开（回复自动@提问者）" else "⛔关（不@）"}\n" +
                    "• 引用回复：${if ((ps["quoteReply"] as? Boolean) ?: true) "✅开" else "⛔关"}\n" +
                    "• 默认欢迎语：${(ps["welcome"] as? String)?.takeIf { it.isNotBlank() } ?: "(未设置)"}\n" +
                    "• 模型：${(ps["aiModel"] as? String)?.takeIf { it.isNotBlank() } ?: "(用各机器人默认)"}\n" +
                    "• 人设：${(ps["systemPrompt"] as? String)?.takeIf { it.isNotBlank() }?.take(60) ?: "(未设置)"}"
            }
        }
        "platform_settings_set" -> {
            if (!isAdmin) return "⛔ 仅管理员可修改平台设置"
            val channel = (args["channel"] ?: "").trim().lowercase()
            if (channel !in listOf("qq", "weixin", "mp")) "⚠️ 语法：platform_settings_set(channel=qq/weixin/mp, aiEnabled=on/off, msgRecord=on/off, proactiveEnabled=on/off, groupJoin=on/off, groupJoinProb=0-100, atReply=on/off, wakePrefix=前缀, c2cAuto=on/off, ignoreSelf=on/off, quoteReply=on/off, blacklist=黑名单, whitelist=白名单, joinCooldownSec=秒, replyFreqLimit=条/分, welcome=欢迎语, aiModel=模型, systemPrompt=人设)"
            else {
                val upd = mutableMapOf<String, Any?>()
                (args["aiEnabled"] ?: "").let { if (it.isNotBlank()) upd["aiEnabled"] = it in listOf("on", "true", "1", "开", "启用") }
                (args["msgRecord"] ?: "").let { if (it.isNotBlank()) upd["msgRecord"] = it in listOf("on", "true", "1", "开", "启用") }
                (args["proactiveEnabled"] ?: "").let { if (it.isNotBlank()) upd["proactiveEnabled"] = it in listOf("on", "true", "1", "开", "启用") }
                (args["groupJoin"] ?: "").let { if (it.isNotBlank()) upd["groupJoin"] = it in listOf("on", "true", "1", "开", "启用") }
                (args["groupJoinProb"] ?: "").toIntOrNull()?.let { upd["groupJoinProb"] = it.coerceIn(0, 100) }
                // ★ v1.216 唤醒/回话字段
                (args["atReply"] ?: "").let { if (it.isNotBlank()) upd["atReply"] = it in listOf("on", "true", "1", "开", "启用") }
                (args["wakePrefix"] ?: "").let { if (it.isNotBlank()) upd["wakePrefix"] = it }
                (args["c2cAuto"] ?: "").let { if (it.isNotBlank()) upd["c2cAuto"] = it in listOf("on", "true", "1", "开", "启用") }
                (args["ignoreSelf"] ?: "").let { if (it.isNotBlank()) upd["ignoreSelf"] = it in listOf("on", "true", "1", "开", "启用") }
                (args["quoteReply"] ?: "").let { if (it.isNotBlank()) upd["quoteReply"] = it in listOf("on", "true", "1", "开", "启用") }
                (args["blacklist"] ?: "").let { if (it.isNotBlank()) upd["blacklist"] = it }
                (args["whitelist"] ?: "").let { if (it.isNotBlank()) upd["whitelist"] = it }
                (args["joinCooldownSec"] ?: "").toIntOrNull()?.let { upd["joinCooldownSec"] = it.coerceIn(0, 600) }
                (args["replyFreqLimit"] ?: "").toIntOrNull()?.let { upd["replyFreqLimit"] = it.coerceIn(0, 600) }
                (args["atAsker"] ?: "").let { if (it.isNotBlank()) upd["atAsker"] = it in listOf("on", "true", "1", "开", "启用") }
                (args["welcome"] ?: "").let { if (it.isNotBlank()) upd["welcome"] = it }
                (args["aiModel"] ?: "").let { if (it.isNotBlank()) upd["aiModel"] = it }
                (args["systemPrompt"] ?: "").let { if (it.isNotBlank()) upd["systemPrompt"] = it }
                if (upd.isEmpty()) "⚠️ 没有要修改的项，参考语法传参"
                else {
                    db.setPlatformSettings(channel, upd)
                    "✅ ${channel.uppercase()} 平台设置已更新：" + upd.keys.joinToString("、")
                }
            }
        }
        "platform_messages" -> {
            if (!isAdmin) return "⛔ 仅管理员可查看消息记录"
            val channel = (args["channel"] ?: "").trim().lowercase()
            val limit = (args["limit"] ?: "20").toIntOrNull()?.coerceIn(1, 100) ?: 20
            val rows = db.getPlatformMessages(channel, "", limit)
            if (rows.isEmpty()) "📭 暂无消息记录${if (channel.isNotBlank()) "（$channel）" else ""}"
            else {
                val chName = mapOf("qq" to "QQ", "weixin" to "微信", "mp" to "公众号")
                "💬 【消息记录${if (channel.isNotBlank()) "·${chName[channel] ?: channel}" else ""}·最近${limit}条】\n" + rows.map { m ->
                    val ts = m["createdAt"] as? Long ?: 0L
                    val tstr = if (ts > 0) { val sdf = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA); sdf.format(java.util.Date(ts)) } else ""
                    val dir = if (m["direction"] == "out") "⬆️" else "⬇️"
                    "[$tstr ${chName[m["channel"]] ?: m["channel"]}} $dir] ${(m["content"] as? String ?: "").take(80)}"
                }.joinToString("\n").take(1800)
            }
        }
        else -> null
    }
}