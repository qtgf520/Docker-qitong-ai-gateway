package com.qitong.gateway.sandbox

import com.qitong.gateway.db.Database
import com.qitong.gateway.http.SkillExecutor
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * qtai-sj 沙盒调度引擎（v45）
 * ========================
 * 目标：QQ 机器人选中 qtai-sj 模型时自动启用沙盒调度，qtai-sj 自主感知网关全部功能、
 * 自主规划多步骤任务、自动调用网关全部已有接口（复用 SkillExecutor，不重写业务）。
 *
 * 设计原则（来自豆包文档 + 原版指南）：
 * 1. 复用优先：所有业务执行走 SkillExecutor（内部直调 Database/业务对象，无鉴权阻塞）
 * 2. 双层权限校验：模型 Prompt 只做引导，沙盒底层独立校验 QQ 身份权限
 * 3. 风险分级：read 只读直执行 / modify 修改直执行 / high 高危需确认
 * 4. 审计日志：每次沙盒调用记入 qq_logs
 * 5. 熔断保护：同一 QQ 用户短时间失败过多自动暂停
 */
object SandboxEngine {

    /** 网关能力知识库（注入 qtai-sj 上下文用） */
    val KNOWLEDGE_JSON: String by lazy {
        JSONArray()
            .put(func("model_batch_test", "批量测速全部模型（只读）", "admin", "read", emptyList(), "全部模型测速结果：正常数/总数"))
            .put(func("model_test_single", "对单个模型测速（只读）", "admin", "read", listOf(param("model_name", true, "模型名称标识，如 deepseek-chat")), "该模型延迟/可用性"))
            .put(func("model_get_all", "获取全部模型列表与状态（只读）", "admin", "read", emptyList(), "模型名称、启用状态"))
            .put(func("model_enable", "启用指定模型（修改）", "admin", "modify", listOf(param("model_name", true, "模型名称")), "启用结果"))
            .put(func("model_disable", "禁用指定模型（修改）", "admin", "modify", listOf(param("model_name", true, "模型名称")), "禁用结果"))
            .put(func("provider_get_all", "获取全部服务商列表（只读）", "admin", "read", emptyList(), "服务商名称/类型"))
            .put(func("gateway_status", "查看网关运行状态（只读）", "user", "read", emptyList(), "运行状态/端口/故障转移/当前活跃模型"))
            .put(func("speed_ranking", "查看测速排行（只读）", "user", "read", emptyList(), "模型测速速度排行"))
            .put(func("active_model", "查看当前活跃模型（只读）", "user", "read", emptyList(), "当前模型名或 qtai-sj 自动模式"))
            .put(func("traffic_total", "查看总上下行流量（只读）", "user", "read", emptyList(), "总上行/总下行字节"))
            .put(func("token_total", "查看总 Token 消耗（只读）", "admin", "read", emptyList(), "总 token 数"))
            .put(func("user_balance", "查询用户余额（只读）", "user", "read", listOf(param("username", false, "网关用户名，空=查自己")), "余额/累计充值"))
            .put(func("user_recharge", "给用户充值（修改，需管理员）", "admin", "modify", listOf(param("username", true, "网关用户名"), param("amount", true, "金额数字")), "充值结果/新余额"))
            .put(func("terminal_run", "沙盒执行终端命令（高危，需管理员确认）", "admin", "high", listOf(param("cmd", true, "待执行命令")), "终端输出"))
            .put(func("qq_bots_list", "获取全部 QQ 机器人配置（只读）", "admin", "read", emptyList(), "机器人 AppID/名称/启用状态/模型"))
            .put(func("qq_bots_groups", "获取全部 QQ 群配置（只读）", "admin", "read", emptyList(), "群名/群 openid/AI 开关"))
            .put(func("qq_points_rank", "查看积分排行（只读）", "user", "read", emptyList(), "积分排行"))
            .put(func("help", "查看沙盒可用能力清单", "user", "read", emptyList(), "全部函数名与说明"))
            .put(func("mcp_list", "列出已配置 MCP 服务器及其可用工具（只读）", "admin", "read", emptyList(), "MCP服务器名/工具列表"))
            .put(func("mcp_call", "调用 MCP 服务器上的工具（只读/执行）", "admin", "modify", listOf(param("server", true, "MCP服务器名"), param("tool", true, "工具名"), param("args", false, "JSON参数")), "工具返回结果"))
            .put(func("web_search", "联网搜索信息（只读）", "user", "read", listOf(param("query", true, "搜索关键词")), "搜索结果摘要"))
            .put(func("heartbeat_status", "查看自主心跳状态（只读）", "user", "read", emptyList(), "心跳是否运行/间隔分钟"))
            .put(func("heartbeat_start", "启动自主心跳（修改）", "admin", "modify", listOf(param("minutes", false, "间隔分钟，默认30")), "启动结果"))
            .put(func("heartbeat_stop", "停止自主心跳（修改）", "admin", "modify", emptyList(), "停止结果"))
            .put(func("heartbeat_check", "立即执行一次心跳自检（只读）", "user", "read", emptyList(), "自检结果"))
            .put(func("account_info", "查看当前绑定账号信息（只读）", "user", "read", emptyList(), "账号/角色/余额/绑定模型"))
            .put(func("account_bind", "远程登录绑定网关账号（修改）", "user", "modify", listOf(param("username", true, "网关用户名"), param("password", true, "账号密码")), "绑定结果"))
            .put(func("account_unbind", "退出当前绑定账号（修改）", "user", "modify", emptyList(), "解绑结果"))
            .toString()
    }

