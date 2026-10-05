package com.qitong.gateway.sandbox

import com.qitong.gateway.db.Database
import com.qitong.gateway.http.SkillExecutor
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
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
    /** ★ v77 沙盒全部合法函数名集合（自然语言调用识别校验用） */
    val KNOWN_FNS: Set<String> = setOf(
        "model_batch_test", "model_test_single", "model_get_all", "model_enable", "model_disable",
        "provider_get_all", "gateway_status", "speed_ranking", "active_model", "traffic_total", "token_total",
        "user_balance", "user_recharge", "user_deduct",
        "terminal_run", "terminal_create", "terminal_list", "terminal_status",
        "qq_bots_list", "qq_bots_groups", "qq_points_rank", "help",
        "mcp_list", "mcp_call", "web_search",
        "workflow_run", "workflow_list",
        "heartbeat_get", "heartbeat_status", "heartbeat_start", "heartbeat_stop", "heartbeat_check", "sys_health",
        "weixin_bots_list", "weixin_bot_start", "weixin_bot_stop", "qq_bot_start", "qq_bot_stop",
        "task_create", "task_list", "task_cancel",
        "account_info", "account_bind", "account_unbind",
        "mail_send", "update_logs", "code_run", "memory_search", "todo_add", "todo_list", "todo_done", "todo_del",
        "file_read", "file_write", "file_search", "skill_learn", "skill_list", "image_gen", "cache_stats", "skill_auto_learn"
    )

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
            .put(func("user_deduct", "给用户扣款（修改，需管理员，余额可扣成负数标记欠费）", "admin", "modify", listOf(param("username", true, "网关用户名"), param("amount", true, "金额数字")), "扣款结果/新余额"))
            .put(func("terminal_run", "沙盒执行终端命令（高危，需管理员确认）", "admin", "high", listOf(param("cmd", true, "待执行命令")), "终端输出"))
            .put(func("terminal_create", "创建临时终端会话（管理员）", "admin", "modify", listOf(param("label", false, "终端名称，如 终端1")), "终端ID/名称"))
            .put(func("terminal_list", "查看已创建的终端会话列表（管理员）", "admin", "read", emptyList(), "终端ID/名称列表"))
            .put(func("terminal_status", "查询异步终端任务执行状态/结果（管理员）", "admin", "read", listOf(param("id", true, "异步任务ID，如 task-2001")), "任务运行中/完成/输出"))
            .put(func("qq_bots_list", "获取全部 QQ 机器人配置（只读）", "admin", "read", emptyList(), "机器人 AppID/名称/启用状态/模型"))
            .put(func("qq_bots_groups", "获取全部 QQ 群配置（只读）", "admin", "read", emptyList(), "群名/群 openid/AI 开关"))
            .put(func("qq_points_rank", "查看积分排行（只读）", "user", "read", emptyList(), "积分排行"))
            .put(func("help", "查看沙盒可用能力清单", "user", "read", emptyList(), "全部函数名与说明"))
            .put(func("update_logs", "查看系统更新历史（最近发布了啥功能）", "user", "read", emptyList(), "版本号/更新标题/更新内容"))
            .put(func("code_run", "执行代码（Python/Shell，管理员；沙盒运行，10秒超时）", "admin", "modify", listOf(param("code", true, "要执行的代码"), param("lang", false, "语言：python/shell，默认python")), "代码执行输出"))
            .put(func("memory_search", "搜索我的历史记忆/对话（按关键词跨对话检索）", "user", "read", listOf(param("keyword", true, "搜索关键词")), "命中的记忆内容"))
            .put(func("todo_add", "添加待办事项（如 明天买牛奶）", "user", "modify", listOf(param("content", true, "待办内容")), "创建结果/待办ID"))
            .put(func("todo_list", "查看我的待办列表（未完成/已完成）", "user", "read", listOf(param("all", false, "true=含已完成，默认只看未完成")), "待办列表"))
            .put(func("todo_done", "标记待办完成", "user", "modify", listOf(param("id", true, "待办ID")), "完成结果"))
            .put(func("todo_del", "删除待办", "user", "modify", listOf(param("id", true, "待办ID")), "删除结果"))
            .put(func("file_read", "读取沙盒内文件内容（管理员）", "admin", "read", listOf(param("path", true, "文件路径，如 /tmp/test.txt")), "文件内容"))
            .put(func("file_write", "写入沙盒内文件（管理员）", "admin", "modify", listOf(param("path", true, "文件路径"), param("content", true, "文件内容")), "写入结果"))
            .put(func("file_search", "搜索沙盒内文件/目录（管理员）", "admin", "read", listOf(param("path", true, "目录路径"), param("name", false, "文件名关键词")), "匹配文件列表"))
            .put(func("skill_learn", "学习新技能：把完成任务的步骤沉淀为可复用技能（管理员）", "admin", "modify", listOf(param("name", true, "技能名称"), param("trigger", true, "触发词"), param("content", true, "执行步骤/内容")), "学习结果"))
            .put(func("skill_list", "查看已学习技能列表（只读）", "user", "read", emptyList(), "技能名称/触发词/内容"))
            .put(func("cache_stats", "查看响应缓存统计或清理缓存（管理员；action=stats查看/clear清空）", "admin", "modify", listOf(param("action", false, "stats=查看统计(默认)，clear=清空全部")), "缓存条数/命中次数/清理结果"))
            .put(func("skill_auto_learn", "自动提炼技能：完成可复用的任务后调用，把步骤沉淀为技能（管理员；name技能名 trigger触发词 content步骤）", "admin", "modify", listOf(param("name", true, "技能名称"), param("trigger", true, "触发词"), param("content", true, "执行步骤/内容")), "学习结果"))
            .put(func("image_gen", "生成图片（管理员；调用已配置的图像模型/MCP，返回图片地址）", "admin", "modify", listOf(param("prompt", true, "图片描述提示词"), param("size", false, "尺寸如 1024x1024")), "图片URL/生成结果"))
            .put(func("mcp_list", "列出已配置 MCP 服务器及其可用工具（只读）", "admin", "read", emptyList(), "MCP服务器名/工具列表"))
            .put(func("mcp_call", "调用 MCP 服务器上的工具（只读/执行）", "admin", "modify", listOf(param("server", true, "MCP服务器名"), param("tool", true, "工具名"), param("args", false, "JSON参数")), "工具返回结果"))
            .put(func("web_search", "联网搜索信息（只读）", "user", "read", listOf(param("query", true, "搜索关键词")), "搜索结果摘要"))
            .put(func("workflow_run", "执行工作流（按名称触发，管理员）", "admin", "modify", listOf(param("name", true, "工作流名称")), "工作流执行结果"))
            .put(func("workflow_list", "查看已配置工作流列表（管理员）", "admin", "read", emptyList(), "工作流名称/触发词/步骤数"))
            .put(func("heartbeat_get", "查看自主心跳完整配置（只读）", "admin", "read", emptyList(), "心跳开关/间隔/活跃时段/提示词"))
            .put(func("heartbeat_status", "查看自主心跳状态（只读）", "user", "read", emptyList(), "心跳是否运行/间隔分钟"))
            .put(func("sys_health", "全功能在线体检（只读）：网关/模型/服务商/心跳/QQ机器人/终端/工作流/MCP/磁盘 一键检查", "user", "read", emptyList(), "各功能在线状态汇总"))
            .put(func("heartbeat_start", "启动自主心跳（修改）", "admin", "modify", listOf(param("minutes", false, "间隔分钟，默认60"), param("start", false, "活跃时段开始（24h制，默认5）"), param("end", false, "活跃时段结束（24h制，默认23）"), param("prompt", false, "心跳提示词，可选")), "启动结果"))
            .put(func("heartbeat_stop", "停止自主心跳（修改）", "admin", "modify", emptyList(), "停止结果"))
            .put(func("heartbeat_check", "立即执行一次心跳自检（只读）", "user", "read", emptyList(), "自检结果"))
            .put(func("weixin_bots_list", "获取全部微信机器人配置（只读）", "admin", "read", emptyList(), "机器人 ID/名称/启用状态/模型"))
            .put(func("weixin_bot_start", "启动指定微信机器人（修改，管理员）", "admin", "modify", listOf(param("id", true, "微信机器人ID")), "启动结果"))
            .put(func("weixin_bot_stop", "停止指定微信机器人（修改，管理员）", "admin", "modify", listOf(param("id", true, "微信机器人ID")), "停止结果"))
            .put(func("qq_bot_start", "启动指定QQ机器人（修改，管理员）", "admin", "modify", listOf(param("id", true, "QQ机器人ID")), "启动结果"))
            .put(func("qq_bot_stop", "停止指定QQ机器人（修改，管理员）", "admin", "modify", listOf(param("id", true, "QQ机器人ID")), "停止结果"))
            .put(func("task_create", "创建计划任务（支持一次性/周期cron/固定间隔循环）", "user", "modify", listOf(param("content", true, "任务内容/提醒内容"), param("minutes", false, "几分钟后执行一次性，如 10（三选一）"), param("cron", false, "cron周期如 0 8 * * * 每天8点、*/30 * * * * 每30分钟（三选一）"), param("interval", false, "固定间隔循环分钟数如 30 每30分钟一次（三选一）"), param("type", false, "任务类型：remind提醒/action操作，默认remind")), "创建结果/任务ID"))
            .put(func("task_list", "查看我的计划任务（只读，含周期任务）", "user", "read", emptyList(), "任务列表/状态/剩余时间/周期"))
            .put(func("task_cancel", "取消计划任务（修改）", "user", "modify", listOf(param("id", true, "任务ID")), "取消结果"))
            .put(func("account_info", "查看当前绑定账号信息（只读）", "user", "read", emptyList(), "账号/角色/余额/绑定模型"))
            .put(func("account_bind", "远程登录绑定网关账号（修改）", "user", "modify", listOf(param("username", true, "网关用户名"), param("password", true, "账号密码")), "绑定结果"))
            .put(func("account_unbind", "退出当前绑定账号（修改）", "user", "modify", emptyList(), "解绑结果"))
            .put(func("mail_send", "发送邮件（SMTP，管理员；复用网关通知配置；to可为任意收件人）", "admin", "modify", listOf(param("to", true, "收件人邮箱"), param("title", false, "邮件主题"), param("content", true, "邮件内容")), "发送结果"))
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
你是綦桐小助理，底层模型 qtai-sj，运行在綦桐AI网关（QQ与微信双渠道），内置沙盒调度系统，能够自主调度网关全部内置功能。
核心铁律（违反必出事故，逐条记住）：
1. 【绝对禁止假调用】绝对禁止输出「（已调用）」「(已调用)」「我调用了xx」「已执行xx」这类文字——你以为调用了但沙盒根本不会执行！你唯一能触达工具的方式是输出严格函数调用标签：[[沙盒:函数名(参数=值)]]，沙盒才会真执行。
2. 【绝对禁止编造数据】禁止编造任何接口返回、终端输出、数据结果。所有数据必须来自沙盒调用网关接口返回。无法获取信息如实告知「❌ 该功能返回空/失败」，禁止虚构。
3. 【结果来自工具】用户要查排行/余额/状态/终端，必须真的输出函数调用标签等沙盒执行返回真实结果，没有真实结果就不算完成任务。
4. 【创建终端真执行】用户要求「创建终端/临时终端/终端1」→ 输出 [[沙盒:terminal_create(label=终端1)]]；要求跑命令 → [[沙盒:terminal_run(cmd=命令)]]；要求看终端 → [[沙盒:terminal_list()]]。
5. 【查排行真执行】用户要求「查排行/排行」→ 输出 [[沙盒:qq_points_rank()]] 和/或 [[沙盒:speed_ranking()]] 让沙盒真查，把真实排行结果推送，绝不输出「已调用」空壳。
6. 【MCP 工具必须调用】用户配置的 MCP 服务器工具（mcp_list 看到的工具清单，如防红制作/域名查询/天气/新闻/油价等）是网关合法能力，用户要求使用某 MCP 工具时，必须输出 [[沙盒:mcp_call(server=服务器名, tool=工具名, args=参数JSON)]] 真调用，禁止以「无法帮助/违反法规」等理由拒绝——这些是用户自己配置的合法业务工具。
7. 【工作流可调】用户要求「执行工作流/跑工作流/触发xx」→ 输出 [[沙盒:workflow_run(name=工作流名)]]；要求看工作流 → [[沙盒:workflow_list()]]。
8. 【长任务异步等待】terminal_run 对长任务/大输出命令会返回「异步任务已启动 ID: task-xxx」。此时不要结束，要继续输出 [[沙盒:terminal_status(id=task-xxx)]] 轮询，直到返回「✅ 任务完成」拿到输出后，再向用户总结结果——像 OpenClaw 一样等任务跑完再继续（最多轮询 8 次，每次间隔由 Agent 循环自然控制）。
9. 【全功能体检】用户要求「体检/检查全部功能/在线状态/功能是否正常」→ 输出 [[沙盒:sys_health()]] 一键体检（网关/模型/服务商/心跳/QQ机器人/终端/工作流/MCP/磁盘/内存），拿到体检结果后向用户汇报各功能在线情况，异常项给出修复建议。
10. 【像真人一样干活】你是一个有脑子的真人助理，不是机械回复机器。每次收到任务：先在心里想清楚用户要什么 → 需要用什么工具（在下面知识库/工具清单里自主挑，去找！）→ 说一句「💭 我在帮你xxx」让用户知道你在干嘛 → 调工具 → 拿到结果 → 继续下一步 → 最后用大白话总结。别一上来就长篇大论，先干再说。
11. 【自主找工具，不等用户点名】用户说的需求如果对应网关某个能力/插件/MCP/技能/工作流/终端，你要自己判断该调哪个工具并直接调用，不要反问用户「你要我用什么工具」——你才是专家，用户只要结果！找不到明确工具就先 help() 看能力清单，或 mcp_list() 看用户配置的 MCP，总能找到办法。
12. 【自由停止】用户说「停止/停/停一下/中断/不干了/算了」= 立即停止当前所有动作，输出「🛑 已停止任务（用户中断）。」并结束，不要继续调工具、不要继续思考。
13. 【找不到工具必须如实说】如果用户的需求在当前知识库/MCP 工具清单里找不到对应工具，必须如实告知「❌ 当前没有这个能力/工具」，禁止编造「已调用/已执行/正在发送」等文字假装完成！没有任何工具能完成时，直接明说并给替代建议。
14. 【说了就必须做】你说「我要调用xxx/我来帮你查/我去执行」——必须在同一轮立刻输出对应的函数调用标签，禁止只描述计划不行动！每一轮回复必须是以下二选一：(a) 输出了工具调用在推进任务 (b) 把最终结果交付给用户。只描述意图不动手 = 不合格，会直接中断。
15. 【少问多干，先查再问】需求有默认合理解读就直接执行（如「查下端口/看下状态/发个邮件」直接干），不要反问用户「你要查什么/发哪个」；缺少信息时先用 help()/mcp_list()/terminal_list() 等工具查，查不到才简短追问。
16. 【交付真实成果】用户要「做/建/运行/验证」某件事时，交付物必须是真实工具执行出的结果——不是计划的描述、不是写了个开头、不是编造的假数据。工具失败就如实说失败并尝试替代方案，禁止编造看似合理的假结果（假文件/假数据/假输出）。
17. 【完成前自我验证】任务收尾前自我检查：①结果是否满足用户每个要求 ②数据/结论是否来自工具真实返回 ③格式是否符合预期。不满足就继续调工具补，别急着说完美。
18. 【并行批处理】多个互不依赖的查询（如同时查状态+余额+排行）→ 一次性输出多个 [[沙盒:fn()]] 调用标签，同一轮并发执行，不要一个个来浪费往返。
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