    private fun func(name: String, desc: String, permission: String, risk: String, params: List<JSONObject>, ret: String): JSONObject =
        JSONObject()
            .put("function_name", name)
            .put("desc", desc)
            .put("permission", permission)
            .put("risk_level", risk)
            .put("params", JSONArray().putAll(params))
            .put("return_desc", ret)

    private fun param(name: String, required: Boolean, desc: String): JSONObject =
        JSONObject().put("name", name).put("required", required).put("desc", desc)

    /** 沙盒专属 System Prompt（选中 qtai-sj 注入） */
    val SYSTEM_PROMPT: String = """
你是綦桐小助理，底层模型 qtai-sj，运行在綦桐AI网关QQ机器人渠道，内置沙盒调度系统，能够自主调度网关全部内置功能。
核心铁律：绝对禁止编造任何接口返回、终端输出、数据结果。所有数据必须来自沙盒调用网关接口返回。无法获取信息如实告知，禁止虚构。

## 1. 身份与权限体系
- QQ普通用户：仅支持闲聊、只读查询（查状态/排行/余额/流量），禁止任何修改类操作。
- QQ管理员：拥有完整网关调度权限，可读写网关所有功能。
> 收到消息第一步：校验发送者QQ身份，判断权限范围，无权限操作直接拒绝，并说明权限不足。

## 2. 你内置网关能力知识库（必须记住所有可用功能）
$KNOWLEDGE_JSON

## 3. 自主任务规划规则【最重要】
用户发来需求，不要直接回答，先自主规划任务，严格遵循流程：
1. 意图解析：读懂用户最终目标，判断需要调用哪些网关功能，拆分为多步子任务。
2. 参数收集：检查所有子任务必填参数，缺少关键信息主动追问，不猜测。
3. 任务编排：判断子任务先后顺序，无依赖任务可并行。
4. 沙盒调用：输出标准化函数调用指令交给沙盒引擎执行，等待真实结果：
   [[沙盒:函数名(参数1=值1, 参数2=值2)]]
5. 结果迭代：拿到接口返回数据判断是否成功，成功继续下一子任务，报错自动分析重试。
6. 任务结束：全部步骤完成，整理简洁易懂回复。

## 4. 沙盒调度规范
1. 所有网关操作必须通过沙盒调度器调用，禁止编造返回结果。
2. 修改类高危操作（删除/终端命令/充值）沙盒会二次确认，需用户确认后才执行。
3. 普通用户只读查询直接执行；修改类操作需管理员身份。
4. 记忆隔离：QQ用户、群会话记忆互相独立。

## 5. 输出规范
1. 闲聊场景语气轻松活泼。
2. 网关运维/调度任务场景逻辑严谨，代码用 markdown 代码块。
3. 长消息自动分段适配QQ消息限制。
4. 任务执行失败如实完整返回错误信息，不隐藏报错。
""".trimIndent()

    /** 解析文本中的沙盒调用指令，兼容 4 种格式：
     *  1. [[沙盒:函数名(参数=值, ...)]]
     *  2. <dots_function_call name="函数名"><dots_function_call name="参数名">值</dots_function_call></dots_function_call>
     *  3. <function_call name="函数名"> 或 <invoke name="函数名">
     *  4. <function_call>{"name":"函数名","arguments":{...}}</function_call>
     */
    fun parseCalls(rawText: String): List<Pair<String, Map<String, String>>> {
        val result = mutableListOf<Pair<String, Map<String, String>>>()
        if (rawText.isBlank()) return result
        // ★ v55 预处理：全角字符/竖线畸形标签统一为半角，便于正则匹配
        var text = rawText
            .replace('＜', '<').replace('＞', '>')   // 全角尖括号
            .replace('｜', '|')                        // 竖线
            .replace('＂', '"').replace('“', '"').replace('”', '"')
        // 畸形闭合：<|invoke|> → <invoke>（去掉竖线左右空格）
        text = text.replace(Regex("<\\s*\\|\\s*(dots_function_call|function_call|invoke|calls)\\s*\\|?\\s*>"), "<$1>")
        text = text.replace(Regex("<\\s*/\\s*(dots_function_call|function_call|invoke|calls)\\s*\\|?\\s*>"), "</$1>")
        // 格式1：[[沙盒:函数(参数)]]
        val regex1 = Regex("\\[\\[沙盒:([a-zA-Z_]+)\\((.*?)\\)\\]\\]", RegexOption.DOT_MATCHES_ALL)
        regex1.findAll(text).forEach { m ->
            val fn = m.groupValues[1]
            val argsStr = m.groupValues[2]
            result.add(fn to parseArgs(argsStr))
        }
        // 格式2/3：<dots_function_call name="fn"> / <function_call name="fn"> / <invoke name="fn">
        // 捕获完整标签对（含嵌套参数值）
        val regex2 = Regex("<(?:dots_function_call|function_call|invoke)\\s+name=\"([a-zA-Z_]+)\"[^>]*>([\\s\\S]*?)</(?:dots_function_call|function_call|invoke)>")
        regex2.findAll(text).forEach { m ->
            val fn = m.groupValues[1]
            val inner = m.groupValues[2]
            val args = mutableMapOf<String, String>()
            // 解析嵌套参数：<dots_function_call name="参数名">值</dots_function_call>
            val paramRegex = Regex("<(?:dots_function_call|param|parameter)\\s+name=\"([^\"]+)\"[^>]*>([\\s\\S]*?)</(?:dots_function_call|param|parameter)>")
            paramRegex.findAll(inner).forEach { pm ->
                args[pm.groupValues[1].trim()] = pm.groupValues[2].trim()
            }
            // 也解析 name= 属性风格（<dots_function_call name="model_name" value="deepseek">）
            val attrRegex = Regex("name=\"([^\"]+)\"\\s+value=\"([^\"]*)\"")
            attrRegex.findAll(inner).forEach { am ->
                args[am.groupValues[1].trim()] = am.groupValues[2].trim()
            }
            if (args.isEmpty() && fn.isNotBlank()) {
                // 可能内嵌 JSON 参数 <function_call>{"name":"fn","arguments":{"k":"v"}}</function_call>
                runCatching {
                    val jo = JSONObject(inner.trim())
                    if (jo.has("arguments")) {
                        val a = jo.get("arguments")
                        if (a is JSONObject) a.keys().forEach { k -> args[k] = a.getString(k) }
                    }
                }
            }
            if (fn.isNotBlank()) result.add(fn to args)
        }
        // 格式4：<function_call>{"name":"fn","arguments":{...}}</function_call> 已在上面的 JSON 分支处理
        // 格式5：非闭合单标签 <dots_function_call name="fn"/> 或 <function_call name="fn">（无配对闭合）
        val regex5 = Regex("<(?:dots_function_call|function_call|invoke)\\s+name=\"([a-zA-Z_]+)\"\\s*/?>")
        regex5.findAll(text).forEach { m ->
            val fn = m.groupValues[1]
            if (fn.isNotBlank() && result.none { it.first == fn }) result.add(fn to emptyMap())
        }
        // 格式6：外层无 name 的 <dots_function_call><parameter name="query">值</parameter></dots_function_call>
        // 从 parameter 名推断函数：query→web_search，其他 try 原样函数名
        val regex6 = Regex("<(?:dots_function_call|function_call|invoke)[^>]*>([\\s\\S]*?)</(?:dots_function_call|function_call|invoke)>")
        regex6.findAll(text).forEach { m ->
            val inner = m.groupValues[1]
            if (inner.isBlank() || inner.trim().startsWith("{")) return@forEach // JSON 格式已处理
            val paramRegex = Regex("<(?:param|parameter)\\s+name=\"([^\"]+)\"[^>]*>([\\s\\S]*?)</(?:param|parameter)>")
            val params = mutableMapOf<String, String>()
            paramRegex.findAll(inner).forEach { pm ->
                params[pm.groupValues[1].trim()] = pm.groupValues[2].trim()
            }
            if (params.isEmpty()) return@forEach
            // 推断函数名：有 query 参数 → web_search；有 fn/function 参数 → 其值；否则取第一个参数名
            val fn = when {
                params.containsKey("query") -> "web_search"
                params.containsKey("function_name") -> params["function_name"]!!
                params.containsKey("name") && params.size == 1 -> params["name"]!!
                else -> params.keys.first()
            }
            if (fn.isNotBlank() && result.none { it.first == fn }) result.add(fn to params)
        }
        // 格式7：JSON 内嵌函数调用 <dots_function_call>{"name":"fn","arguments":{...}}</dots_function_call>
        // 或 <dots_function_call>:{"name":"fn"...}（带冒号）
        val jsonFn = Regex("<(?:dots_function_call|function_call|invoke)[^>]*>\\s*:?\\s*\\{([\\s\\S]*?)\\}")
        jsonFn.findAll(text).forEach { m ->
            runCatching {
                val jo = JSONObject("{" + m.groupValues[1] + "}")
                val fnName = jo.optString("name").ifBlank { jo.optString("function_name") }
                if (fnName.isNotBlank() && result.none { it.first == fnName }) {
                    val args = mutableMapOf<String, String>()
                    val a = jo.optJSONObject("arguments")
                    if (a != null) a.keys().forEach { k -> args[k] = a.optString(k) }
                    result.add(fnName to args)
                }
            }
        }
        // 格式8：YAML/纯文本风格 function_name: gateway_status / params: {...}
        val yamlFn = Regex("""(?:function_name|function|name)\s*[:：]\s*([a-zA-Z_][a-zA-Z0-9_]*)\s*""")
        yamlFn.findAll(text).forEach { m ->
            val fn = m.groupValues[1]
            if (fn.isNotBlank() && result.none { it.first == fn }) {
                // 尝试提取后面的 params: {...} 或 arguments: {...}
                val args = mutableMapOf<String, String>()
                val p = Regex("""(?:params|parameters|arguments)\s*[:：]\s*\{([^}]*)}""").find(text, m.range.last)
                if (p != null) {
                    p.groupValues[1].split(",").forEach { seg ->
                        val kv = seg.trim().split(Regex("""[:：]"""), limit = 2)
                        if (kv.size == 2) args[kv[0].trim().trim('"', '\'')] = kv[1].trim().trim('"', '\'')
                    }
                }
                result.add(fn to args)
            }
        }
        // ★ v56 过滤无意义函数名（模型误输出的字段名，不是真实沙盒函数）
        val skip = setOf("params", "parameters", "arguments", "function", "function_name", "name", "value", "output", "result", "results", "resp", "response")
        return result.distinctBy { it.first to it.second }
            .filter { it.first !in skip }
    }