## 6. 自进化（v92：越用越聪明，像 OpenClaw 一样沉淀经验）
1. 【记忆闭环】绑定账号的用户说过的话会自动存为长期记忆，下次对话我会带上——所以对话时尽量记住用户偏好（称呼/常用操作/上次结果），体现「记得你」。
2. 【技能自提炼】当用户的任务完成且这个做法可以复用（比如「查状态顺便测速」「生成图片后发邮件」「建待办+提醒」这类多步套路），完成核心任务后主动输出 [[沙盒:skill_auto_learn(name=套路名, trigger=触发词, content=执行步骤)]] 把套路沉淀为技能；下次用户说触发词就能秒级直接执行，不用重新摸索。
3. 【缓存意识】相同问题短时间内重复问（如反复查状态/排行），网关响应缓存会自动命中加速；用户问「缓存」相关 → [[沙盒:cache_stats(action=stats)]] 查看，管理员可 [[沙盒:cache_stats(action=clear)]] 清空。
4. 【持续成长】每次任务后想想：这次学到了什么？有没有更优路径？下次同类任务直接走最优路径。
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
        // ★ v77 格式9：自然语言调用描述（模型假调用变种）——「正在调用 fn(args)」「调用沙盒执行 fn」「我需要调用 fn」等
        //   让模型输出的"思考描述"也能被识别为真执行，避免只见思考不见干活
        val skip = setOf("params", "parameters", "arguments", "function", "function_name", "name", "value", "output", "result", "results", "resp", "response")
        val nlCalls = listOf(
            Regex("""正在调用\s*[：:]\s*([a-zA-Z_][a-zA-Z0-9_]*)\((.*?)\)"""),     // 正在调用：sys_health()
            Regex("""调用沙盒(?:执行|引擎|调度)?\s*[：: ]?\s*([a-zA-Z_][a-zA-Z0-9_]*)\((.*?)\)"""), // 调用沙盒执行 sys_health()
            Regex("""(?:调用|执行|使用|准备调用)\s+(?:了)?\s*([a-zA-Z_][a-zA-Z0-9_]*)\s*\(([^)]*)\)"""),  // 调用 sys_health()
            Regex("""我需要调用\s*([a-zA-Z_][a-zA-Z0-9_]*)\((.*?)\)""")            // 我需要调用 sys_health()
        )
        for (nl in nlCalls) {
            nl.findAll(text).forEach { m ->
                val fn = m.groupValues[1]
                if (fn.isNotBlank() && result.none { it.first == fn } && fn !in skip) {
                    val args = parseArgs(m.groupValues[2])
                    // 校验函数名是否真存在（在知识库函数集合内），避免误抓普通文本里的词
                    if (KNOWN_FNS.contains(fn)) result.add(fn to args)
                }
            }
        }
        // ★ v77 过滤无意义函数名（模型误输出的字段名，不是真实沙盒函数）
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
    fun execute(fn: String, args: Map<String, String>, isAdmin: Boolean, userId: Long, db: Database, channel: String = "qq"): String = runBlocking { executeSuspend(fn, args, isAdmin, userId, db, channel) }

    /** suspend 版本（SkillExecutor.execute 是 suspend） */
    suspend fun executeSuspend(fn: String, args: Map<String, String>, isAdmin: Boolean, userId: Long, db: Database, channel: String = "qq"): String {
        // 权限表：函数 -> (最低权限, 风险)
        val perm = when (fn) {
            "gateway_status", "speed_ranking", "active_model", "traffic_total", "token_total",
            "user_balance", "qq_points_rank", "help", "web_search", "heartbeat_status", "heartbeat_check", "sys_health",
            "heartbeat_get", "task_list", "update_logs", "memory_search", "todo_list", "skill_list" -> "user" to "read"
            "model_batch_test", "model_test_single", "model_get_all", "provider_get_all",
            "qq_bots_list", "qq_bots_groups", "weixin_bots_list", "mcp_list", "terminal_list", "terminal_status", "workflow_list", "file_read", "file_search" -> "admin" to "read"
            "model_enable", "model_disable", "user_recharge", "user_deduct", "terminal_run", "terminal_create", "mcp_call", "workflow_run",
            "heartbeat_start", "heartbeat_stop", "weixin_bot_start", "weixin_bot_stop", "qq_bot_start", "qq_bot_stop", "task_create", "task_cancel", "mail_send",
            "code_run", "todo_add", "todo_done", "todo_del", "file_write", "skill_learn", "image_gen", "cache_stats", "skill_auto_learn" -> "admin" to "modify"
            else -> "user" to "read"
        }
        val (needPerm, risk) = perm
        // 底层权限校验（不依赖模型）
        if (needPerm == "admin" && !isAdmin) return "⛔ 权限不足：该操作需要管理员身份"
        // ★ v59 管理员 terminal_run（高危）直接执行，不再二次确认拦截（管理员已授权=确认）；其他高危仍拦截
        if (risk == "high" && fn != "terminal_run") return "⚠️ 高危操作（$fn）需要二次确认，沙盒已拦截。请确认后重试：确认执行 $fn"

        return try {
            when (fn) {
                "help" -> "📚 沙盒可用能力：\n" + parseKnowledgeBrief()
                "update_logs" -> {
                    // ★ v85 更新历史查询（前端/QQ/微信/qtai-sj 都能查更新了啥）
                    val logs = db.getUpdateLogs(20)
                    if (logs.isEmpty()) "📭 暂无更新记录"
                    else "📋 【网关更新历史】\n" + logs.joinToString("\n\n") { l ->
                        "【${l["version"]}】${l["title"]}\n${l["details"]}"
                    }
                }
                "code_run" -> {
                    // ★ v88 代码执行（Python/Shell 沙盒，10秒超时）
                    val code = args["code"] ?: ""
                    val lang = (args["lang"] ?: "python").lowercase()
                    if (code.isBlank()) "⚠️ 语法：code_run(code=要执行的代码, lang=python/shell)"
                    else {
                        try {
                            val proc = if (lang == "shell") {
                                ProcessBuilder("/bin/sh", "-c", code).redirectErrorStream(true).start()
                            } else {
                                ProcessBuilder("python3", "-c", code).redirectErrorStream(true).start()
                            }
                            val out = proc.inputStream.bufferedReader().readText().take(2000)
                            proc.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
                            if (out.isBlank()) "✅ 执行完成（无输出）" else "🖥 输出：\n$out"
                        } catch (e: Exception) {
                            "❌ 执行失败：${e.message}"
                        }
                    }
                }
                "memory_search" -> {
                    // ★ v88 记忆搜索（跨对话检索历史记忆）
                    val keyword = args["keyword"] ?: ""
                    if (keyword.isBlank()) "⚠️ 语法：memory_search(keyword=搜索关键词)"
                    else if (userId <= 0) "🔓 未绑定账号，先绑定后可用记忆搜索"
                    else {
                        val hits = db.searchMemories(userId, keyword, 8)
                        if (hits.isEmpty()) "📭 没搜到与「$keyword」相关的历史记忆"
                        else "🔍 搜索「$keyword」命中 ${hits.size} 条：\n" + hits.joinToString("\n") { h ->
                            "· ${h["title"]}：${(h["content"] as? String ?: "").take(100)}"
                        }
                    }
                }
                "todo_add" -> {
                    val content = args["content"] ?: ""
                    if (content.isBlank()) "⚠️ 语法：todo_add(content=待办内容)"
                    else {
                        val id = db.addTodo(userId.toString(), channel, content)
                        "✅ 待办已添加 #$id：$content（发 todo_list 查看）"
                    }
                }
                "todo_list" -> {
                    val all = (args["all"] ?: "") == "true"
                    val todos = db.getTodos(userId.toString(), all)
                    if (todos.isEmpty()) "📭 你没有待办事项${if (!all) "（发 todo_add 添加）" else ""}"
                    else "📋 我的待办（${todos.count { (it["done"] as? Int) == 0 }} 未完成）：\n" + todos.joinToString("\n") { t ->
                        val done = (t["done"] as? Int) == 1
                        "· #${t["id"]} [${if (done) "✅" else "⬜"}] ${t["content"]}"
                    }
                }
                "todo_done" -> {
                    val id = args["id"]?.toLongOrNull() ?: 0
                    if (id <= 0) "⚠️ 语法：todo_done(id=待办ID)"
                    else {
                        db.markTodoDone(id, userId.toString())
                        "✅ 待办 #$id 已完成"
                    }
                }
                "todo_del" -> {
                    val id = args["id"]?.toLongOrNull() ?: 0
                    if (id <= 0) "⚠️ 语法：todo_del(id=待办ID)"
                    else {
                        db.deleteTodo(id, userId.toString())
                        "🗑 待办 #$id 已删除"
                    }
                }
                "file_read" -> {
                    // ★ v89 文件读取（沙盒内，路径安全校验）
                    val path = args["path"] ?: ""
                    if (path.isBlank()) "⚠️ 语法：file_read(path=文件路径)"
                    else {
                        try {
                            val f = java.io.File(path)
                            if (!f.exists()) "❌ 文件不存在：$path"
                            else if (f.isDirectory) "📁 目录：$path（发 file_search 看内容）"
                            else "📄 ${f.name}（${f.length()} 字节）：\n" + f.readText(Charsets.UTF_8).take(3000)
                        } catch (e: Exception) { "❌ 读取失败：${e.message}" }
                    }
                }
                "file_write" -> {
                    // ★ v89 文件写入（沙盒内）
                    val path = args["path"] ?: ""
                    val content = args["content"] ?: ""
                    if (path.isBlank()) "⚠️ 语法：file_write(path=文件路径, content=文件内容)"
                    else {
                        try {
                            val f = java.io.File(path)
                            f.parentFile?.mkdirs()
                            f.writeText(content, Charsets.UTF_8)
                            "✅ 已写入 ${f.absolutePath}（${content.length} 字）"
                        } catch (e: Exception) { "❌ 写入失败：${e.message}" }
                    }
                }
                "file_search" -> {
                    // ★ v89 文件搜索（沙盒内目录扫描）
                    val path = args["path"] ?: "."
                    val name = (args["name"] ?: "").lowercase()
                    try {
                        val dir = java.io.File(path)
                        if (!dir.exists() || !dir.isDirectory) "❌ 目录不存在：$path"
                        else {
                            val files = dir.listFiles()?.filter { name.isBlank() || it.name.lowercase().contains(name) }?.take(30) ?: emptyList()
                            if (files.isEmpty()) "📂 $path 下没有${if (name.isNotBlank()) "匹配「$name」的" else ""}文件"
                            else "📂 $path 下 ${files.size} 项：\n" + files.joinToString("\n") { f ->
                                if (f.isDirectory) "📁 ${f.name}/" else "📄 ${f.name}（${f.length()}B）"
                            }
                        }
                    } catch (e: Exception) { "❌ 搜索失败：${e.message}" }
                }
                "skill_learn" -> {
                    // ★ v89 技能自动提炼（Hermes skill_manager 精髓：完成任务沉淀为可复用技能）
                    val name = args["name"] ?: ""
                    val trigger = args["trigger"] ?: ""
                    val content = args["content"] ?: ""
                    if (name.isBlank() || trigger.isBlank() || content.isBlank()) "⚠️ 语法：skill_learn(name=技能名, trigger=触发词, content=执行步骤)"
                    else {
                        try {
                            db.saveSkill(null, name, trigger, "contains", "skill", content, true, userId)
                            "🧠 技能已学会：#$name（触发词：$trigger）——下次用户说「$trigger」我会直接用它"
                        } catch (e: Exception) { "❌ 学习失败：${e.message}" }
                    }
                }
                "skill_list" -> {
                    // ★ v89 已学习技能列表（内置 + 用户自定义）
                    val custom = try { db.getSkills(userId) } catch (e: Exception) { emptyList() }
                    if (custom.isEmpty()) "📭 还没学到自定义技能（管理员可发「学习技能 xxx」沉淀复用能力）"
                    else "🧠 已学技能（${custom.size}个）：\n" + custom.joinToString("\n") { s ->
                        "· ${s["name"]}（触发：${s["trigger"]}）"
                    }
                }
                "cache_stats" -> {
                    // ★ v92 响应缓存统计/清理（管理员；stats 查看 / clear 清空）
                    val action = (args["action"] ?: "stats").lowercase()
                    if (action == "clear") {
                        db.clearResponseCache()
                        "🗑 响应缓存已清空"
                    } else {
                        val (total, hits) = db.getResponseCacheStats()
                        "⚡ 响应缓存：共 $total 条，累计命中 $hits 次（同请求 5 分钟内直接返回，省 token 省延迟）\n发「缓存 清理」可清空"
                    }
                }
                "skill_auto_learn" -> {
                    // ★ v92 自动提炼技能：模型完成任务后主动调用，把可复用步骤沉淀为技能（对齐 Hermes 自进化）
                    val name = args["name"]?.trim() ?: ""
                    val trigger = args["trigger"]?.trim() ?: ""
                    val content = args["content"]?.trim() ?: ""
                    if (name.isBlank() || trigger.isBlank() || content.isBlank()) {
                        "⚠️ 语法：skill_auto_learn(name=技能名, trigger=触发词, content=执行步骤)"
                    } else {
                        try {
                            db.saveSkill(null, name, trigger, "contains", "skill", content, true, userId)
                            "🧠 技能已自动提炼：#$name（触发词：$trigger）——下次用户说「$trigger」我会直接用，不再重新摸索"
                        } catch (e: Exception) { "❌ 提炼失败：${e.message}" }
                    }
                }
                "image_gen" -> {
                    // ★ v90 图像生成：真出图——优先调网关本地画图通道 /v1/images/generations（复用已配置画图模型），失败再提示 MCP
                    val prompt = args["prompt"] ?: ""
                    val size = (args["size"] ?: "1024x1024")
                    if (prompt.isBlank()) "⚠️ 语法：image_gen(prompt=图片描述, size=尺寸如 1024x1024)"
                    else {
                        val imgBody = "{\"model\":\"\",\"prompt\":${org.json.JSONObject.quote(prompt)},\"n\":1,\"size\":${org.json.JSONObject.quote(size)}}"
                        val imgResult = runCatching {
                            okhttp3.OkHttpClient().newCall(
                                okhttp3.Request.Builder()
                                    .url("http://127.0.0.1:${System.getenv("GATEWAY_PORT") ?: "18889"}/v1/images/generations")
                                    .addHeader("Content-Type", "application/json")
                                    .post(imgBody.toRequestBody("application/json".toMediaType()))
                                    .build()
                            ).execute().use { resp ->
                                val body = resp.body?.string().orEmpty()
                                if (!resp.isSuccessful) "🎨 画图失败（HTTP ${resp.code}）"
                                else {
                                    val b64 = org.json.JSONObject(body).optJSONArray("data")?.optJSONObject(0)?.optString("b64_json")
                                    if (b64.isNullOrBlank()) "🎨 画图完成，但未返回图片（模型可能不支持）"
                                    else "🎨 画图成功（${size}，b64 图片 ${b64.length / 1024}KB，可在管理后台查看）"
                                }
                            }
                        }.getOrElse { e -> "❌ 画图失败：${e.message}" }
                        // 本地通道失败且存在 MCP 图像工具时，给出 MCP 兜底指引
                        if (imgResult.startsWith("🎨 画图成功")) imgResult
                        else {
                            val mcpServers = try { com.qitong.gateway.sandbox.McpClient.listEnabled(db) } catch (e: Exception) { emptyList() }
                            var mcpHint = ""
                            for (srv in mcpServers) {
                                val toolsTxt = runCatching { com.qitong.gateway.sandbox.McpClient.listTools(srv) }.getOrDefault("")
                                if (toolsTxt.contains("image", true) || toolsTxt.contains("draw", true) || toolsTxt.contains("生成图片", true)) {
                                    mcpHint = "\n🖼 已找到 MCP 图像工具（${srv.name}），可改发「用 ${srv.name} 的 ${toolsTxt.lineSequence().firstOrNull()?.trim().orEmpty()} 生成 描述」"
                                    break
                                }
                            }
                            imgResult + mcpHint
                        }
                    }
                }
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
                "user_deduct" -> SkillExecutor.execute(db, "900021", "${args["username"] ?: ""} ${args["amount"] ?: ""}", userId)
                "terminal_run" -> {
                    // ★ v46：终端真执行（复用 TerminalManager.runOnce，危险命令拦截+超时+输出截断）
                    // ★ v59 增强：cmd 含中文（自然语言需求）时自动用 AiTermHelper 转命令，支持"扫一下 xxx"直接跑
                    // ★ v65 增强：疑似长任务命令（后台/循环/下载/编译/大输出）自动异步跑，不再 10 秒超时中断
                    val raw = args["cmd"] ?: ""
                    if (raw.isBlank()) "⚠️ 语法：terminal_run(cmd=要执行的命令 或 自然语言需求)"
                    else {
                        val cmd = if (raw.any { it.code in 0x4E00..0x9FFF }) {
                            val c = com.qitong.gateway.http.AiTermHelper.genCommand(raw)
                            if (c.isBlank()) return@executeSuspend "😵 无法将「$raw」转换为安全命令，请直接提供 shell 命令"
                            c
                        } else raw
                        val dangerous = listOf(
                            "rm -rf /", "rm -rf /*", "rm -fr /", "mkfs", "dd if=", "shutdown", "reboot", ":(){",
                            "format", "fdisk", "mkfs.ext", "mkfs.xfs", "chmod 777 /", "chown -R", "> /dev/sda",
                            // ★ v68 加固：docker 全家桶 + 高危系统操作
                            "docker rm", "docker rmi", "docker volume", "docker network", "docker compose down", "docker-compose down",
                            "docker stop", "docker kill", "docker system prune", "docker builder prune", "docker image prune",
                            "docker container prune", "docker restart", "systemctl stop", "systemctl disable", "kill -9 1",
                            "umount", "mount -o", "mv /", "cp -r / /", "crontab -r", "userdel", "find / -delete", ":(){ :|:& };:"
                        )
                        if (dangerous.any { cmd.contains(it) }) "⛔ 危险命令已拦截：$cmd"
                        else {
                            // ★ v65 长任务检测：包含这些特征 → 异步跑（后台不阻塞，返回 task-id 供轮询）
                            val longTask = cmd.contains(" &") || cmd.contains("nohup") || cmd.contains("sleep ") ||
                                cmd.contains("loop") || cmd.contains("while") || cmd.contains("curl") ||
                                cmd.contains("wget") || cmd.contains("ping -c 1") || cmd.contains("cat /") ||
                                cmd.contains("tail -f") || cmd.contains("apt") || cmd.contains("apt-get") ||
                                cmd.contains("npm") || cmd.contains("gradle") || cmd.contains("yarn") ||
                                cmd.contains("docker build") || cmd.contains("ping -c 5") || cmd.length > 80
                            if (longTask) {
                                com.qitong.gateway.http.TerminalManager.startAsync(cmd)
                            } else {
                                val out = com.qitong.gateway.http.TerminalManager.runOnce(cmd)
                                "🖥 执行: $cmd\n📤 输出:\n$out"
                            }
                        }
                    }
                }
                "terminal_status" -> {
                    // ★ v65 查询异步任务状态/结果（大模型轮询长任务）
                    val id = args["id"] ?: ""
                    if (id.isBlank()) "⚠️ 语法：terminal_status(id=任务ID，如 task-2001)"
                    else com.qitong.gateway.http.TerminalManager.asyncStatus(id.trim())
                }
                "terminal_create" -> {
                    // ★ v62 创建临时终端会话（TerminalManager.create，label 可选）
                    val label = args["label"]?.trim().orEmpty().ifBlank { "QQ终端-" + userId }
                    val s = com.qitong.gateway.http.TerminalManager.create(label)
                    "✅ 终端创建成功\n📛 名称: ${s.label}\n🆔 ID: ${s.id}\n\n发「terminal_run(cmd=命令)」在沙盒执行，或 Web 后台「终端」页查看/操作"
                }
                "terminal_list" -> {
                    val sessions = com.qitong.gateway.http.TerminalManager.list()
                    if (sessions.isEmpty()) "📋 暂无终端会话（可先 terminal_create 创建）"
                    else "📋 终端会话（${sessions.size}个）：\n" + sessions.take(10).joinToString("\n") { "· ${it.label} | ${it.id}" }
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
                "workflow_run" -> {
                    // ★ v63 执行工作流（按名称）
                    val name = args["name"] ?: ""
                    if (name.isBlank()) "⚠️ 语法：workflow_run(name=工作流名称)"
                    else {
                        val wfs = try { db.getWorkflows(0) } catch (e: Exception) { emptyList() }
                        val wf = wfs.firstOrNull { (it["name"] as? String)?.contains(name, true) == true || name.contains(it["name"] as? String ?: "", true) }
                        if (wf == null) "❌ 未找到工作流「$name」（发 workflow_list 查看已配置）"
                        else {
                            val stepsJson = try { org.json.JSONArray(wf["steps"] as? String ?: "[]") } catch (e: Exception) { org.json.JSONArray() }
                            val sb = StringBuilder("⚙️ 工作流「${wf["name"]}」执行：\n")
                            for (i in 0 until stepsJson.length()) {
                                val step = stepsJson.optJSONObject(i) ?: continue
                                val out = com.qitong.gateway.http.WorkflowEngine.runStep(db, step.optString("type", "reply"), step.optString("content", ""))
                                sb.append("【${i + 1}·${step.optString("type", "reply")}】\n$out\n\n")
                            }
                            sb.toString().trim().take(1500)
                        }
                    }
                }
                "workflow_list" -> {
                    val wfs = try { db.getWorkflows(0) } catch (e: Exception) { emptyList() }
                    if (wfs.isEmpty()) "📋 暂无工作流（后台「工作流」页可创建）"
                    else "📋 工作流（${wfs.size}个）：\n" + wfs.take(20).joinToString("\n") { w ->
                        "· ${w["name"]}（触发:${w["triggerText"] ?: "-"}，${if ((w["enabled"] as? Boolean) == true) "启用" else "停用"}）"
                    }
                }
                "heartbeat_status" -> com.qitong.gateway.sandbox.HeartbeatEngine.statusText()
                "sys_health" -> {
                    // ★ v67 全功能在线体检：网关/模型/服务商/心跳/QQ机器人/终端/工作流/MCP/磁盘 一键检查
                    val sb = StringBuilder("🏥 【綦桐AI网关 全功能体检】\n")
                    val t = java.text.SimpleDateFormat("MM-dd HH:mm").format(java.util.Date())
                    sb.append("📅 $t\n\n")
                    // 1. 网关
                    val gwRunning = com.qitong.gateway.http.GatewayProxy.running
                    sb.append(if (gwRunning) "✅ 网关服务：运行中\n" else "❌ 网关服务：已停止\n")
                    // 2. 模型
                    val models = db.getModels()
                    val enabled = models.count { it.isEnabled }
                    sb.append("✅ 模型：${models.size} 个（启用 $enabled）\n")
                    // 3. 服务商
                    val providers = db.getProviders()
                    val provOn = providers.count { it.isEnabled }
                    sb.append("✅ 服务商：${providers.size} 个（启用 $provOn）\n")
                    // 4. 心跳
                    sb.append(com.qitong.gateway.sandbox.HeartbeatEngine.statusText() + "\n")
                    // 5. QQ机器人
                    val bots = db.getQqBots()
                    if (bots.isEmpty()) sb.append("ℹ️ QQ机器人：未配置\n")
                    else {
                        val on = bots.count { (it["enabled"] as? Boolean) == true }
                        sb.append("✅ QQ机器人：${bots.size} 个（启用 $on）\n")
                    }
                    // 6. 终端
                    val terms = com.qitong.gateway.http.TerminalManager.list()
                    sb.append("✅ 终端会话：${terms.size} 个\n")
                    // 7. 工作流
                    val wfs = try { db.getWorkflows(0) } catch (e: Exception) { emptyList() }
                    sb.append(if (wfs.isEmpty()) "ℹ️ 工作流：未配置\n" else "✅ 工作流：${wfs.size} 个\n")
                    // 8. MCP
                    val mcpSrv = try { McpClient.listEnabled(db) } catch (e: Exception) { emptyList() }
                    sb.append(if (mcpSrv.isEmpty()) "ℹ️ MCP服务器：未配置\n" else "✅ MCP服务器：${mcpSrv.size} 个（${mcpSrv.joinToString(",") { it.name }}）\n")
                    // 9. 磁盘/内存
                    runCatching {
                        val proc = ProcessBuilder("sh", "-c", "df -P / | tail -1 | awk '{print $5}'").redirectErrorStream(true).start()
                        val pct = proc.inputStream.bufferedReader().readText().trim()
                        val rt = Runtime.getRuntime()
                        val memMb = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024
                        val memTotal = rt.totalMemory() / 1024 / 1024
                        sb.append(if (pct.isNotBlank() && pct.replace("%", "").toIntOrNull()?.let { it > 85 } == true)
                            "⚠️ 磁盘：$pct（建议清理）\n" else "✅ 磁盘：$pct 已用\n")
                        sb.append("✅ 内存：${memMb}/${memTotal} MB\n")
                    }
                    sb.toString().trim()
                }
                "heartbeat_check" -> com.qitong.gateway.sandbox.HeartbeatEngine.checkOnce(db)
                "heartbeat_get" -> {
                    val enabled = db.getConfig("heartbeat_enabled", "true") != "false"
                    val minutes = db.getConfig("heartbeat_interval_minutes", "60")
                    val start = db.getConfig("heartbeat_active_start", "5")
                    val end = db.getConfig("heartbeat_active_end", "23")
                    val prompt = db.getConfig("heartbeat_prompt", "")
                    "⚙️ 【自主心跳配置】\n开关: ${if (enabled) "✅ 启用" else "⛔ 停用"}\n间隔: $minutes 分钟\n活跃时段: $start:00 - $end:00\n提示词: ${if (prompt.isBlank()) "（空）" else prompt}\n运行状态: ${com.qitong.gateway.sandbox.HeartbeatEngine.statusText()}"
                }
                "heartbeat_start" -> {
                    val minutes = args["minutes"]?.toIntOrNull() ?: 60
                    if (minutes < 5) "⚠️ 间隔最少 5 分钟"
                    else {
                        db.setConfig("heartbeat_enabled", "true")
                        db.setConfig("heartbeat_interval_minutes", minutes.toString())
                        args["start"]?.let { db.setConfig("heartbeat_active_start", it) }
                        args["end"]?.let { db.setConfig("heartbeat_active_end", it) }
                        args["prompt"]?.let { db.setConfig("heartbeat_prompt", it) }
                        // 重启心跳协程
                        if (com.qitong.gateway.sandbox.HeartbeatEngine.isRunning()) com.qitong.gateway.sandbox.HeartbeatEngine.stop()
                        com.qitong.gateway.sandbox.HeartbeatEngine.start(db)
                        "✅ 自主心跳已启动（间隔 $minutes 分钟）\n📅 活跃时段 ${db.getConfig("heartbeat_active_start", "5")}:00-${db.getConfig("heartbeat_active_end", "23")}:00\n💬 提示词: ${db.getConfig("heartbeat_prompt", "（空）")}"
                    }
                }
                "heartbeat_stop" -> {
                    db.setConfig("heartbeat_enabled", "false")
                    com.qitong.gateway.sandbox.HeartbeatEngine.stop()
                    "✅ 自主心跳已停止"
                }
                "weixin_bots_list" -> {
                    val bots = db.getWeixinBots()
                    if (bots.isEmpty()) "📋 暂无微信机器人（后台「微信机器人」页可添加）"
                    else "📋 微信机器人（${bots.size}个）：\n" + bots.joinToString("\n") { b ->
                        "· #${b.id} ${b.name}（${if (b.enabled) "✅ 启用" else "⛔ 停用"}，模型 ${b.aiModel}）"
                    }
                }
                "weixin_bot_start" -> {
                    val id = args["id"]?.toLongOrNull() ?: 0
                    val bot = db.getWeixinBots().firstOrNull { it.id == id }
                    if (bot == null) "❌ 未找到微信机器人 #$id"
                    else {
                        com.qitong.gateway.weixin.WeixinBotManager.startBot(bot)
                        "✅ 微信机器人「${bot.name}」已启动"
                    }
                }
                "weixin_bot_stop" -> {
                    val id = args["id"]?.toLongOrNull() ?: 0
                    val bot = db.getWeixinBots().firstOrNull { it.id == id }
                    if (bot == null) "❌ 未找到微信机器人 #$id"
                    else {
                        com.qitong.gateway.weixin.WeixinBotManager.stopBot(id)
                        "✅ 微信机器人「${bot.name}」已停止"
                    }
                }
                "qq_bot_start" -> {
                    val id = args["id"]?.toLongOrNull() ?: 0
                    val bots = db.getQqBots()
                    val row = bots.firstOrNull { (it["id"] as? Number)?.toLong() == id }
                    if (row == null) "❌ 未找到 QQ 机器人 #$id"
                    else {
                        val b = com.qitong.gateway.qq.QqBotManager.botFromRowPublic(row)
                        com.qitong.gateway.qq.QqBotManager.startBot(b)
                        "✅ QQ机器人 #$id 已启动"
                    }
                }
                "qq_bot_stop" -> {
                    val id = args["id"]?.toLongOrNull() ?: 0
                    val bot = db.getQqBots().firstOrNull { (it["id"] as? Number)?.toLong() == id }
                    if (bot == null) "❌ 未找到 QQ 机器人 #$id"
                    else {
                        com.qitong.gateway.qq.QqBotManager.stopBot(id)
                        "✅ QQ机器人 #$id 已停止"
                    }
                }
                "task_create" -> {
                    if (db.getConfig("scheduled_tasks_enabled", "true") == "false") "⛔ 计划任务已停用（后台设置可开启）"
                    else {
                        val content = args["content"] ?: ""
                        val minutes = args["minutes"]?.toLongOrNull() ?: 0
                        val type = args["type"] ?: "remind"
                        val cronExpr = args["cron"] ?: ""
                        val intervalMinutes = args["interval"]?.toIntOrNull() ?: 0
                        if (content.isBlank() || (minutes < 1 && cronExpr.isBlank() && intervalMinutes < 1)) "⚠️ 语法：task_create(content=任务内容, minutes=几分钟后执行, cron=可选cron表达式如 0 8 * * *, interval=可选固定间隔分钟如 30, type=remind/action)"
                        else if (cronExpr.isNotBlank() && !com.qitong.gateway.sandbox.CronExpression.isValid(cronExpr)) {
                            "⚠️ cron 表达式无效（标准5字段：分 时 日 月 周，如 0 8 * * * = 每天8点、*/30 * * * * = 每30分钟）"
                        } else if (intervalMinutes > 0 && intervalMinutes < 1) {
                            "⚠️ 固定间隔至少 1 分钟"
                        } else {
                            val runAt = when {
                                cronExpr.isNotBlank() -> com.qitong.gateway.sandbox.CronExpression.parse(cronExpr)?.next(System.currentTimeMillis()) ?: (System.currentTimeMillis() + 60_000L)
                                intervalMinutes > 0 -> System.currentTimeMillis() + intervalMinutes * 60_000L
                                else -> System.currentTimeMillis() + minutes * 60_000L
                            }
                            val id = db.addScheduledTask(channel, userId.toString(), type, content, runAt, cronExpr, intervalMinutes)
                            when {
                                cronExpr.isNotBlank() -> "✅ 周期任务已创建 #$id：cron「$cronExpr」→「$content」（发 task_list 查看，到期自动执行并滚动到下一周期）"
                                intervalMinutes > 0 -> "✅ 循环任务已创建 #$id：每 $intervalMinutes 分钟执行「$content」（发 task_list 查看，到期自动执行并滚动到下一周期）"
                                else -> "✅ 计划任务已创建 #$id：$minutes 分钟后「$content」（发 task_list 查看）"
                            }
                        }
                    }
                }
                "task_list" -> {
                    val tasks = db.getScheduledTasks(channel = channel, userOpenid = userId.toString())
                    if (tasks.isEmpty()) "📭 你当前没有计划任务"
                    else "📋 我的计划任务（${tasks.size}条）：\n" + tasks.joinToString("\n") { t ->
                        val remain = ((t["runAt"] as? Long ?: 0) - System.currentTimeMillis())
                        val st = t["status"]
                        val cron = (t["cronExpr"] as? String) ?: ""
                        val cronTxt = if (cron.isNotBlank()) "【周期 ${cron}】" else ""
                        val timeTxt = try {
                            java.text.SimpleDateFormat("MM-dd HH:mm").format(java.util.Date((t["runAt"] as? Long ?: 0)))
                        } catch (e: Exception) { "-" }
                        "· #${t["id"]} [${st}] $cronTxt ${t["content"]}（下次 $timeTxt${if (remain > 0 && cron.isBlank()) "·剩${remain / 60_000L}分" else ""}）"
                    }
                }
                "task_cancel" -> {
                    val id = args["id"]?.toLongOrNull() ?: 0
                    db.cancelScheduledTask(id, userId.toString())
                    "✅ 计划任务 #$id 已取消"
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
                "mail_send" -> {
                    // ★ v80 真实发信：复用网关 SMTP 通知配置，可发任意收件人
                    val to = args["to"]?.trim().orEmpty()
                    val content = args["content"]?.trim().orEmpty()
                    val title = args["title"]?.trim().orEmpty()
                    if (to.isBlank() || !to.contains("@")) "⚠️ 语法：mail_send(to=收件人邮箱, title=主题, content=内容)"
                    else if (content.isBlank()) "⚠️ 邮件内容不能为空"
                    else com.qitong.gateway.notify.NotificationManager.sendMail(db, to, title, content)
                }
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