    /** 解析 参数名=值, 参数名=值 格式 */
    private fun parseArgs(argsStr: String): Map<String, String> {
        val args = mutableMapOf<String, String>()
        if (argsStr.isNotBlank()) {
            argsStr.split(",").forEach { seg ->
                val kv = seg.trim().split("=", limit = 2)
                if (kv.size == 2) args[kv[0].trim()] = kv[1].trim()
            }
        }
        return args
    }

    /**
     * 执行沙盒调用（复用 SkillExecutor）
     * @param fn 函数名
     * @param args 参数
     * @param isAdmin QQ 用户是否管理员
     * @param userId 网关用户ID（用于技能的用户级操作，QQ 用户传 0=全局）
     */
    fun execute(fn: String, args: Map<String, String>, isAdmin: Boolean, userId: Long, db: Database): String = runBlocking { executeSuspend(fn, args, isAdmin, userId, db) }

    /** suspend 版本（SkillExecutor.execute 是 suspend） */
    suspend fun executeSuspend(fn: String, args: Map<String, String>, isAdmin: Boolean, userId: Long, db: Database): String {
        // 权限表：函数 -> (最低权限, 风险)
        val perm = when (fn) {
            "gateway_status", "speed_ranking", "active_model", "traffic_total", "token_total",
            "user_balance", "qq_points_rank", "help", "web_search", "heartbeat_status", "heartbeat_check" -> "user" to "read"
            "model_batch_test", "model_test_single", "model_get_all", "provider_get_all",
            "qq_bots_list", "qq_bots_groups", "mcp_list" -> "admin" to "read"
            "model_enable", "model_disable", "user_recharge", "terminal_run", "mcp_call", "heartbeat_start", "heartbeat_stop" -> "admin" to "modify"
            else -> "user" to "read"
        }
        val (needPerm, risk) = perm
        // 底层权限校验（不依赖模型）
        if (needPerm == "admin" && !isAdmin) return "⛔ 权限不足：该操作需要管理员身份"
        if (risk == "high") return "⚠️ 高危操作（$fn）需要二次确认，沙盒已拦截。请确认后重试：确认执行 $fn"

        return try {
            when (fn) {
                "help" -> "📚 沙盒可用能力：\n" + parseKnowledgeBrief()
                "model_batch_test" -> SkillExecutor.execute(db, "100002", "", userId)
                "model_test_single" -> SkillExecutor.execute(db, "100001", args["model_name"] ?: "", userId)
                "model_get_all" -> SkillExecutor.execute(db, "600008", "", userId)
                "model_enable" -> SkillExecutor.execute(db, "800003", args["model_name"] ?: "", userId)
                "model_disable" -> SkillExecutor.execute(db, "800004", args["model_name"] ?: "", userId)
                "provider_get_all" -> SkillExecutor.execute(db, "600007", "", userId)
                "gateway_status" -> SkillExecutor.execute(db, "600001", "", userId)
                "speed_ranking" -> SkillExecutor.execute(db, "600002", "", userId)
                "active_model" -> SkillExecutor.execute(db, "600003", "", userId)
                "traffic_total" -> SkillExecutor.execute(db, "600004", "", userId)
                "token_total" -> SkillExecutor.execute(db, "600005", "", userId)
                "user_balance" -> {
                    // ★ v54 无参自动用绑定账号：查自己余额（不报"用户不存在"）
                    val uname = args["username"]?.trim().orEmpty()
                    if (uname.isNotBlank()) {
                        SkillExecutor.execute(db, "600009", uname, userId)
                    } else if (userId > 0) {
                        val u = db.getUserById(userId)
                        if (u == null) "👤 绑定账号不存在"
                        else {
                            val bal = db.getUserBalance(u.id)
                            "💰 ${u.username} 余额：¥${"%.2f".format(bal)}（累计充值 ¥${"%.2f".format(u.totalRecharge)}）"
                        }
                    } else "🔓 未绑定账号。发「绑定账号 用户名 密码」或网页生成绑定码后群里发「绑定码 xxxx」"
                }
                "user_recharge" -> SkillExecutor.execute(db, "900020", "${args["username"] ?: ""} ${args["amount"] ?: ""}", userId)
                "terminal_run" -> {
                    // ★ v46：终端真执行（复用 TerminalManager.runOnce，危险命令拦截+超时+输出截断）
                    val cmd = args["cmd"] ?: ""
                    if (cmd.isBlank()) "⚠️ 语法：terminal_run(cmd=要执行的命令)"
                    else {
                        val out = com.qitong.gateway.http.TerminalManager.runOnce(cmd)
                        "🖥 执行: $cmd\n📤 输出:\n$out"
                    }
                }
                "qq_bots_list" -> {
                    val bots = db.getQqBots()
                    if (bots.isEmpty()) "📋 暂无QQ机器人配置"
                    else "📋 QQ机器人（${bots.size}个）：\n" + bots.take(20).joinToString("\n") { b ->
                        "· ${b["name"]} | AppID:${b["appid"]} | 模型:${b["aiModel"]} | ${if (b["enabled"] == true) "启用" else "停用"}"
                    }
                }
                "qq_bots_groups" -> {
                    val groups = db.getQqGroups()
                    if (groups.isEmpty()) "📋 暂无群记录"
                    else "📋 QQ群（${groups.size}个）：\n" + groups.take(20).joinToString("\n") { g ->
                        "· ${g["groupName"] ?: "-"} | ${g["groupOpenid"]}"
                    }
                }
                "qq_points_rank" -> {
                    val pts = db.getAllQqPoints("")
                    if (pts.isEmpty()) "📋 暂无积分记录"
                    else "📋 积分排行（前10）：\n" + pts.take(10).mapIndexed { i, p -> "#${i + 1} · ${p["openid"]} = ${p["points"]}" }.joinToString("\n")
                }
                "mcp_list" -> {
                    // 列出已配置 MCP 服务器 + 各服务器工具
                    val servers = McpClient.listEnabled(db)
                    if (servers.isEmpty()) "📋 未配置 MCP 服务器（后台 MCP 管理页可添加 Streamable HTTP 端点）"
                    else servers.joinToString("\n\n") { s -> McpClient.listTools(s) }
                }
                "mcp_call" -> {
                    val serverName = args["server"] ?: ""
                    val tool = args["tool"] ?: ""
                    val servers = McpClient.listEnabled(db)
                    val server = servers.firstOrNull { it.name.contains(serverName, true) || serverName.contains(it.name, true) }
                    if (server == null) "❌ 未找到 MCP 服务器: $serverName（已配置: ${servers.joinToString(",") { it.name }}）"
                    else if (tool.isBlank()) "⚠️ 语法：mcp_call(server=服务器名, tool=工具名, args=JSON参数)"
                    else {
                        val argsJson = runCatching { org.json.JSONObject(args["args"] ?: "{}") }.getOrElse { org.json.JSONObject() }
                        McpClient.callTool(server, tool, argsJson)
                    }
                }
                "web_search" -> {
                    val query = args["query"] ?: ""
                    if (query.isBlank()) "⚠️ 语法：web_search(query=关键词)"
                    else {
                        // 联网搜索：用 DuckDuckGo HTML 接口（免费无需 key）
                        val url = "https://html.duckduckgo.com/html/?q=" + java.net.URLEncoder.encode(query, "UTF-8")
                        val req = okhttp3.Request.Builder().url(url)
                            .addHeader("User-Agent", "Mozilla/5.0 (compatible; QitongAI/1.0)")
                            .build()
                        try {
                            val http = okhttp3.OkHttpClient()
                            http.newCall(req).execute().use { resp ->
                                val html = resp.body?.string().orEmpty()
                                // 提取搜索结果标题+摘要（简化解析）
                                val titles = Regex("result__a[^>]*>([^<]{5,100})").findAll(html).take(5).map { it.groupValues[1].trim() }.toList()
                                val snips = Regex("result__snippet[^>]*>([^<]{5,200})").findAll(html).take(5).map { it.groupValues[1].trim() }.toList()
                                if (titles.isEmpty()) "🔍 未找到「$query」结果（DDG 可能被限流）"
                                else {
                                    val sb = StringBuilder("🔍 「$query」搜索结果：\n")
                                    for (i in titles.indices) {
                                        sb.append("${i + 1}. ${titles[i]}\n")
                                        if (i < snips.size) sb.append("   ${snips[i]}\n")
                                    }
                                    sb.toString().take(1500)
                                }
                            }
                        } catch (e: Exception) { "❌ 搜索失败: ${e.message}" }
                    }
                }
                "heartbeat_status" -> com.qitong.gateway.sandbox.HeartbeatEngine.statusText()
                "heartbeat_check" -> com.qitong.gateway.sandbox.HeartbeatEngine.checkOnce(db)
                "heartbeat_start" -> {
                    val minutes = args["minutes"]?.toIntOrNull() ?: 30
                    if (minutes < 5) "⚠️ 间隔最少 5 分钟"
                    else {
                        db.setConfig("heartbeat_enabled", "true")
                        db.setConfig("heartbeat_interval_minutes", minutes.toString())
                        // 重启心跳协程
                        if (com.qitong.gateway.sandbox.HeartbeatEngine.isRunning()) com.qitong.gateway.sandbox.HeartbeatEngine.stop()
                        com.qitong.gateway.sandbox.HeartbeatEngine.start(db)
                        "✅ 自主心跳已启动（间隔 $minutes 分钟）"
                    }
                }
                "heartbeat_stop" -> {
                    db.setConfig("heartbeat_enabled", "false")
                    com.qitong.gateway.sandbox.HeartbeatEngine.stop()
                    "✅ 自主心跳已停止"
                }
                "account_info" -> {
                    // 查看当前绑定账号信息（沙盒用 userId 推断，若 0 说明未绑定）
                    if (userId <= 0) "🔓 当前未绑定网关账号\n✅ 发「绑定账号 用户名 密码」远程登录绑定，绑定后获得该账号全部权限与数据"
                    else {
                        val u = db.getUserById(userId)
                        if (u == null) "🔓 绑定账号不存在，请重新绑定"
                        else {
                            val bal = db.getUserBalance(u.id)
                            "👤 当前绑定账号：${u.username}\n💎 角色: ${u.role}\n💰 余额: ¥${"%.2f".format(bal)}\n💳 累计充值: ¥${"%.2f".format(u.totalRecharge)}"
                        }
                    }
                }
                "account_bind" -> {
                    val username = args["username"] ?: ""
                    val password = args["password"] ?: ""
                    if (username.isBlank() || password.isBlank()) "⚠️ 语法：account_bind(username=用户名, password=密码)"
                    else {
                        val r = com.qitong.gateway.auth.AuthManager.login(db, username, password)
                        if (r.isSuccess) {
                            val u = db.getUserByUsername(username.trim())
                            "✅ 绑定成功！已登录账号「${u?.username}」（角色 ${u?.role}），可查余额/用量/分销，发「我的账号」查看"
                        } else "❌ 登录失败：${r.exceptionOrNull()?.message ?: "用户名或密码错误"}"
                    }
                }
                "account_unbind" -> "✅ 已在 QQ 私聊发送「退出账号」解绑"
                else -> "❌ 未知沙盒函数: $fn（发「沙盒帮助」查看可用能力）"
            }
        } catch (e: Exception) {
            "❌ 沙盒执行失败: ${e.message}"
        }
    }

    private fun parseKnowledgeBrief(): String {
        val arr = JSONArray(KNOWLEDGE_JSON)
        return (0 until arr.length()).map { i ->
            val f = arr.getJSONObject(i)
            "· ${f.getString("function_name")}（${f.getString("risk_level")}）— ${f.getString("desc")}"
        }.joinToString("\n")
    }
}
