package com.qitong.gateway

import com.qitong.gateway.auth.AuthManager
import com.qitong.gateway.auth.authUser
import com.qitong.gateway.auth.requireAuth
import com.qitong.gateway.db.Database
import com.qitong.gateway.http.AdminApi
import com.qitong.gateway.http.GatewayProxy
import com.qitong.gateway.http.GatewayScheduler
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.request.receive
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.options
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.http.content.staticResources
import io.ktor.server.http.content.staticFiles
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import java.io.File

/** 登录失败计数（按IP，暴力破解告警用） */
private val loginFailCount = java.util.concurrent.ConcurrentHashMap<String, Int>()

/**
 * 全局测速任务（测速页离开后仍在后台跑，回来看结果）
 * 状态保存在内存，前端轮询 /api/speedtest/progress
 */
object SpeedTaskRunner {
    data class TaskState(
        var running: Boolean = false,
        var total: Int = 0,
        var current: Int = 0,
        var currentKey: String = "",
        var currentName: String = "",
        var done: Int = 0,
        var passed: Int = 0,
        var failed: Int = 0,
        var results: MutableList<Map<String, Any?>> = mutableListOf(),
        var startedAt: Long = 0,
        var finishedAt: Long = 0,
        var error: String = ""
    )
    @Volatile var state = TaskState()
    private val lock = Any()

    fun snapshot(): Map<String, Any?> = synchronized(lock) {
        val s = state
        mapOf(
            "running" to s.running,
            "total" to s.total,
            "current" to s.current,
            "currentKey" to s.currentKey,
            "currentName" to s.currentName,
            "done" to s.done,
            "passed" to s.passed,
            "failed" to s.failed,
            "results" to s.results.toList(),
            "startedAt" to s.startedAt,
            "finishedAt" to s.finishedAt,
            "progress" to (if (s.total > 0) (s.done * 100 / s.total) else 0),
            "error" to s.error
        )
    }

    fun start(total: Int) = synchronized(lock) {
        state = TaskState(running = true, total = total, startedAt = System.currentTimeMillis())
    }

    fun markCurrent(idx: Int, key: String, name: String) = synchronized(lock) {
        state.current = idx; state.currentKey = key; state.currentName = name
    }

    fun addResult(r: Map<String, Any?>) = synchronized(lock) {
        state.results.add(r); state.done = state.results.size
        if ((r["isHealthy"] as? Boolean) == true) state.passed++ else state.failed++
    }

    fun finish() = synchronized(lock) {
        state.running = false; state.finishedAt = System.currentTimeMillis()
    }

    fun fail(msg: String) = synchronized(lock) {
        state.running = false; state.error = msg; state.finishedAt = System.currentTimeMillis()
    }
}

/**
 * 綦桐AI网关 · Docker 服务器版 v3.18.22-78
 * Web后台(18080) + 网关API(18889)
 */
fun main(args: Array<String>) {
    val webPort = System.getenv("WEB_PORT")?.toIntOrNull() ?: 18080
    val gatewayPort = System.getenv("GATEWAY_PORT")?.toIntOrNull() ?: 18889
    val dbPath = System.getenv("DB_PATH") ?: "/data/qitong/gateway.db"

    File(dbPath).parentFile?.mkdirs()

    println("""
        ╔══════════════════════════════════════════╗
        ║   綦桐AI网关 · Docker Server v3.18.22-78    ║
        ╠══════════════════════════════════════════╣
        ║  Web后台 : :$webPort  |  网关API : :$gatewayPort  ║
        ║  数据库  : $dbPath
        ╚══════════════════════════════════════════╝
    """.trimIndent())

    val database = Database(dbPath)

    val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
    if (database.getConfig("auto_failover", "true").toBoolean()) {
        scope.launch {
            kotlinx.coroutines.delay(3000)
            try {
                println("⚡ 启动自动测速...")
                GatewayScheduler.refreshHealthCache(database)
                GatewayScheduler.buildPipelineSortedModels(database)
                println("✅ 自动测速完成")
            } catch (e: Exception) {
                println("⚠️ 自动测速失败: ${e.message}")
            }
        }
    }

    // QQ 开放平台机器人：启动所有已启用的 Bot 长连接
    com.qitong.gateway.qq.QqBotManager.startAll(scope, database, gatewayPort)

    // ★ v69 微信 ilink 机器人：启动所有已启用的微信 Bot 长轮询
    com.qitong.gateway.weixin.WeixinBotManager.startAll(database, gatewayPort)

    val gatewayServer = embeddedServer(Netty, port = gatewayPort) { moduleGateway(database) }
    val webServer = embeddedServer(Netty, port = webPort) { moduleWeb(database) }

    Runtime.getRuntime().addShutdownHook(Thread {
        println("🛑 正在关闭...")
        gatewayServer.stop(500, 2000)
        webServer.stop(500, 2000)
        database.close()
    })

    try {
        gatewayServer.start(wait = false)
        webServer.start(wait = false)
        println("🚀 启动完成！后台: :$webPort  网关: :$gatewayPort")
        Thread.currentThread().join()
    } catch (e: Exception) {
        println("❌ 启动失败: ${e.message}")
        e.printStackTrace()
    }
}

fun Application.moduleGateway(database: Database) {
    val proxy = GatewayProxy(database)

    install(CORS) {
        anyHost()
        allowHeader("Content-Type")
        allowHeader("Authorization")
        allowHeader("x-api-key")
        allowHeader("anthropic-version")
        allowHeader("x-goog-api-key")
        allowMethod(io.ktor.http.HttpMethod.Options)
        allowMethod(io.ktor.http.HttpMethod.Get)
        allowMethod(io.ktor.http.HttpMethod.Post)
        allowMethod(io.ktor.http.HttpMethod.Put)
        allowMethod(io.ktor.http.HttpMethod.Delete)
    }
    install(DefaultHeaders)

    routing {
        get("/health") {
            val models = database.getEnabledModels()
            val healthJson = buildJsonObject {
                put("status", JsonPrimitive("ok"))
                put("service", JsonPrimitive("qitong-ai-gateway-docker"))
                put("version", JsonPrimitive("3.18.22-78"))
                put("running", JsonPrimitive(true))
                put("port", JsonPrimitive(System.getenv("GATEWAY_PORT")?.toIntOrNull() ?: 18889))
                put("failover", JsonPrimitive(database.getConfig("auto_failover", "true").toBoolean()))
                put("models_count", JsonPrimitive(models.size))
                put("uptime_seconds", JsonPrimitive((System.currentTimeMillis() - GatewayProxy.startTime) / 1000))
                put("require_api_key", JsonPrimitive(database.getConfig("require_api_key", "true").toBoolean()))
            }
            call.respondText(healthJson.toString(), ContentType.Application.Json.withCharset(Charsets.UTF_8))
        }

        options("/{path...}") {
            call.respondText("", ContentType.Application.Json, HttpStatusCode.OK)
        }

        get("/v1/models") {
            cors(call)
            if (!proxy.validateApiKey(call, call.remoteIp())) {
                call.respondText(openAIErrorRaw(401, "Invalid or missing API key", "invalid_api_key"), ContentType.Application.Json, HttpStatusCode.Unauthorized)
                return@get
            }
            val models = database.getEnabledModels()
            val modelList = models.map { m ->
                buildJsonObject {
                    put("id", JsonPrimitive(m.modelId))
                    put("object", JsonPrimitive("model"))
                    put("owned_by", JsonPrimitive("custom"))
                    put("model_id", JsonPrimitive(m.modelId))
                    put("display_name", JsonPrimitive(if (m.customAlias.isNotBlank()) m.customAlias else m.displayName))
                    put("provider_id", JsonPrimitive(m.providerId))
                }
            }
            val finalList = modelList + buildJsonObject {
                put("id", JsonPrimitive("qtai-sj"))
                put("object", JsonPrimitive("model"))
                put("owned_by", JsonPrimitive("qitong"))
                put("model_id", JsonPrimitive("qtai-sj"))
                put("display_name", JsonPrimitive("🔄 自动化切换"))
            }
            call.respondText(
                buildJsonObject {
                    put("object", JsonPrimitive("list"))
                    put("data", JsonArray(finalList))
                }.toString(),
                ContentType.Application.Json.withCharset(Charsets.UTF_8)
            )
        }

        get("/v1/chat/completions") {
            cors(call)
            call.respondText(
                openAIErrorRaw(400, "This endpoint requires a POST request.", "invalid_request_error"),
                ContentType.Application.Json, HttpStatusCode.BadRequest
            )
        }

        post("/chat/completions") {
            cors(call)
            proxy.proxyRequest(call, "/v1/chat/completions", call.remoteIp())
        }

        listOf(
            "/v1/chat/completions",
            "/v1/completions",
            "/v1/messages",
            "/v1/embeddings",
            "/v1/rerank",
            "/v1/moderations",
            "/v1/audio/speech",
            "/v1/images/generations",
            "/v1/videos",
            "/v1/video/generations",
            "/v1beta/models/{model}:generateContent",
            "/v1/engines/{model}/embeddings"
        ).forEach { path ->
            post(path) {
                cors(call)
                if (!proxy.validateApiKey(call, call.remoteIp())) {
                    call.respondText(openAIErrorRaw(401, "Invalid or missing API key", "invalid_api_key"), ContentType.Application.Json, HttpStatusCode.Unauthorized)
                    return@post
                }
                proxy.proxyRequest(call, path, call.remoteIp())
            }
        }

        get("/v1/video/generations/{task_id}") {
            cors(call)
            if (!proxy.validateApiKey(call, call.remoteIp())) {
                call.respondText(openAIErrorRaw(401, "Invalid or missing API key", "invalid_api_key"), ContentType.Application.Json, HttpStatusCode.Unauthorized)
                return@get
            }
            proxy.proxyRequest(call, "/v1/video/generations", call.remoteIp())
        }

        get("/v1/files") {
            call.respondText(
                openAIErrorRaw(501, "This endpoint is not implemented.", "not_implemented"),
                ContentType.Application.Json, HttpStatusCode.NotImplemented
            )
        }

        post("/v1/{path...}") {
            cors(call)
            if (!proxy.validateApiKey(call, call.remoteIp())) {
                call.respondText(openAIErrorRaw(401, "Invalid or missing API key", "invalid_api_key"), ContentType.Application.Json, HttpStatusCode.Unauthorized)
                return@post
            }
            val path = "/v1/" + (call.parameters.getAll("path")?.joinToString("/") ?: "")
            proxy.proxyRequest(call, path, call.remoteIp())
        }

        get("/v1/{path...}") {
            cors(call)
            if (!proxy.validateApiKey(call, call.remoteIp())) {
                call.respondText(openAIErrorRaw(401, "Invalid or missing API key", "invalid_api_key"), ContentType.Application.Json, HttpStatusCode.Unauthorized)
                return@get
            }
            val path = "/v1/" + (call.parameters.getAll("path")?.joinToString("/") ?: "")
            proxy.proxyRequest(call, path, call.remoteIp())
        }
    }
}

fun Application.moduleWeb(database: Database) {
    install(ContentNegotiation) { json() }
    install(CallLogging)
    install(DefaultHeaders)

    routing {
        // 静态资源（layui）— 目录结构 static/assets/layui，天然匹配 /assets/layui 前缀
        staticResources("/assets", "static/assets")

        get("/") { call.respondText(WebUi.loginHtml(), ContentType.Text.Html.withCharset(Charsets.UTF_8)) }
        get("/login") { call.respondText(WebUi.loginHtml(), ContentType.Text.Html.withCharset(Charsets.UTF_8)) }
        get("/admin") { call.respondText(WebUi.adminHtml(), ContentType.Text.Html.withCharset(Charsets.UTF_8)) }
        get("/app") { call.respondText(WebUi.adminHtml(), ContentType.Text.Html.withCharset(Charsets.UTF_8)) }

        // 认证
        post("/api/auth/register") {
            val body = call.receive<JsonObject>()
            val uname = body["username"]?.jsonPrimitive?.content ?: ""
            val result = AuthManager.register(
                database,
                uname,
                body["password"]?.jsonPrimitive?.content ?: "",
                body["displayName"]?.jsonPrimitive?.content ?: "",
                body["inviteCode"]?.jsonPrimitive?.content ?: ""
            )
            if (result.isSuccess) {
                // 新用户注册通知管理员（钉钉/邮箱）- 异步线程
                Thread {
                    try { kotlinx.coroutines.runBlocking { com.qitong.gateway.notify.NotificationManager.notify(
                        database, "🆕 新用户注册",
                        "新用户注册: $uname\n时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date())}"
                    ) } } catch (_: Exception) {}
                }.start()
                AdminApi.ok(call, "注册成功")
            }
            else AdminApi.fail(call, result.exceptionOrNull()?.message ?: "注册失败", 400)
        }
        post("/api/auth/login") {
            val body = call.receive<JsonObject>()
            val result = AuthManager.login(
                database,
                body["username"]?.jsonPrimitive?.content ?: "",
                body["password"]?.jsonPrimitive?.content ?: ""
            )
            if (result.isSuccess) {
                val user = AuthManager.getUserByToken(database, result.getOrNull())
                val loginIp = call.request.local.remoteHost
                if (user != null) {
                    database.addOpLog(user.id, user.username, "登录", "后台登录成功", loginIp)
                    database.addLoginLog(user.id, user.username, true, loginIp, "登录成功")
                }
                AdminApi.ok(call, mapOf(
                    "token" to result.getOrNull(),
                    "user" to (user?.let { mapOf("id" to it.id, "username" to it.username, "role" to it.role, "displayName" to it.displayName) })
                ), "登录成功")
            } else {
                // 记录失败登录日志（宝塔风格：失败 + IP + 原因）
                val failIp = call.request.local.remoteHost
                val failUser = body["username"]?.jsonPrimitive?.content ?: ""
                // 尝试匹配用户名对应的用户ID
                val failUserId = database.getUserByUsername(failUser)?.id ?: 0L
                database.addLoginLog(failUserId, failUser, false, failIp, "密码错误或用户不存在")
                database.addOpLog(failUserId, failUser, "登录失败", "密码错误或用户不存在", failIp)
                // ★ P1 暴力破解告警：同 IP 5 次失败触发钉钉/邮箱
                val failCount = loginFailCount.computeIfAbsent(failIp) { 0 }.let { c ->
                    loginFailCount[failIp] = c + 1; c + 1
                }
                if (failCount == 5) {
                    Thread {
                        try { kotlinx.coroutines.runBlocking {
                            com.qitong.gateway.notify.NotificationManager.notify(
                                database, "🚨 疑似暴力破解",
                                "IP ${failIp} 连续 5 次登录失败，请检查是否有人尝试破解后台"
                            )
                        } } catch (_: Exception) {}
                    }.start()
                } else if (failCount > 20) {
                    loginFailCount.remove(failIp)  // 计数重置，避免无限告警
                }
                AdminApi.fail(call, result.exceptionOrNull()?.message ?: "登录失败", 401)
            }
        }
        post("/api/auth/logout") {
            val token = call.request.headers["Authorization"]?.removePrefix("Bearer ")?.trim()
            AuthManager.logout(database, token ?: "")
            AdminApi.ok(call, null, "已退出")
        }
        get("/api/auth/me") {
            val user = call.authUser(database)
            if (user == null) AdminApi.fail(call, "未登录", 401)
            else AdminApi.ok(call, user, "ok")
        }
        post("/api/auth/change-password") {
            val user = call.requireAuth(database) ?: return@post
            val body = call.receive<JsonObject>()
            val oldPwd = body["oldPassword"]?.jsonPrimitive?.content ?: ""
            val newPwd = body["newPassword"]?.jsonPrimitive?.content ?: ""
            val result = AuthManager.changePassword(database, user, oldPwd, newPwd)
            if (result.isSuccess) AdminApi.ok(call, null, "密码修改成功，请重新登录")
            else AdminApi.fail(call, result.exceptionOrNull()?.message ?: "修改失败", 400)
        }

        // 服务商
        get("/api/providers") { val u = call.requireAuth(database) ?: return@get; AdminApi.ok(call, AdminApi.getVisibleProviders(database, u)) }
        post("/api/providers") {
            val u = call.requireAuth(database) ?: return@post
            val body = call.receive<JsonObject>()
            val id = AdminApi.saveProvider(database, body, u)
            if (id < 0) AdminApi.fail(call, "无权限管理该服务商", 403)
            else AdminApi.ok(call, mapOf("id" to id), "保存成功")
        }
        delete("/api/providers/{id}") {
            val id = call.parameters["id"]?.toLongOrNull() ?: return@delete
            val u = call.requireAuth(database) ?: return@delete
            if (AdminApi.deleteProvider(database, id, u)) AdminApi.ok(call, null, "已删除")
            else AdminApi.fail(call, "无权限删除该服务商", 403)
        }
        post("/api/providers/{id}/sync") {
            val id = call.parameters["id"]?.toLongOrNull() ?: return@post
            val u = call.requireAuth(database) ?: return@post
            val provider = database.getProviderById(id)
            if (provider == null || (!AdminApi.canManageResource(u, AdminApi.Perm.P_MANAGE, provider.ownerId))) {
                AdminApi.fail(call, "无权限同步该服务商", 403); return@post
            }
            val count = AdminApi.syncProviderModels(database, id)
            if (count > 0) AdminApi.ok(call, mapOf("synced" to count), "同步成功 $count 个模型")
            else AdminApi.fail(call, "同步失败或没有新模型", 400)
        }

        // 模型
        get("/api/models") { val u = call.requireAuth(database) ?: return@get; AdminApi.ok(call, AdminApi.getVisibleModels(database, u)) }
        post("/api/models") {
            val u = call.requireAuth(database) ?: return@post
            val body = call.receive<JsonObject>()
            val id = AdminApi.saveModel(database, body, u)
            if (id < 0) AdminApi.fail(call, "无权限管理该模型", 403)
            else AdminApi.ok(call, mapOf("id" to id), "保存成功")
        }
        delete("/api/models/{id}") {
            val id = call.parameters["id"]?.toLongOrNull() ?: return@delete
            val u = call.requireAuth(database) ?: return@delete
            if (AdminApi.deleteModel(database, id, u)) AdminApi.ok(call, null, "已删除")
            else AdminApi.fail(call, "无权限删除该模型", 403)
        }
        // 模型启用/停用
        post("/api/models/toggle") {
            val u = call.requireAuth(database) ?: return@post
            val body = call.receive<JsonObject>()
            val id = body["id"]?.jsonPrimitive?.content?.toLongOrNull() ?: run { AdminApi.fail(call, "模型ID无效", 400); return@post }
            val model = database.getModelById(id) ?: run { AdminApi.fail(call, "模型不存在", 404); return@post }
            if (!AdminApi.canManageResource(u, AdminApi.Perm.M_MANAGE, model.ownerId)) { AdminApi.fail(call, "无权限操作该模型", 403); return@post }
            val newEnabled = body["enabled"]?.let {
                when { it is JsonPrimitive && it.isString -> it.content == "true" || it.content == "1"; else -> !model.isEnabled }
            } ?: !model.isEnabled
            database.updateModel(model.copy(isEnabled = newEnabled))
            AdminApi.ok(call, mapOf("id" to id, "enabled" to newEnabled), if (newEnabled) "模型已启用" else "模型已停用")
        }
        // 批量测速（原APP方式：逐个串行，通过自动启用，失败可选自动关闭）
        post("/api/speedtest/batch") {
            val u = call.requireAuth(database) ?: return@post
            // 系统级测速需权限：admin 或 system.speedtest
            if (!AdminApi.hasPerm(u, AdminApi.Perm.SYS_SPEED)) { AdminApi.fail(call, "无权限执行全局测速", 403); return@post }
            val body = runCatching { call.receive<JsonObject>() }.getOrElse { buildJsonObject { } }
            val autoClose = body["autoClose"]?.let {
                when { it is JsonPrimitive && it.isString -> it.content == "true" || it.content == "1"; else -> false }
            } ?: false
            val results = AdminApi.batchSpeedTest(database, autoClose)
            AdminApi.ok(call, results, "批量测速完成")
        }
        post("/api/models/sync/{providerId}") {
            val pid = call.parameters["providerId"]?.toLongOrNull() ?: return@post
            val u = call.requireAuth(database) ?: return@post
            val provider = database.getProviderById(pid)
            if (provider == null || (!AdminApi.canManageResource(u, AdminApi.Perm.M_MANAGE, provider.ownerId))) {
                AdminApi.fail(call, "无权限同步该模型", 403); return@post
            }
            val count = AdminApi.syncProviderModels(database, pid)
            AdminApi.ok(call, mapOf("synced" to count), "同步完成")
        }

        // 测速
        post("/api/speedtest") {
            val u = call.requireAuth(database) ?: return@post
            if (!AdminApi.hasPerm(u, AdminApi.Perm.SYS_SPEED)) { AdminApi.fail(call, "无权限执行全局测速", 403); return@post }
            AdminApi.ok(call, AdminApi.speedTest(database), "测速完成")
        }
        // 待测速模型列表（测速页默认渲染当前用户可见的已启用模型为"待测速"）
        get("/api/speedtest/models") {
            val u = call.requireAuth(database) ?: return@get
            AdminApi.ok(call, AdminApi.getSpeedTestModels(database, u), "ok")
        }
        // 单模型测速（前端逐个调用，测一个显示一个，对齐原APP缓冲流式刷新）
        post("/api/speedtest/one") {
            val u = call.requireAuth(database) ?: return@post
            val body = runCatching { call.receive<JsonObject>() }.getOrElse { buildJsonObject { } }
            val providerId = body["providerId"]?.jsonPrimitive?.content?.toLongOrNull() ?: -1
            val modelId = body["modelId"]?.jsonPrimitive?.content ?: ""
            // 权限：管理员可测全部；普通用户只能测自己可见的模型（公用+自己的）
            val model = database.getModelByKey(providerId, modelId)
            if (model == null) { AdminApi.fail(call, "模型不存在", 404); return@post }
            val visible = if (u.role == "admin") true else (model.isPublic || model.ownerId == u.id)
            if (!visible) { AdminApi.fail(call, "无权限测速该模型", 403); return@post }
            AdminApi.ok(call, AdminApi.speedTestOneModel(database, providerId, modelId), "ok")
        }
        // 启动后台批量测速（离开页面也在跑，回来看进度/结果）
        post("/api/speedtest/start") {
            val u = call.requireAuth(database) ?: return@post
            if (SpeedTaskRunner.state.running) { AdminApi.ok(call, SpeedTaskRunner.snapshot(), "测速已在运行中"); return@post }
            val models = AdminApi.getSpeedTestModels(database, u)
            if (models.isEmpty()) { AdminApi.fail(call, "暂无待测速的模型", 400); return@post }
            SpeedTaskRunner.start(models.size)
            // 后台协程逐个测速（不阻塞请求，前端轮询进度）
            kotlinx.coroutines.GlobalScope.launch {
                try {
                    for ((i, m) in models.withIndex()) {
                        SpeedTaskRunner.markCurrent(i, "${m["providerId"]}::${m["modelId"]}", (m["displayName"] as? String) ?: "")
                        val providerId = (m["providerId"] as? Number)?.toLong() ?: -1
                        val modelId = m["modelId"] as? String ?: ""
                        val r = AdminApi.speedTestOneModel(database, providerId, modelId)
                        SpeedTaskRunner.addResult(r)
                        kotlinx.coroutines.delay(150) // 轻微间隔，避免上游风暴
                    }
                    SpeedTaskRunner.finish()
                } catch (e: Exception) {
                    SpeedTaskRunner.fail("测速异常: ${e.message}")
                }
            }
            AdminApi.ok(call, SpeedTaskRunner.snapshot(), "后台测速已启动")
        }
        // 轮询测速进度（离开页面后回来也能看到结果）
        get("/api/speedtest/progress") {
            val u = call.requireAuth(database) ?: return@get
            AdminApi.ok(call, SpeedTaskRunner.snapshot(), "ok")
        }
        // 健康缓存（模型页每行显示测过状态）
        get("/api/speedtest/health") {
            val u = call.requireAuth(database) ?: return@get
            val cache = com.qitong.gateway.http.GatewayScheduler.healthCacheSnapshot()
            AdminApi.ok(call, cache, "ok")
        }
        // 传输明细（每次调用一条：上传/下载/token，对齐原APP TokenUsage；admin=全部，普通用户=自己）
        get("/api/usage/recent") {
            val u = call.requireAuth(database) ?: return@get
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 200
            AdminApi.ok(call, AdminApi.getUsageRecent(database, u, limit), "ok")
        }
        // 清理用量：普通用户清自己的；管理员清全部（用户独立管理）
        post("/api/usage/clear") {
            val u = call.requireAuth(database) ?: return@post
            if (u.role == "admin") database.clearTokenUsage()
            else database.clearTokenUsageByUser(u.id)
            AdminApi.ok(call, null, "用量已清理")
        }
        // 删除单条用量记录：普通用户只能删自己的；管理员可删任何
        post("/api/usage/delete") {
            val u = call.requireAuth(database) ?: return@post
            val body = call.receive<JsonObject>()
            val id = body["id"]?.jsonPrimitive?.content?.toLongOrNull() ?: run { AdminApi.fail(call, "记录ID无效", 400); return@post }
            val ok = if (u.role == "admin") database.deleteTokenUsage(id)
                else database.deleteTokenUsage(id, u.id)
            if (ok) AdminApi.ok(call, null, "记录已删除")
            else AdminApi.fail(call, "删除失败（只能删自己的记录）", 403)
        }
        // 按模型清理用量：普通用户删自己该模型；管理员删任意
        post("/api/usage/clear-model") {
            val u = call.requireAuth(database) ?: return@post
            val body = call.receive<JsonObject>()
            val modelKey = body["modelKey"]?.jsonPrimitive?.content ?: run { AdminApi.fail(call, "模型Key无效", 400); return@post }
            val n = if (u.role == "admin") database.deleteTokenUsageByModel(modelKey)
                else database.deleteTokenUsageByModel(modelKey, u.id)
            AdminApi.ok(call, mapOf("deleted" to n), "已清理 $n 条记录")
        }

        // 配置
        get("/api/config") { val u = call.requireAuth(database) ?: return@get; AdminApi.ok(call, AdminApi.getAllConfig(database)) }
        post("/api/config") {
            val u = call.requireAuth(database) ?: return@post
            // 修改系统配置需权限：admin 或 system.config
            if (!AdminApi.hasPerm(u, AdminApi.Perm.SYS_CONFIG)) { AdminApi.fail(call, "无权限修改系统配置", 403); return@post }
            val body = call.receive<JsonObject>()
            AdminApi.setConfigs(database, body)
            AdminApi.ok(call, null, "已保存")
        }

        // 统计（admin=全量，普通用户=自己）
        get("/api/stats") {
            val u = call.requireAuth(database) ?: return@get
            AdminApi.respondJson(call, buildJsonObject {
                put("code", JsonPrimitive(0)); put("msg", JsonPrimitive("ok"))
                put("data", AdminApi.getStats(database, u))
            }, 200)
        }

        // 密钥
        get("/api/keys") { val u = call.requireAuth(database) ?: return@get; AdminApi.ok(call, AdminApi.getVisibleApiKeys(database, u)) }
        post("/api/keys") {
            val u = call.requireAuth(database) ?: return@post
            val body = call.receive<JsonObject>()
            if (AdminApi.addApiKey(database, body, u)) AdminApi.ok(call, null, "添加成功")
            else AdminApi.fail(call, "密钥已存在或无效", 400)
        }
        delete("/api/keys/{key}") {
            val key = call.parameters["key"] ?: return@delete
            val u = call.requireAuth(database) ?: return@delete
            if (AdminApi.deleteApiKey(database, key, u)) AdminApi.ok(call, null, "已删除")
            else AdminApi.fail(call, "无权限删除该密钥", 403)
        }

        // 路由规则
        get("/api/rules") { val u = call.requireAuth(database) ?: return@get; AdminApi.ok(call, AdminApi.getVisibleRules(database, u)) }
        post("/api/rules") {
            val u = call.requireAuth(database) ?: return@post
            val body = call.receive<JsonObject>()
            val id = AdminApi.saveRule(database, body, u)
            if (id < 0) AdminApi.fail(call, "无权限管理该规则", 403)
            else AdminApi.ok(call, mapOf("id" to id), "保存成功")
        }
        delete("/api/rules/{id}") {
            val id = call.parameters["id"]?.toLongOrNull() ?: return@delete
            val u = call.requireAuth(database) ?: return@delete
            if (AdminApi.deleteRule(database, id, u)) AdminApi.ok(call, null, "已删除")
            else AdminApi.fail(call, "无权限删除该规则", 403)
        }

        // ============ QQ 开放平台机器人（仅管理员） ============
        fun requireAdminQQ(u: com.qitong.gateway.model.User): Boolean = u.role == "admin"

        get("/api/qq/bots") {
            val u = call.requireAuth(database) ?: return@get
            if (!requireAdminQQ(u)) { AdminApi.fail(call, "仅管理员可管理QQ机器人", 403); return@get }
            val list = database.getQqBots().map { row ->
                val id = row["id"] as Long
                val st = com.qitong.gateway.qq.QqBotManager.statusOf(id)
                row + mapOf(
                    "online" to (st.status == com.qitong.gateway.qq.QqBotStatus.ONLINE),
                    "status" to st.status.name,
                    "lastError" to st.lastError,
                    "messagesHandled" to st.messagesHandled,
                    "readyAt" to st.readyAt
                )
            }
            AdminApi.ok(call, list, "ok")
        }
        post("/api/qq/bots") {
            val u = call.requireAuth(database) ?: return@post
            if (!requireAdminQQ(u)) { AdminApi.fail(call, "仅管理员可管理QQ机器人", 403); return@post }
            val body = call.receive<JsonObject>()
            val appid = body["appid"]?.jsonPrimitive?.content?.trim().orEmpty()
            var token = body["token"]?.jsonPrimitive?.content?.trim().orEmpty()
            var appSecret = body["appSecret"]?.jsonPrimitive?.content?.trim().orEmpty()
            val useSandbox = body["useSandbox"]?.jsonPrimitive?.content?.toBoolean() ?: false
            if (appid.isBlank()) { AdminApi.fail(call, "AppID 不能为空", 400); return@post }
            // 编辑已有机器人时，Token/AppSecret 留空则沿用原值
            val existing = database.getQqBotByAppid(appid)
            if (token.isBlank()) token = (existing?.get("token") as? String).orEmpty()
            if (appSecret.isBlank()) appSecret = (existing?.get("appSecret") as? String).orEmpty()
            if (token.isBlank() && appSecret.isBlank()) { AdminApi.fail(call, "AppID、Token/AppSecret 不能同时为空", 400); return@post }
            val name = body["name"]?.jsonPrimitive?.content?.trim().orEmpty()
            val enabled = body["enabled"]?.jsonPrimitive?.content?.toBoolean() ?: true
            val aiModel = body["aiModel"]?.jsonPrimitive?.content?.trim()?.ifBlank { "qtai-sj" } ?: "qtai-sj"
            val systemPrompt = body["systemPrompt"]?.jsonPrimitive?.content ?: ""
            val welcome = body["welcome"]?.jsonPrimitive?.content ?: ""
            val id = database.upsertQqBot(appid, token, appSecret, useSandbox, name, enabled, aiModel, systemPrompt, welcome)
            // 保存后立即按启用状态拉起/停止
            val fresh = database.getQqBotById(id)
            if (fresh != null) {
                if (enabled) {
                    com.qitong.gateway.qq.QqBotManager.startBot(com.qitong.gateway.qq.QqBot(
                        id = id, appid = appid, token = token, appSecret = appSecret, useSandbox = useSandbox,
                        name = name, enabled = true, aiModel = aiModel, systemPrompt = systemPrompt, welcome = welcome
                    ))
                } else {
                    com.qitong.gateway.qq.QqBotManager.stopBot(id)
                }
            }
            AdminApi.ok(call, mapOf("id" to id), "保存成功")
        }
        delete("/api/qq/bots/{id}") {
            val id = call.parameters["id"]?.toLongOrNull() ?: return@delete
            val u = call.requireAuth(database) ?: return@delete
            if (!requireAdminQQ(u)) { AdminApi.fail(call, "仅管理员可管理QQ机器人", 403); return@delete }
            com.qitong.gateway.qq.QqBotManager.stopBot(id)
            database.deleteQqBot(id)
            AdminApi.ok(call, null, "已删除")
        }
        post("/api/qq/bots/{id}/toggle") {
            val id = call.parameters["id"]?.toLongOrNull() ?: return@post
            val u = call.requireAuth(database) ?: return@post
            if (!requireAdminQQ(u)) { AdminApi.fail(call, "仅管理员可管理QQ机器人", 403); return@post }
            val row = database.getQqBotById(id) ?: run { AdminApi.fail(call, "机器人不存在", 404); return@post }
            val nowOn = !(row["enabled"] as Boolean)
            database.setQqBotEnabled(id, nowOn)
            if (nowOn) com.qitong.gateway.qq.QqBotManager.restartBot(id)
            else com.qitong.gateway.qq.QqBotManager.stopBot(id)
            AdminApi.ok(call, mapOf("enabled" to nowOn), if (nowOn) "已启用" else "已停用")
        }
        post("/api/qq/bots/{id}/restart") {
            val id = call.parameters["id"]?.toLongOrNull() ?: return@post
            val u = call.requireAuth(database) ?: return@post
            if (!requireAdminQQ(u)) { AdminApi.fail(call, "仅管理员可管理QQ机器人", 403); return@post }
            com.qitong.gateway.qq.QqBotManager.restartBot(id)
            AdminApi.ok(call, null, "已重启")
        }
        get("/api/qq/groups") {
            val u = call.requireAuth(database) ?: return@get
            if (!requireAdminQQ(u)) { AdminApi.fail(call, "仅管理员可查看群配置", 403); return@get }
            AdminApi.ok(call, database.getQqGroups(), "ok")
        }
        post("/api/qq/groups/update") {
            val u = call.requireAuth(database) ?: return@post
            if (!requireAdminQQ(u)) { AdminApi.fail(call, "仅管理员可修改群配置", 403); return@post }
            val body = call.receive<JsonObject>()
            val groupOpenid = body["groupOpenid"]?.jsonPrimitive?.content?.trim().orEmpty()
            if (groupOpenid.isBlank()) { AdminApi.fail(call, "groupOpenid 不能为空", 400); return@post }
            val aiEnabled = body["aiEnabled"]?.jsonPrimitive?.content?.toBooleanStrictOrNull()
            val welcomeEnabled = body["welcomeEnabled"]?.jsonPrimitive?.content?.toBooleanStrictOrNull()
            val greeting = body["greeting"]?.jsonPrimitive?.content
            val adminMute = body["adminMute"]?.jsonPrimitive?.content?.toBooleanStrictOrNull()
            val adminKick = body["adminKick"]?.jsonPrimitive?.content?.toBooleanStrictOrNull()
            val adminManage = body["adminManage"]?.jsonPrimitive?.content?.toBooleanStrictOrNull()
            val groupName = body["groupName"]?.jsonPrimitive?.content
            val groupPrompt = body["groupPrompt"]?.jsonPrimitive?.content
            database.updateQqGroup(groupOpenid, aiEnabled, welcomeEnabled, greeting, adminMute, adminKick, adminManage, groupName, groupPrompt)
            AdminApi.ok(call, null, "群配置已保存")
        }
        // 删除群配置（含该群下用户记录）
        delete("/api/qq/groups/{openid}") {
            val openid = call.parameters["openid"].orEmpty()
            val u = call.requireAuth(database) ?: return@delete
            if (!requireAdminQQ(u)) { AdminApi.fail(call, "仅管理员", 403); return@delete }
            database.deleteQqGroup(openid)
            AdminApi.ok(call, null, "群已删除")
        }
        // 删除单个用户
        delete("/api/qq/users/{openid}") {
            val openid = call.parameters["openid"].orEmpty()
            val u = call.requireAuth(database) ?: return@delete
            if (u.role != "admin") { AdminApi.fail(call, "仅管理员", 403); return@delete }
            database.deleteQqUser(openid)
            AdminApi.ok(call, null, "用户已删除")
        }
        // 按群设置用户权限（群隔离）
        post("/api/qq/users/perm-group") {
            val u = call.requireAuth(database) ?: return@post
            if (u.role != "admin") { AdminApi.fail(call, "仅管理员", 403); return@post }
            val body = call.receive<JsonObject>()
            val openid = body["openid"]?.jsonPrimitive?.content?.trim().orEmpty()
            val groupOpenid = body["groupOpenid"]?.jsonPrimitive?.content?.trim().orEmpty()
            val level = body["level"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1
            val flags = body["flags"]?.jsonPrimitive?.content ?: ""
            database.setQqUserPermByGroup(openid, groupOpenid, level, flags)
            AdminApi.ok(call, null, "权限已保存")
        }
        // ============ 微信 ilink 机器人（v69，对齐 QQ 管理） ============
        get("/api/weixin/bots") {
            val u = call.requireAuth(database) ?: return@get
            if (u.role != "admin") { AdminApi.fail(call, "仅管理员", 403); return@get }
            val list = database.getWeixinBots().map { b ->
                val st = com.qitong.gateway.weixin.WeixinBotManager.statusOf(b.id)
                mapOf(
                    "id" to b.id, "name" to b.name, "enabled" to b.enabled,
                    "aiModel" to b.aiModel, "ilinkBotId" to b.ilinkBotId,
                    "systemPrompt" to (b.systemPrompt ?: ""),
                    "hasToken" to b.botToken.isNotBlank(),
                    "online" to (st.status == com.qitong.gateway.weixin.WeixinBotStatus.ONLINE),
                    "status" to st.status.name, "lastError" to st.lastError,
                    "messagesHandled" to st.messagesHandled
                )
            }
            AdminApi.ok(call, list, "ok")
        }
        get("/api/weixin/qrcode") {
            val u = call.requireAuth(database) ?: return@get
            if (u.role != "admin") { AdminApi.fail(call, "仅管理员", 403); return@get }
            try {
                val (qrcode, url) = com.qitong.gateway.weixin.WeixinBotManager.getQrCode()
                AdminApi.ok(call, mapOf("qrcode" to qrcode, "qrcodeUrl" to url), "二维码已生成（5分钟内有效）")
            } catch (e: Exception) {
                AdminApi.fail(call, "二维码获取失败：${e.message}", 500)
            }
        }
        // ★ v69b 扫码确认闭环：轮询直到微信 bot 扫码 confirmed，自动保存并启动
        post("/api/weixin/qrcode/confirm") {
            val u = call.requireAuth(database) ?: return@post
            if (u.role != "admin") { AdminApi.fail(call, "仅管理员", 403); return@post }
            val body = call.receive<JsonObject>()
            val qrcode = body["qrcode"]?.jsonPrimitive?.content?.trim().orEmpty()
            if (qrcode.isBlank()) { AdminApi.fail(call, "缺少 qrcode 参数", 400); return@post }
            // 长轮询最多 5 分钟：用协程跑避免阻塞请求线程（简化：直接同步最多 35s 轮询几轮，前端定时刷新）
            AdminApi.ok(call, mapOf("result" to com.qitong.gateway.weixin.WeixinBotManager.confirmQrLogin(qrcode)), "ok")
        }
        post("/api/weixin/bots") {
            val u = call.requireAuth(database) ?: return@post
            if (u.role != "admin") { AdminApi.fail(call, "仅管理员", 403); return@post }
            val body = call.receive<JsonObject>()
            val id = body["id"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0
            // ★ v70 编辑保存不覆盖 token：botToken 为空且已存在 → 保留原 token（防止编辑后机器人死）
            val old = if (id > 0) database.getWeixinBotById(id) else null
            val newToken = body["botToken"]?.jsonPrimitive?.content ?: ""
            val botToken = if (newToken.isBlank() && old != null) old.botToken else newToken
            val bot = com.qitong.gateway.weixin.WeixinBot(
                id = id,
                name = body["name"]?.jsonPrimitive?.content ?: "微信机器人",
                botToken = botToken,
                ilinkBotId = body["ilinkBotId"]?.jsonPrimitive?.content ?: old?.ilinkBotId ?: "",
                enabled = body["enabled"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: (old?.enabled ?: true),
                aiModel = body["aiModel"]?.jsonPrimitive?.content ?: "qtai-sj",
                systemPrompt = body["systemPrompt"]?.jsonPrimitive?.content ?: ""
            )
            database.saveWeixinBot(bot)
            // ★ v70 保存后取真实 id（不能用 name 匹配——重名会取错）
            val saved = if (id > 0) {
                database.getWeixinBotById(id)
            } else {
                database.getWeixinBots().lastOrNull { it.ilinkBotId == bot.ilinkBotId && it.name == bot.name }
            } ?: bot
            if (saved.enabled && saved.botToken.isNotBlank()) com.qitong.gateway.weixin.WeixinBotManager.startBot(saved)
            else com.qitong.gateway.weixin.WeixinBotManager.stopBot(saved.id)
            AdminApi.ok(call, mapOf("id" to saved.id, "token" to saved.botToken.isNotBlank()), "微信机器人已保存")
        }
        delete("/api/weixin/bots/{id}") {
            val u = call.requireAuth(database) ?: return@delete
            if (u.role != "admin") { AdminApi.fail(call, "仅管理员", 403); return@delete }
            val id = call.parameters["id"]?.toLongOrNull() ?: return@delete
            com.qitong.gateway.weixin.WeixinBotManager.stopBot(id)
            database.deleteWeixinBot(id)
            AdminApi.ok(call, null, "已删除")
        }
        post("/api/weixin/bots/{id}/restart") {
            val u = call.requireAuth(database) ?: return@post
            if (u.role != "admin") { AdminApi.fail(call, "仅管理员", 403); return@post }
            val id = call.parameters["id"]?.toLongOrNull() ?: return@post
            com.qitong.gateway.weixin.WeixinBotManager.restartBot(id)
            AdminApi.ok(call, null, "已重连")
        }
        // ★ v69e 启停切换
        post("/api/weixin/bots/{id}/toggle") {
            val u = call.requireAuth(database) ?: return@post
            if (u.role != "admin") { AdminApi.fail(call, "仅管理员", 403); return@post }
            val id = call.parameters["id"]?.toLongOrNull() ?: return@post
            val bot = database.getWeixinBotById(id) ?: run { AdminApi.fail(call, "机器人不存在", 404); return@post }
            val nowOn = !bot.enabled
            database.saveWeixinBot(bot.copy(enabled = nowOn))
            if (nowOn) com.qitong.gateway.weixin.WeixinBotManager.startBot(bot.copy(enabled = true))
            else com.qitong.gateway.weixin.WeixinBotManager.stopBot(id)
            AdminApi.ok(call, mapOf("enabled" to nowOn), if (nowOn) "已启用" else "已停用")
        }
        get("/api/weixin/logs") {
            val u = call.requireAuth(database) ?: return@get
            if (u.role != "admin") { AdminApi.fail(call, "仅管理员", 403); return@get }
            AdminApi.ok(call, database.getWeixinLogs(limit = 200), "ok")
        }
        // ★ v72 微信动态概览（对齐 QQ overview）
        get("/api/weixin/overview") {
            val u = call.requireAuth(database) ?: return@get
            if (u.role != "admin") { AdminApi.fail(call, "仅管理员", 403); return@get }
            val bots = database.getWeixinBots()
            val totalMsgs = com.qitong.gateway.weixin.WeixinBotManager.allStatus().values.sumOf { it.messagesHandled }
            val onlineCount = com.qitong.gateway.weixin.WeixinBotManager.allStatus().values.count { it.status == com.qitong.gateway.weixin.WeixinBotStatus.ONLINE }
            AdminApi.ok(call, mapOf(
                "bots" to bots.map { b ->
                    val st = com.qitong.gateway.weixin.WeixinBotManager.statusOf(b.id)
                    mapOf("id" to b.id, "name" to b.name, "online" to (st.status == com.qitong.gateway.weixin.WeixinBotStatus.ONLINE),
                        "status" to st.status.name, "lastError" to st.lastError, "messagesHandled" to st.messagesHandled)
                },
                "overview" to mapOf("totalMessages" to totalMsgs, "onlineBots" to onlineCount, "totalBots" to bots.size)
            ), "ok")
        }
        // ★ v72 微信日志单条删除
        delete("/api/weixin/logs/{id}") {
            val u = call.requireAuth(database) ?: return@delete
            if (u.role != "admin") { AdminApi.fail(call, "仅管理员", 403); return@delete }
            val id = call.parameters["id"]?.toLongOrNull() ?: return@delete
            database.deleteWeixinLog(id)
            AdminApi.ok(call, null, "日志已删除")
        }
        // ★ v72 微信日志清空
        post("/api/weixin/logs/clear") {
            val u = call.requireAuth(database) ?: return@post
            if (u.role != "admin") { AdminApi.fail(call, "仅管理员", 403); return@post }
            database.clearWeixinLogs()
            AdminApi.ok(call, null, "日志已清空")
        }
        // QQ 动态概览（流动面板数据源）
        get("/api/qq/overview") {
            val u = call.requireAuth(database) ?: return@get
            if (u.role != "admin") { AdminApi.fail(call, "仅管理员", 403); return@get }
            val bots = database.getQqBots().map { b ->
                val st = com.qitong.gateway.qq.QqBotManager.statusOf(b["id"] as Long)
                mapOf("id" to b["id"], "name" to b["name"], "appid" to b["appid"],
                    "aiModel" to (b["aiModel"] ?: "qtai-sj"),
                    "systemPrompt" to (b["systemPrompt"] ?: ""),
                    "welcome" to (b["welcome"] ?: ""),
                    "online" to (st.status == com.qitong.gateway.qq.QqBotStatus.ONLINE),
                    "messagesHandled" to st.messagesHandled, "lastError" to st.lastError)
            }
            val ov = database.qqOverview()
            val recent = database.getQqLogs(limit = 30)
            AdminApi.ok(call, mapOf("overview" to ov, "bots" to bots, "recent" to recent), "ok")
        }
        // ===== 沙盒 Linux 终端（临时会话，仅管理员） =====
        get("/api/terminal/sessions") {
            val u = call.requireAuth(database) ?: return@get
            if (u.role != "admin") { AdminApi.fail(call, "仅管理员", 403); return@get }
            val list = com.qitong.gateway.http.TerminalManager.list().map { s ->
                mapOf("id" to s.id, "label" to s.label, "createdAt" to s.createdAt, "lastActiveAt" to s.lastActiveAt, "commands" to s.commands, "output" to s.output.toString(), "ttlMinutes" to s.ttlMinutes)
            }
            AdminApi.ok(call, list, "ok")
        }
        post("/api/terminal/create") {
            val u = call.requireAuth(database) ?: return@post
            if (u.role != "admin") { AdminApi.fail(call, "仅管理员", 403); return@post }
            val body = call.receive<JsonObject>()
            val label = body["label"]?.jsonPrimitive?.content?.trim().orEmpty()
            val ttl = body["ttlMinutes"]?.jsonPrimitive?.content?.toIntOrNull() ?: 30
            val s = com.qitong.gateway.http.TerminalManager.create(label, ttl)
            val ttlTxt = if (ttl == 0) "永久" else "${ttl} 分钟"
            val msg = if (ttl == 0) "终端已创建（永久会话，不会自动清理）" else "终端已创建（$ttlTxt 无操作自动清理）"
            AdminApi.ok(call, mapOf("id" to s.id, "label" to s.label, "ttlMinutes" to s.ttlMinutes), msg)
        }
        // 调整终端会话时长（0=永久；>0=分钟）
        post("/api/terminal/set-ttl") {
            val u = call.requireAuth(database) ?: return@post
            if (u.role != "admin") { AdminApi.fail(call, "仅管理员", 403); return@post }
            val body = call.receive<JsonObject>()
            val id = body["id"]?.jsonPrimitive?.content.orEmpty()
            val ttl = body["ttlMinutes"]?.jsonPrimitive?.content?.toIntOrNull() ?: 30
            if (id.isBlank()) { AdminApi.fail(call, "会话ID无效", 400); return@post }
            if (com.qitong.gateway.http.TerminalManager.setTtl(id, ttl)) {
                AdminApi.ok(call, mapOf("ttlMinutes" to ttl), if (ttl == 0) "已设为永久会话（不会自动清理）" else "已设为 $ttl 分钟无操作自动清理")
            } else AdminApi.fail(call, "会话不存在", 404)
        }
        // AI 智能操作终端：自然语言 -> 大模型生成命令 -> 执行
        post("/api/terminal/ai-exec") {
            val u = call.requireAuth(database) ?: return@post
            if (u.role != "admin") { AdminApi.fail(call, "仅管理员", 403); return@post }
            val body = call.receive<JsonObject>()
            val req = body["req"]?.jsonPrimitive?.content?.trim().orEmpty()
            val id = body["id"]?.jsonPrimitive?.content?.trim().orEmpty()
            if (req.isBlank()) { AdminApi.fail(call, "请描述你的需求", 400); return@post }
            // 1) 创建或复用会话
            val sessionId = if (id.isNotBlank() && com.qitong.gateway.http.TerminalManager.get(id) != null) id
                else com.qitong.gateway.http.TerminalManager.create("AI终端-" + u.username).id
            // 2) 大模型把自然语言转成 Linux 命令（网关本机 /v1/chat/completions）
            val cmd = com.qitong.gateway.http.AiTermHelper.genCommand(req)
            if (cmd.isBlank()) { AdminApi.fail(call, "AI 无法生成命令，换个说法试试", 400); return@post }
            // 3) 危险拦截
            val dangerous = listOf("rm -rf /", "mkfs", "dd if=", "shutdown", "reboot", ":(){", "format", "fdisk")
            if (dangerous.any { cmd.contains(it) }) { AdminApi.fail(call, "⛔ 生成命令涉及危险操作已拦截", 400); return@post }
            // 4) 执行
            val (ok, out) = com.qitong.gateway.http.TerminalManager.exec(sessionId, cmd)
            if (!ok) { AdminApi.fail(call, out, 400); return@post }
            database.addOpLog(u.id, u.username, "AI终端", "需求:$req -> 命令:$cmd", call.request.local.remoteHost)
            AdminApi.ok(call, mapOf("cmd" to cmd, "sessionId" to sessionId, "output" to out), "ok")
        }
        post("/api/terminal/exec") {
            val u = call.requireAuth(database) ?: return@post
            if (u.role != "admin") { AdminApi.fail(call, "仅管理员", 403); return@post }
            val body = call.receive<JsonObject>()
            val id = body["id"]?.jsonPrimitive?.content.orEmpty()
            val cmd = body["cmd"]?.jsonPrimitive?.content.orEmpty()
            if (id.isBlank()) { AdminApi.fail(call, "会话ID无效", 400); return@post }
            val (ok, out) = com.qitong.gateway.http.TerminalManager.exec(id, cmd)
            if (!ok) { AdminApi.fail(call, out, 400); return@post }
            database.addOpLog(u.id, u.username, "终端执行", "会话$id 执行: $cmd", call.request.local.remoteHost)
            AdminApi.ok(call, mapOf("output" to out), "ok")
        }
        post("/api/terminal/close") {
            val u = call.requireAuth(database) ?: return@post
            if (u.role != "admin") { AdminApi.fail(call, "仅管理员", 403); return@post }
            val body = call.receive<JsonObject>()
            val id = body["id"]?.jsonPrimitive?.content.orEmpty()
            val closed = if (id.isBlank()) { com.qitong.gateway.http.TerminalManager.closeAll(); true } else com.qitong.gateway.http.TerminalManager.close(id)
            AdminApi.ok(call, null, if (closed) "终端已关闭" else "会话不存在")
        }
        // ===== 技能库（独立管理界面：触发器 -> 动作） =====
        get("/api/skills") {
            val u = call.requireAuth(database) ?: return@get
            AdminApi.ok(call, database.getSkills(if (u.role == "admin") 0 else u.id), "ok")
        }
        post("/api/skills") {
            val u = call.requireAuth(database) ?: return@post
            if (u.role != "admin" && !AdminApi.hasPerm(u, AdminApi.Perm.SYS_SPEED)) { AdminApi.fail(call, "仅管理员可管理技能", 403); return@post }
            val body = call.receive<JsonObject>()
            val id = body["id"]?.jsonPrimitive?.content?.toLongOrNull()
            val name = body["name"]?.jsonPrimitive?.content?.trim().orEmpty()
            val trigger = body["trigger"]?.jsonPrimitive?.content?.trim().orEmpty()
            val matchType = body["matchType"]?.jsonPrimitive?.content ?: "exact"
            val action = body["action"]?.jsonPrimitive?.content ?: "skill"
            val content = body["content"]?.jsonPrimitive?.content ?: ""
            val enabled = body["enabled"]?.jsonPrimitive?.content?.toBoolean() ?: true
            // v48：技能启停切换时只传 id+enabled（name/trigger 可为空）
            if (id == null && name.isBlank() && trigger.isBlank()) { AdminApi.fail(call, "名称或触发词不能为空", 400); return@post }
            val sid = database.saveSkill(id, name, trigger, matchType, action, content, enabled, if (u.role == "admin") 0 else u.id)
            AdminApi.ok(call, mapOf("id" to sid), "技能已保存")
        }
        delete("/api/skills/{id}") {
            val u = call.requireAuth(database) ?: return@delete
            val id = call.parameters["id"]?.toLongOrNull() ?: return@delete
            database.deleteSkill(id)
            AdminApi.ok(call, null, "技能已删除")
        }
        // 测试技能执行
        post("/api/skills/run") {
            val u = call.requireAuth(database) ?: return@post
            val body = call.receive<JsonObject>()
            val skillId = body["id"]?.jsonPrimitive?.content?.toLongOrNull() ?: run { AdminApi.fail(call, "技能ID无效", 400); return@post }
            val skill = database.getSkills(0).firstOrNull { it["id"] as Long == skillId }
                ?: run { AdminApi.fail(call, "技能不存在", 404); return@post }
            val result = com.qitong.gateway.http.WorkflowEngine.runStep(database, skill["action"] as? String ?: "reply", skill["content"] as? String ?: "")
            AdminApi.ok(call, mapOf("result" to result), "ok")
        }

        // ===== MCP 服务器管理（独立界面：对接外部 MCP，编辑/删除/启停） =====
        get("/api/mcp") {
            val u = call.requireAuth(database) ?: return@get
            if (u.role != "admin") { AdminApi.fail(call, "仅管理员", 403); return@get }
            AdminApi.ok(call, database.getMcpConfigs(), "ok")
        }
        post("/api/mcp") {
            val u = call.requireAuth(database) ?: return@post
            if (u.role != "admin") { AdminApi.fail(call, "仅管理员", 403); return@post }
            val body = call.receive<JsonObject>()
            val id = body["id"]?.jsonPrimitive?.content?.toLongOrNull()
            val name = body["name"]?.jsonPrimitive?.content?.trim().orEmpty()
            val serverType = body["serverType"]?.jsonPrimitive?.content ?: "http"
            val url = body["url"]?.jsonPrimitive?.content?.trim().orEmpty()
            val authToken = body["authToken"]?.jsonPrimitive?.content.orEmpty()
            val enabled = body["enabled"]?.jsonPrimitive?.content?.toBoolean() ?: true
            if (name.isBlank() || url.isBlank()) { AdminApi.fail(call, "名称和URL必填", 400); return@post }
            val mid = database.saveMcpConfig(id, name, serverType, url, authToken, enabled)
            AdminApi.ok(call, mapOf("id" to mid), "MCP已保存")
        }
        delete("/api/mcp/{id}") {
            val u = call.requireAuth(database) ?: return@delete
            if (u.role != "admin") { AdminApi.fail(call, "仅管理员", 403); return@delete }
            val id = call.parameters["id"]?.toLongOrNull() ?: return@delete
            database.deleteMcpConfig(id)
            AdminApi.ok(call, null, "MCP已删除")
        }
        // 测试 MCP 连接
        post("/api/mcp/test") {
            val u = call.requireAuth(database) ?: return@post
            if (u.role != "admin") { AdminApi.fail(call, "仅管理员", 403); return@post }
            val body = call.receive<JsonObject>()
            val url = body["url"]?.jsonPrimitive?.content?.trim().orEmpty()
            if (url.isBlank()) { AdminApi.fail(call, "URL必填", 400); return@post }
            val ok = try {
                val req = okhttp3.Request.Builder().url(url).get().build()
                okhttp3.OkHttpClient.Builder().connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS).readTimeout(15, java.util.concurrent.TimeUnit.SECONDS).build().newCall(req).execute().use { it.isSuccessful }
            } catch (e: Exception) { false }
            AdminApi.ok(call, mapOf("ok" to ok), if (ok) "MCP 连接正常" else "MCP 连接失败")
        }
        // ★ v48 MCP 工具列表：握手后列出该服务器可调用的所有工具（对齐 Kai 展开）
        get("/api/mcp/{id}/tools") {
            val u = call.requireAuth(database) ?: return@get
            if (u.role != "admin") { AdminApi.fail(call, "仅管理员", 403); return@get }
            val id = call.parameters["id"]?.toLongOrNull() ?: return@get
            val cfg = database.getMcpConfigs().firstOrNull { (it["id"] as? Number)?.toLong() == id }
                ?: run { AdminApi.fail(call, "MCP不存在", 404); return@get }
            val server = com.qitong.gateway.sandbox.McpClient.McpServer(
                id,
                (cfg["name"] as? String ?: ""),
                (cfg["serverType"] as? String ?: "http"),
                (cfg["url"] as? String ?: ""),
                (cfg["authToken"] as? String ?: "")
            )
            val toolsText = com.qitong.gateway.sandbox.McpClient.listTools(server)
            AdminApi.ok(call, mapOf("tools" to toolsText), "ok")
        }

        // ===== 工作流（可做任何事的自动化：触发条件 -> 动作序列；qtai-sj 可创建/修改/执行） =====
        get("/api/workflows") {
            val u = call.requireAuth(database) ?: return@get
            AdminApi.ok(call, database.getWorkflows(if (u.role == "admin") 0 else u.id), "ok")
        }
        post("/api/workflows") {
            val u = call.requireAuth(database) ?: return@post
            val body = call.receive<JsonObject>()
            val id = body["id"]?.jsonPrimitive?.content?.toLongOrNull()
            val name = body["name"]?.jsonPrimitive?.content?.trim().orEmpty()
            val description = body["description"]?.jsonPrimitive?.content.orEmpty()
            val triggerType = body["triggerType"]?.jsonPrimitive?.content ?: "manual"
            val triggerText = body["triggerText"]?.jsonPrimitive?.content.orEmpty()
            val steps = body["steps"]?.jsonPrimitive?.content ?: "[]"
            val enabled = body["enabled"]?.jsonPrimitive?.content?.toBoolean() ?: true
            if (name.isBlank()) { AdminApi.fail(call, "工作流名称必填", 400); return@post }
            val wid = database.saveWorkflow(id, name, description, triggerType, triggerText, steps, enabled, if (u.role == "admin") 0 else u.id)
            AdminApi.ok(call, mapOf("id" to wid), "工作流已保存")
        }
        delete("/api/workflows/{id}") {
            val u = call.requireAuth(database) ?: return@delete
            val id = call.parameters["id"]?.toLongOrNull() ?: return@delete
            database.deleteWorkflow(id)
            AdminApi.ok(call, null, "工作流已删除")
        }
        // 执行工作流（逐步执行 steps JSON 数组）
        post("/api/workflows/run") {
            val u = call.requireAuth(database) ?: return@post
            val body = call.receive<JsonObject>()
            val wid = body["id"]?.jsonPrimitive?.content?.toLongOrNull() ?: run { AdminApi.fail(call, "工作流ID无效", 400); return@post }
            val wf = database.getWorkflows(0).firstOrNull { it["id"] as Long == wid }
                ?: run { AdminApi.fail(call, "工作流不存在", 404); return@post }
            val stepsJson = try { org.json.JSONArray(wf["steps"] as? String ?: "[]") } catch (e: Exception) { org.json.JSONArray() }
            val results = mutableListOf<Map<String, Any?>>()
            for (i in 0 until stepsJson.length()) {
                val step = stepsJson.optJSONObject(i) ?: continue
                val stepType = step.optString("type", "reply")
                val stepContent = step.optString("content", "")
                val out = com.qitong.gateway.http.WorkflowEngine.runStep(database, stepType, stepContent)
                results.add(mapOf("index" to i, "type" to stepType, "output" to out))
            }
            database.addOpLog(u.id, u.username, "工作流", "执行 ${wf["name"]}：${results.size}步", call.request.local.remoteHost)
            AdminApi.ok(call, mapOf("results" to results), "工作流执行完成")
        }

        // ===== 群级自动化（单群独立：自定义提醒/自动化任务） =====
        get("/api/qq/automation") {
            val u = call.requireAuth(database) ?: return@get
            if (u.role != "admin") { AdminApi.fail(call, "仅管理员", 403); return@get }
            val groupOpenid = call.queryParameters["group"]
            AdminApi.ok(call, database.getGroupAutomation(groupOpenid), "ok")
        }
        post("/api/qq/automation") {
            val u = call.requireAuth(database) ?: return@post
            if (u.role != "admin") { AdminApi.fail(call, "仅管理员", 403); return@post }
            val body = call.receive<JsonObject>()
            val id = body["id"]?.jsonPrimitive?.content?.toLongOrNull()
            val groupOpenid = body["groupOpenid"]?.jsonPrimitive?.content?.trim().orEmpty()
            val name = body["name"]?.jsonPrimitive?.content?.trim().orEmpty()
            val type = body["type"]?.jsonPrimitive?.content ?: "reminder"
            val content = body["content"]?.jsonPrimitive?.content.orEmpty()
            val cron = body["cron"]?.jsonPrimitive?.content.orEmpty()
            val enabled = body["enabled"]?.jsonPrimitive?.content?.toBoolean() ?: true
            if (groupOpenid.isBlank() || name.isBlank()) { AdminApi.fail(call, "群和名称必填", 400); return@post }
            val aid = database.saveGroupAutomation(id, groupOpenid, name, type, content, cron, enabled)
            AdminApi.ok(call, mapOf("id" to aid), "群自动化已保存")
        }
        delete("/api/qq/automation/{id}") {
            val u = call.requireAuth(database) ?: return@delete
            if (u.role != "admin") { AdminApi.fail(call, "仅管理员", 403); return@delete }
            val id = call.parameters["id"]?.toLongOrNull() ?: return@delete
            database.deleteGroupAutomation(id)
            AdminApi.ok(call, null, "已删除")
        }

        // QQ 插件指令
        get("/api/qq/commands") {
            val u = call.requireAuth(database) ?: return@get
            if (u.role != "admin") { AdminApi.fail(call, "仅管理员", 403); return@get }
            AdminApi.ok(call, database.getQqCommands(), "ok")
        }
        post("/api/qq/commands") {
            val u = call.requireAuth(database) ?: return@post
            if (u.role != "admin") { AdminApi.fail(call, "仅管理员", 403); return@post }
            val body = call.receive<JsonObject>()
            val id = body["id"]?.jsonPrimitive?.content?.toLongOrNull()
            val name = body["name"]?.jsonPrimitive?.content?.trim().orEmpty()
            val trigger = body["trigger"]?.jsonPrimitive?.content?.trim().orEmpty()
            if (trigger.isBlank()) { AdminApi.fail(call, "触发词不能为空", 400); return@post }
            val cid = database.upsertQqCommand(
                id, name, trigger,
                body["matchType"]?.jsonPrimitive?.content ?: "exact",
                body["action"]?.jsonPrimitive?.content ?: "reply",
                body["content"]?.jsonPrimitive?.content ?: "",
                body["enabled"]?.jsonPrimitive?.content?.toBoolean() ?: true,
                body["cooldown"]?.jsonPrimitive?.content?.toIntOrNull() ?: 5,
                body["priority"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            )
            AdminApi.ok(call, mapOf("id" to cid), "已保存")
        }
        delete("/api/qq/commands/{id}") {
            val id = call.parameters["id"]?.toLongOrNull() ?: return@delete
            val u = call.requireAuth(database) ?: return@delete
            if (u.role != "admin") { AdminApi.fail(call, "仅管理员", 403); return@delete }
            database.deleteQqCommand(id)
            AdminApi.ok(call, null, "已删除")
        }
        // ===== QQ 插件包（上传 zip 安装，含名字/描述/菜单） =====
        get("/api/qq/plugins") {
            val u = call.requireAuth(database) ?: return@get
            if (u.role != "admin") { AdminApi.fail(call, "仅管理员", 403); return@get }
            AdminApi.ok(call, database.getQqPlugins(), "ok")
        }
        post("/api/qq/plugins/upload") {
            val u = call.requireAuth(database) ?: return@post
            if (u.role != "admin") { AdminApi.fail(call, "仅管理员", 403); return@post }
            val body = call.receive<JsonObject>()
            val pluginName = body["name"]?.jsonPrimitive?.content?.trim().orEmpty()
            val description = body["description"]?.jsonPrimitive?.content.orEmpty()
            val version = body["version"]?.jsonPrimitive?.content.orEmpty().ifBlank { "1.0.0" }
            val menu = body["menu"]?.jsonPrimitive?.content.orEmpty()
            val author = body["author"]?.jsonPrimitive?.content.orEmpty()
            val b64 = body["data"]?.jsonPrimitive?.content.orEmpty()
            if (pluginName.isBlank()) { AdminApi.fail(call, "插件名不能为空", 400); return@post }
            if (b64.length < 4) { AdminApi.fail(call, "压缩包内容为空", 400); return@post }
            // 解压到插件目录
            val dataDir = java.io.File(System.getenv("DATA_DIR") ?: "/data/qitong")
            val pluginDir = java.io.File(dataDir, "plugins/${pluginName.replace(Regex("[^a-zA-Z0-9_\\u4e00-\\u9fa5]"), "_")}")
            pluginDir.mkdirs()
            try {
                val clean = b64.replace(Regex("^data:.*;base64,"), "")
                val bytes = java.util.Base64.getDecoder().decode(clean)
                val zipFile = java.io.File(pluginDir, "plugin.zip")
                zipFile.writeBytes(bytes)
                // 解压 zip
                java.util.zip.ZipFile(zipFile).use { zf ->
                    val entries = zf.entries()
                    while (entries.hasMoreElements()) {
                        val e = entries.nextElement()
                        val outFile = java.io.File(pluginDir, e.name)
                        if (e.isDirectory) { outFile.mkdirs(); continue }
                        outFile.parentFile?.mkdirs()
                        zf.getInputStream(e).use { input -> outFile.outputStream().use { output -> input.copyTo(output) } }
                    }
                }
                zipFile.delete()
            } catch (e: Exception) {
                AdminApi.fail(call, "解压失败: ${e.message}", 400); return@post
            }
            database.upsertQqPlugin(pluginName, description, version, menu, author)
            database.addOpLog(u.id, u.username, "QQ插件", "安装插件 $pluginName v$version", call.request.local.remoteHost)
            AdminApi.ok(call, mapOf("name" to pluginName), "插件「$pluginName」安装成功")
        }
        delete("/api/qq/plugins/{name}") {
            val name = call.parameters["name"].orEmpty()
            val u = call.requireAuth(database) ?: return@delete
            if (u.role != "admin") { AdminApi.fail(call, "仅管理员", 403); return@delete }
            database.deleteQqPlugin(name)
            // 删除插件目录
            runCatching {
                val dataDir = java.io.File(System.getenv("DATA_DIR") ?: "/data/qitong")
                java.io.File(dataDir, "plugins/${name.replace(Regex("[^a-zA-Z0-9_\\u4e00-\\u9fa5]"), "_")}").deleteRecursively()
            }
            AdminApi.ok(call, null, "插件已删除")
        }
        // 插件启停切换
        post("/api/qq/plugins/{name}/toggle") {
            val name = call.parameters["name"].orEmpty()
            val u = call.requireAuth(database) ?: return@post
            if (u.role != "admin") { AdminApi.fail(call, "仅管理员", 403); return@post }
            val body = call.receive<JsonObject>()
            val enabled = body["enabled"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true
            database.setQqPluginEnabled(name, enabled)
            database.addOpLog(u.id, u.username, "QQ插件", if (enabled) "启用插件 $name" else "停用插件 $name", call.request.local.remoteHost)
            AdminApi.ok(call, null, if (enabled) "插件已启用" else "插件已停用")
        }
        // 插件配置更新（标题/描述/版本/菜单/作者）
        post("/api/qq/plugins/{name}/config") {
            val name = call.parameters["name"].orEmpty()
            val u = call.requireAuth(database) ?: return@post
            if (u.role != "admin") { AdminApi.fail(call, "仅管理员", 403); return@post }
            val body = call.receive<JsonObject>()
            database.updateQqPluginConfig(name,
                body["description"]?.jsonPrimitive?.content,
                body["version"]?.jsonPrimitive?.content,
                body["menu"]?.jsonPrimitive?.content,
                body["author"]?.jsonPrimitive?.content)
            database.addOpLog(u.id, u.username, "QQ插件", "更新插件配置 $name", call.request.local.remoteHost)
            AdminApi.ok(call, null, "插件配置已保存")
        }
        // 插件文件列表（游戏/菜单 txt 文件）
        get("/api/qq/plugins/{name}/files") {
            val name = call.parameters["name"].orEmpty()
            val u = call.requireAuth(database) ?: return@get
            if (u.role != "admin") { AdminApi.fail(call, "仅管理员", 403); return@get }
            val dataDir = java.io.File(System.getenv("DATA_DIR") ?: "/data/qitong")
            val pluginDir = java.io.File(dataDir, "plugins/${name.replace(Regex("[^a-zA-Z0-9_\\u4e00-\\u9fa5]"), "_")}")
            if (!pluginDir.isDirectory) { AdminApi.fail(call, "插件目录不存在", 404); return@get }
            val files = pluginDir.walkTopDown().filter { it.isFile && it.extension in setOf("txt", "json", "md") }
                .map { it.relativeTo(pluginDir).path }
                .toList().sorted()
            AdminApi.ok(call, files, "ok")
        }
        // 读取插件文件内容
        get("/api/qq/plugins/{name}/file") {
            val name = call.parameters["name"].orEmpty()
            val u = call.requireAuth(database) ?: return@get
            if (u.role != "admin") { AdminApi.fail(call, "仅管理员", 403); return@get }
            val rel = call.queryParameters["path"].orEmpty()
            val dataDir = java.io.File(System.getenv("DATA_DIR") ?: "/data/qitong")
            val pluginDir = java.io.File(dataDir, "plugins/${name.replace(Regex("[^a-zA-Z0-9_\\u4e00-\\u9fa5]"), "_")}")
            val f = java.io.File(pluginDir, rel)
            if (!f.isFile || !f.canonicalPath.startsWith(pluginDir.canonicalPath)) { AdminApi.fail(call, "文件不存在", 404); return@get }
            val content = runCatching { f.readText(Charsets.UTF_8) }
                .getOrElse { runCatching { f.readText(java.nio.charset.Charset.forName("GBK")) }.getOrDefault("") }
            AdminApi.ok(call, mapOf("path" to rel, "content" to content), "ok")
        }
        // 保存插件文件内容
        post("/api/qq/plugins/{name}/file") {
            val name = call.parameters["name"].orEmpty()
            val u = call.requireAuth(database) ?: return@post
            if (u.role != "admin") { AdminApi.fail(call, "仅管理员", 403); return@post }
            val body = call.receive<JsonObject>()
            val rel = body["path"]?.jsonPrimitive?.content.orEmpty()
            val content = body["content"]?.jsonPrimitive?.content.orEmpty()
            val dataDir = java.io.File(System.getenv("DATA_DIR") ?: "/data/qitong")
            val pluginDir = java.io.File(dataDir, "plugins/${name.replace(Regex("[^a-zA-Z0-9_\\u4e00-\\u9fa5]"), "_")}")
            val f = java.io.File(pluginDir, rel)
            if (!f.canonicalPath.startsWith(pluginDir.canonicalPath)) { AdminApi.fail(call, "非法路径", 400); return@post }
            f.parentFile?.mkdirs()
            f.writeText(content, Charsets.UTF_8)
            database.addOpLog(u.id, u.username, "QQ插件", "修改插件文件 $name/$rel", call.request.local.remoteHost)
            AdminApi.ok(call, null, "文件已保存")
        }
        // QQ 用户（独立隔离）
        get("/api/qq/users") {
            val u = call.requireAuth(database) ?: return@get
            if (u.role != "admin") { AdminApi.fail(call, "仅管理员", 403); return@get }
            AdminApi.ok(call, database.getQqUsers(), "ok")
        }
        // QQ 用户权限设置（level: 0禁止 1查询 2操作 3管理 4全部；flags细分）
        post("/api/qq/users/perm") {
            val u = call.requireAuth(database) ?: return@post
            if (u.role != "admin") { AdminApi.fail(call, "仅管理员", 403); return@post }
            val body = call.receive<JsonObject>()
            val openid = body["openid"]?.jsonPrimitive?.content?.trim().orEmpty()
            val level = body["level"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1
            val flags = body["flags"]?.jsonPrimitive?.content ?: ""
            if (openid.isBlank()) { AdminApi.fail(call, "openid 不能为空", 400); return@post }
            database.setQqUserPerm(openid, level.coerceIn(0, 4), flags)
            database.addOpLog(u.id, u.username, "QQ用户权限", "设置 $openid 权限=$level flags=$flags", call.request.local.remoteHost)
            AdminApi.ok(call, null, "权限已保存")
        }
        post("/api/qq/users/update") {
            val u = call.requireAuth(database) ?: return@post
            if (u.role != "admin") { AdminApi.fail(call, "仅管理员", 403); return@post }
            val body = call.receive<JsonObject>()
            val openid = body["openid"]?.jsonPrimitive?.content?.trim().orEmpty()
            if (openid.isBlank()) { AdminApi.fail(call, "openid 不能为空", 400); return@post }
            database.updateQqUser(openid,
                body["displayName"]?.jsonPrimitive?.content,
                body["persona"]?.jsonPrimitive?.content,
                body["aiEnabled"]?.jsonPrimitive?.content?.toBooleanStrictOrNull())
            AdminApi.ok(call, null, "用户已更新")
        }
        // QQ 运行日志
        get("/api/qq/logs") {
            val u = call.requireAuth(database) ?: return@get
            if (u.role != "admin") { AdminApi.fail(call, "仅管理员", 403); return@get }
            val type = call.queryParameters["type"]
            AdminApi.ok(call, database.getQqLogs(limit = 300, type = type), "ok")
        }
        delete("/api/qq/logs/{id}") {
            val u = call.requireAuth(database) ?: return@delete
            if (u.role != "admin") { AdminApi.fail(call, "仅管理员", 403); return@delete }
            val id = call.parameters["id"]?.toLongOrNull() ?: return@delete
            database.deleteQqLog(id)
            AdminApi.ok(call, null, "日志已删除")
        }
        post("/api/qq/logs/clear") {
            val u = call.requireAuth(database) ?: return@post
            if (u.role != "admin") { AdminApi.fail(call, "仅管理员", 403); return@post }
            database.clearQqLogs()
            AdminApi.ok(call, null, "日志已清空")
        }
        // ★ 按群清理聊天记录（群聊天记录单独管理）
        post("/api/qq/logs/clear-group") {
            val u = call.requireAuth(database) ?: return@post
            if (u.role != "admin") { AdminApi.fail(call, "仅管理员", 403); return@post }
            val body = call.receive<JsonObject>()
            val groupOpenid = body["groupOpenid"]?.jsonPrimitive?.content.orEmpty()
            if (groupOpenid.isBlank()) { AdminApi.fail(call, "群不能为空", 400); return@post }
            database.clearQqLogsByGroup(groupOpenid)
            AdminApi.ok(call, null, "该群聊天记录已清空")
        }
        // ★ 用户记忆管理：查某用户记忆明细（带 id）/ 删除单条 / 清空
        get("/api/qq/users/memory/detail") {
            val u = call.requireAuth(database) ?: return@get
            if (u.role != "admin") { AdminApi.fail(call, "仅管理员", 403); return@get }
            val openid = call.queryParameters["openid"].orEmpty()
            AdminApi.ok(call, database.getQqBrainMemoryItems(openid, limit = 100), "ok")
        }
        delete("/api/qq/users/memory/{id}") {
            val u = call.requireAuth(database) ?: return@delete
            if (u.role != "admin") { AdminApi.fail(call, "仅管理员", 403); return@delete }
            val id = call.parameters["id"]?.toLongOrNull() ?: return@delete
            database.deleteQqBrainMemoryById(id)
            AdminApi.ok(call, null, "该条记忆已删除")
        }
        // QQ 积分排行/调整（按群隔离）
        get("/api/qq/points") {
            val u = call.requireAuth(database) ?: return@get
            if (u.role != "admin") { AdminApi.fail(call, "仅管理员", 403); return@get }
            val groupOpenid = call.queryParameters["group"].orEmpty()
            AdminApi.ok(call, database.getAllQqPoints(groupOpenid), "ok")
        }
        post("/api/qq/points/adjust") {
            val u = call.requireAuth(database) ?: return@post
            if (u.role != "admin") { AdminApi.fail(call, "仅管理员", 403); return@post }
            val body = call.receive<JsonObject>()
            val openid = body["openid"]?.jsonPrimitive?.content?.trim().orEmpty()
            val delta = body["delta"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            val groupOpenid = body["groupOpenid"]?.jsonPrimitive?.content ?: ""
            if (openid.isBlank()) { AdminApi.fail(call, "openid 不能为空", 400); return@post }
            database.adjustQqPoints(openid, delta, groupOpenid)
            AdminApi.ok(call, null, "已调整 $delta 积分")
        }
        // QQ 用户长期记忆
        get("/api/qq/users/memory") {
            val u = call.requireAuth(database) ?: return@get
            if (u.role != "admin") { AdminApi.fail(call, "仅管理员", 403); return@get }
            val openid = call.queryParameters["openid"].orEmpty()
            AdminApi.ok(call, database.getQqBrainMemories(openid, limit = 50), "ok")
        }
        post("/api/qq/users/memory/clear") {
            val u = call.requireAuth(database) ?: return@post
            if (u.role != "admin") { AdminApi.fail(call, "仅管理员", 403); return@post }
            val body = call.receive<JsonObject>()
            val openid = body["openid"]?.jsonPrimitive?.content?.trim().orEmpty()
            database.clearQqBrainMemories(openid)
            AdminApi.ok(call, null, "记忆已清空")
        }

        // 用户（admin=全部；代理=自己的下级用户；普通用户无权限）
        get("/api/users") {
            val u = call.requireAuth(database) ?: return@get
            // 代理自动拥有管理自己下级的权限（即使未显式配置 users.manage）
            val isAgentOrAdmin = u.role == "admin" || u.role == "agent"
            if (!isAgentOrAdmin && !AdminApi.hasPerm(u, AdminApi.Perm.U_MANAGE)) AdminApi.fail(call, "无权限", 403)
            else {
                // 代理：只看自己的下级；管理员：看全部
                val users = if (u.role == "admin") AdminApi.getUsers(database)
                    else if (u.role == "agent") AdminApi.getUsersByInviter(database, u.id)
                    else emptyList()
                AdminApi.ok(call, users, "ok")
            }
        }
        // 编辑用户（昵称/角色/额度/绑定模型/权限/重置密码；管理员=全部，代理=自己的下级）
        post("/api/users/update") {
            val u = call.requireAuth(database) ?: return@post
            if (u.role != "admin" && u.role != "agent" && !AdminApi.hasPerm(u, AdminApi.Perm.U_MANAGE)) { AdminApi.fail(call, "无权限", 403); return@post }
            val body = call.receive<JsonObject>()
            val userId = body["id"]?.jsonPrimitive?.content?.toLongOrNull() ?: return@post
            val user = database.getUserById(userId) ?: run { AdminApi.fail(call, "用户不存在", 404); return@post }
            // 代理只能管理自己的下级
            if (u.role != "admin" && !database.isSubordinate(u.id, userId)) { AdminApi.fail(call, "只能管理自己的下级用户", 403); return@post }
            // 代理不能把下级改成代理/管理员（权限隔离）
            val newRole = body["role"]?.jsonPrimitive?.content ?: user.role
            if (u.role != "admin" && (newRole == "admin" || newRole == "agent")) { AdminApi.fail(call, "无权变更角色", 403); return@post }
            val updated = user.copy(
                displayName = body["displayName"]?.jsonPrimitive?.content ?: user.displayName,
                role = body["role"]?.jsonPrimitive?.content ?: user.role,
                quotaLimit = body["quotaLimit"]?.jsonPrimitive?.content?.toLongOrNull() ?: user.quotaLimit,
                quotaUsed = body["quotaUsed"]?.jsonPrimitive?.content?.toLongOrNull() ?: user.quotaUsed,
                bindModels = body["bindModels"]?.let {
                    try { kotlinx.serialization.json.Json.decodeFromString<List<String>>(it.toString()) } catch (_: Exception) { user.bindModels }
                } ?: user.bindModels,
                permissions = body["permissions"]?.let {
                    try { kotlinx.serialization.json.Json.decodeFromString<List<String>>(it.toString()) } catch (_: Exception) { user.permissions }
                } ?: user.permissions
            )
            database.updateUser(updated)
            // 可选重置密码
            body["newPassword"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }?.let { np ->
                if (np.length >= 6) {
                    database.updateUserPassword(userId, org.mindrot.jbcrypt.BCrypt.hashpw(np, org.mindrot.jbcrypt.BCrypt.gensalt()))
                }
            }
            AdminApi.ok(call, null, "用户已更新")
        }
        // 删除用户（管理员=全部；代理=自己的下级）
        post("/api/users/delete") {
            val u = call.requireAuth(database) ?: return@post
            if (u.role != "admin" && u.role != "agent" && !AdminApi.hasPerm(u, AdminApi.Perm.U_MANAGE)) { AdminApi.fail(call, "无权限", 403); return@post }
            val body = call.receive<JsonObject>()
            val userId = body["id"]?.jsonPrimitive?.content?.toLongOrNull() ?: return@post
            if (userId == u.id) { AdminApi.fail(call, "不能删除自己", 400); return@post }
            // 代理只能删自己的下级
            if (u.role != "admin" && !database.isSubordinate(u.id, userId)) { AdminApi.fail(call, "只能管理自己的下级用户", 403); return@post }
            database.deleteUser(userId)
            AdminApi.ok(call, null, "用户已删除")
        }
        // 管理员直接创建用户（可指定角色/初始余额/额度）
        post("/api/users/create") {
            val u = call.requireAuth(database) ?: return@post
            if (u.role != "admin") { AdminApi.fail(call, "仅管理员可直接开通账号", 403); return@post }
            val body = call.receive<JsonObject>()
            val uname = body["username"]?.jsonPrimitive?.content?.trim().orEmpty()
            val pwd = body["password"]?.jsonPrimitive?.content?.orEmpty() ?: ""
            val displayName = body["displayName"]?.jsonPrimitive?.content?.trim().orEmpty()
            val role = body["role"]?.jsonPrimitive?.content ?: "user"
            val quotaLimit = body["quotaLimit"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
            val balance = body["balance"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
            if (uname.length < 3) { AdminApi.fail(call, "用户名至少3个字符", 400); return@post }
            if (pwd.length < 6) { AdminApi.fail(call, "密码至少6个字符", 400); return@post }
            if (database.getUserByUsername(uname) != null) { AdminApi.fail(call, "用户名已存在", 400); return@post }
            val reg = AuthManager.register(database, uname, pwd, displayName, "")
            if (reg.isFailure) { AdminApi.fail(call, reg.exceptionOrNull()?.message ?: "创建失败", 400); return@post }
            val newUser = database.getUserByUsername(uname)
            if (newUser != null) {
                database.updateUser(newUser.copy(role = role, quotaLimit = quotaLimit))
                if (balance > 0) database.rechargeBalance(newUser.id, balance)
                database.addOpLog(u.id, u.username, "开通账号", "为 $uname 开通账号（角色=$role，余额¥$balance）", call.request.local.remoteHost)
            }
            AdminApi.ok(call, null, "账号已创建")
        }
        // 用户充值（admin / 代理自己的下级）
        post("/api/users/recharge") {
            val u = call.requireAuth(database) ?: return@post
            if (u.role != "admin" && u.role != "agent" && !AdminApi.hasPerm(u, AdminApi.Perm.U_MANAGE)) { AdminApi.fail(call, "无权限", 403); return@post }
            val body = call.receive<JsonObject>()
            val userId = body["id"]?.jsonPrimitive?.content?.toLongOrNull() ?: run { AdminApi.fail(call, "用户ID无效", 400); return@post }
            // 代理只能给自己的下级充值
            if (u.role != "admin" && !database.isSubordinate(u.id, userId)) { AdminApi.fail(call, "只能管理自己的下级用户", 403); return@post }
            val amount = body["amount"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: run { AdminApi.fail(call, "金额无效", 400); return@post }
            if (amount <= 0) { AdminApi.fail(call, "金额必须大于0", 400); return@post }
            if (database.rechargeBalance(userId, amount)) {
                val bal = database.getUserBalance(userId)
                val target = database.getUserById(userId)
                database.addOpLog(u.id, u.username, "用户充值", "给 ${target?.username ?: "用户$userId"} 充值 ¥$amount（当前余额 ¥$bal）", call.request.local.remoteHost)
                AdminApi.ok(call, mapOf("balance" to bal), "充值成功，当前余额 ¥$bal")
            } else AdminApi.fail(call, "充值失败", 400)
        }
        // 管理员手动扣款（admin / 代理自己的下级）
        post("/api/users/deduct") {
            val u = call.requireAuth(database) ?: return@post
            if (u.role != "admin" && u.role != "agent" && !AdminApi.hasPerm(u, AdminApi.Perm.U_MANAGE)) { AdminApi.fail(call, "无权限", 403); return@post }
            val body = call.receive<JsonObject>()
            val userId = body["id"]?.jsonPrimitive?.content?.toLongOrNull() ?: run { AdminApi.fail(call, "用户ID无效", 400); return@post }
            // 代理只能给自己的下级扣款
            if (u.role != "admin" && !database.isSubordinate(u.id, userId)) { AdminApi.fail(call, "只能管理自己的下级用户", 403); return@post }
            val amount = body["amount"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: run { AdminApi.fail(call, "金额无效", 400); return@post }
            if (amount <= 0) { AdminApi.fail(call, "金额必须大于0", 400); return@post }
            if (database.deductBalanceAdmin(userId, amount)) {
                val bal = database.getUserBalance(userId)
                val target = database.getUserById(userId)
                database.addOpLog(u.id, u.username, "用户扣款", "给 ${target?.username ?: "用户$userId"} 扣款 ¥$amount（当前余额 ¥$bal）", call.request.local.remoteHost)
                AdminApi.ok(call, mapOf("balance" to bal), "已扣款 ¥$amount，当前余额 ¥$bal")
            } else AdminApi.fail(call, "扣款失败", 400)
        }
        // 我的分销信息（邀请码/邀请人数/累计佣金）——空码自动生成并持久化
        get("/api/me/distribution") {
            val u = call.requireAuth(database) ?: return@get
            val inviteCount = database.getInvitedCount(u.id)
            val inviteCode = database.ensureInviteCode(u.id)
            AdminApi.ok(call, mapOf(
                "inviteCode" to inviteCode,
                "inviteCount" to inviteCount,
                "commissionRate" to (u.commissionRate * 100),
                "balance" to u.balance,
                "totalRecharge" to u.totalRecharge
            ), "ok")
        }
        // 当前用户余额查询
        get("/api/me/balance") {
            val u = call.requireAuth(database) ?: return@get
            AdminApi.ok(call, mapOf(
                "balance" to u.balance,
                "totalRecharge" to u.totalRecharge,
                "quotaLimit" to u.quotaLimit,
                "quotaUsed" to u.quotaUsed
            ), "ok")
        }
        // 当前用户余额账单（充值/扣费/返佣流水）
        get("/api/me/balance-logs") {
            val u = call.requireAuth(database) ?: return@get
            AdminApi.ok(call, database.getBalanceLogs(u.id), "ok")
        }
        // 个人中心：我的资料（邮箱/昵称/通知开关）
        get("/api/me/profile") {
            val u = call.requireAuth(database) ?: return@get
            val inviteCode = database.ensureInviteCode(u.id)  // 空码自动生成持久化
            AdminApi.ok(call, mapOf(
                "id" to u.id,
                "username" to u.username,
                "displayName" to u.displayName,
                "role" to u.role,
                "email" to u.email,
                "notifyEnabled" to u.notifyEnabled,
                "balance" to u.balance,
                "totalRecharge" to u.totalRecharge,
                "inviteCode" to inviteCode,
                "createdAt" to u.createdAt
            ), "ok")
        }
        // 个人中心：更新邮箱/昵称/通知开关
        post("/api/me/profile") {
            val u = call.requireAuth(database) ?: return@post
            val body = call.receive<JsonObject>()
            val email = body["email"]?.jsonPrimitive?.content ?: u.email
            val displayName = body["displayName"]?.jsonPrimitive?.content ?: u.displayName
            val notifyEnabled = body["notifyEnabled"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: u.notifyEnabled
            // 简单邮箱校验
            if (email.isNotBlank() && !email.contains("@")) { AdminApi.fail(call, "邮箱格式不正确", 400); return@post }
            database.updateUserEmail(u.id, email.trim(), notifyEnabled)
            database.updateUserDisplayName(u.id, displayName.trim())
            database.addOpLog(u.id, u.username, "更新个人中心", "邮箱/昵称", call.request.local.remoteHost)
            AdminApi.ok(call, null, "个人资料已更新")
        }
        // ★ v52 生成 QQ 绑定码（网页端生成，群里发「绑定码 xxxx」绑定，无需私聊）
        post("/api/me/bind-code") {
            val u = call.requireAuth(database) ?: return@post
            // 6 位随机码 + 5 分钟过期
            val code = (100000 + (Math.random() * 900000).toInt()).toString()
            val expireAt = System.currentTimeMillis() + 5 * 60 * 1000L
            database.setConfig("qq_bind_code_$code", "${u.id}|$expireAt")
            AdminApi.ok(call, mapOf("code" to code, "expireSeconds" to 300), "绑定码已生成（5分钟内有效，群里发「绑定码 $code」完成绑定）")
        }
        // 限流配置：读取（QPS/每日配额，0=不限）
        get("/api/me/rate") {
            val u = call.requireAuth(database) ?: return@get
            AdminApi.ok(call, mapOf(
                "qps" to database.getUserConfig(u.id, "rate_qps", "60"),
                "daily" to database.getUserConfig(u.id, "rate_daily", "10000")
            ), "ok")
        }
        // 限流配置：保存
        post("/api/me/rate") {
            val u = call.requireAuth(database) ?: return@post
            val body = call.receive<JsonObject>()
            val qps = body["qps"]?.jsonPrimitive?.content?.toIntOrNull() ?: 60
            val daily = body["daily"]?.jsonPrimitive?.content?.toIntOrNull() ?: 10000
            if (qps < 0 || daily < 0) { AdminApi.fail(call, "限流值不能为负数", 400); return@post }
            database.setUserConfig(u.id, "rate_qps", qps.toString())
            database.setUserConfig(u.id, "rate_daily", daily.toString())
            AdminApi.ok(call, null, "限流配置已保存（0=不限流）")
        }
        // 人格配置：读取/保存
        get("/api/persona") {
            val user = call.requireAuth(database) ?: return@get
            val persona = database.getPersona(user.id)
            if (persona != null) AdminApi.ok(call, persona, "ok")
            else AdminApi.ok(call, mapOf<String, Any?>(), "暂无配置")
        }
        post("/api/persona") {
            val user = call.requireAuth(database) ?: return@post
            val body = call.receive<JsonObject>()
            val p = com.qitong.gateway.model.Persona(
                userId = user.id,
                name = body["name"]?.jsonPrimitive?.content ?: "",
                age = body["age"]?.jsonPrimitive?.content ?: "",
                personality = body["personality"]?.jsonPrimitive?.content ?: "",
                tone = body["tone"]?.jsonPrimitive?.content ?: "",
                background = body["background"]?.jsonPrimitive?.content ?: "",
                openness = body["openness"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.5,
                conscientiousness = body["conscientiousness"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.5,
                extraversion = body["extraversion"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.5,
                agreeableness = body["agreeableness"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.5,
                neuroticism = body["neuroticism"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.5,
                memoryEnabled = body["memoryEnabled"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true
            )
            database.savePersona(p)
            AdminApi.ok(call, null, "人格配置已保存")
        }
        // ===== 数据备份/恢复（APP↔线上互传） =====
        // 导出：服务商/模型/配置/密钥/规则/人格/记忆/自定义技能
        get("/api/backup/export") {
            val user = call.requireAuth(database) ?: return@get
            val isAdmin = user.role == "admin"
            val data = buildJsonObject {
                put("version", JsonPrimitive("3.18.22-78"))
                put("exportedAt", JsonPrimitive(System.currentTimeMillis()))
                put("username", JsonPrimitive(user.username))
                // 服务商（admin全量，用户自己的+公用）
                put("providers", JsonArray(
                    (if (isAdmin) database.getProviders() else database.getVisibleProviders(user.id).filter { it.isPublic || it.ownerId == user.id })
                        .map { p ->
                            buildJsonObject {
                                put("name", JsonPrimitive(p.name)); put("type", JsonPrimitive(p.type))
                                put("baseUrl", JsonPrimitive(p.baseUrl)); put("port", JsonPrimitive(p.port))
                                put("apiKey", JsonPrimitive(p.apiKey ?: "")); put("isEnabled", JsonPrimitive(p.isEnabled))
                                put("chatPath", JsonPrimitive(p.chatPath ?: "")); put("customId", JsonPrimitive(p.customId))
                                put("isPublic", JsonPrimitive(p.isPublic)); put("ownerId", JsonPrimitive(p.ownerId))
                            }
                        }
                ))
                // 模型（admin全量，用户自己的）
                put("models", JsonArray(
                    (if (isAdmin) database.getModels() else database.getModels().filter { it.isPublic || it.ownerId == user.id })
                        .map { m ->
                            buildJsonObject {
                                put("providerId", JsonPrimitive(m.providerId)); put("modelId", JsonPrimitive(m.modelId))
                                put("displayName", JsonPrimitive(m.displayName)); put("isEnabled", JsonPrimitive(m.isEnabled))
                                put("customAlias", JsonPrimitive(m.customAlias)); put("isPublic", JsonPrimitive(m.isPublic))
                                put("price", JsonPrimitive(m.price)); put("ownerId", JsonPrimitive(m.ownerId))
                            }
                        }
                ))
                // 人格 + 记忆
                if (!isAdmin) {
                    database.getPersona(user.id)?.let { p ->
                        put("persona", buildJsonObject {
                            put("name", JsonPrimitive(p.name)); put("age", JsonPrimitive(p.age))
                            put("personality", JsonPrimitive(p.personality)); put("tone", JsonPrimitive(p.tone))
                            put("background", JsonPrimitive(p.background))
                            put("openness", JsonPrimitive(p.openness)); put("conscientiousness", JsonPrimitive(p.conscientiousness))
                            put("extraversion", JsonPrimitive(p.extraversion)); put("agreeableness", JsonPrimitive(p.agreeableness))
                            put("neuroticism", JsonPrimitive(p.neuroticism)); put("memoryEnabled", JsonPrimitive(p.memoryEnabled))
                        })
                    }
                    put("memories", JsonArray(database.getMemories(user.id, 500).map { m ->
                        buildJsonObject {
                            put("title", JsonPrimitive(m["title"] as? String ?: ""))
                            put("content", JsonPrimitive(m["content"] as? String ?: ""))
                            put("type", JsonPrimitive(m["type"] as? String ?: "short"))
                            put("emotion", JsonPrimitive(m["emotion"] as? String ?: "neutral"))
                            put("importance", JsonPrimitive(m["importance"] as? Int ?: 5))
                        }
                    }))
                }
                // 自定义技能（按用户隔离）
                put("customSkills", JsonArray(database.getUserConfig(user.id, "custom_skills", "[]").let { s ->
                    try { kotlinx.serialization.json.Json.decodeFromString<JsonArray>(s) } catch (_: Exception) { JsonArray(emptyList()) }
                }))
                // ★ 系统级全量备份（仅管理员）：QQ机器人/群配置/用户/系统配置/API密钥
                if (isAdmin) {
                    put("qqBots", JsonArray(database.getQqBots().map { b ->
                        buildJsonObject {
                            put("appid", JsonPrimitive(b["appid"] as? String ?: ""))
                            put("name", JsonPrimitive(b["name"] as? String ?: ""))
                            put("appSecret", JsonPrimitive(b["appSecret"] as? String ?: ""))
                            put("useSandbox", JsonPrimitive((b["useSandbox"] as? Boolean) ?: false))
                            put("enabled", JsonPrimitive((b["enabled"] as? Boolean) ?: true))
                            put("aiModel", JsonPrimitive(b["aiModel"] as? String ?: "qtai-sj"))
                            put("systemPrompt", JsonPrimitive(b["systemPrompt"] as? String ?: ""))
                            put("welcome", JsonPrimitive(b["welcome"] as? String ?: ""))
                        }
                    }))
                    put("qqGroups", JsonArray(database.getQqGroups().map { g ->
                        buildJsonObject {
                            put("groupOpenid", JsonPrimitive(g["groupOpenid"] as? String ?: ""))
                            put("aiEnabled", JsonPrimitive(g["aiEnabled"] as? Boolean ?: true))
                            put("welcomeEnabled", JsonPrimitive(g["welcomeEnabled"] as? Boolean ?: true))
                            put("greeting", JsonPrimitive(g["greeting"] as? String ?: ""))
                            put("groupName", JsonPrimitive(g["groupName"] as? String ?: ""))
                            put("groupPrompt", JsonPrimitive(g["groupPrompt"] as? String ?: ""))
                            put("adminMute", JsonPrimitive(g["adminMute"] as? Boolean ?: true))
                            put("adminKick", JsonPrimitive(g["adminKick"] as? Boolean ?: true))
                            put("adminManage", JsonPrimitive(g["adminManage"] as? Boolean ?: true))
                        }
                    }))
                    put("qqUsers", JsonArray(database.getQqUsers().map { u ->
                        buildJsonObject {
                            put("openid", JsonPrimitive(u["openid"] as? String ?: ""))
                            put("displayName", JsonPrimitive(u["displayName"] as? String ?: ""))
                            put("permLevel", JsonPrimitive(u["permLevel"] as? Int ?: 1))
                            put("aiEnabled", JsonPrimitive(u["aiEnabled"] as? Boolean ?: true))
                            put("persona", JsonPrimitive(u["persona"] as? String ?: ""))
                        }
                    }))
                    put("systemConfig", JsonArray(database.getAllConfigs().map { c ->
                        buildJsonObject {
                            put("key", JsonPrimitive(c["key"] as? String ?: ""))
                            put("value", JsonPrimitive(c["value"] as? String ?: ""))
                        }
                    }))
                    put("apiKeys", JsonArray(database.getApiKeys().map { k ->
                        buildJsonObject {
                            put("key", JsonPrimitive(k.key))
                            put("label", JsonPrimitive(k.label))
                            put("enabled", JsonPrimitive(k.enabled))
                        }
                    }))
                }
            }
            AdminApi.respondJson(call, buildJsonObject {
                put("code", JsonPrimitive(0)); put("msg", JsonPrimitive("ok"))
                put("data", data)
            }, 200)
        }
        // 导入
        post("/api/backup/import") {
            val user = call.requireAuth(database) ?: return@post
            val isAdmin = user.role == "admin"
            val rawBody = call.receive<JsonObject>()
            // 兼容导出文件整体结构 {code,msg,data:{...}} 与直接 {providers,...}
            val body = rawBody["data"]?.let { if (it is JsonObject) it else rawBody } ?: rawBody
            var imported = 0
            // 导入服务商（已存在同名则跳过）
            body["providers"]?.jsonArray?.forEach { item ->
                val obj = item.jsonObject
                val pName = obj["name"]?.jsonPrimitive?.content ?: return@forEach
                if (database.getProviders().any { it.name == pName }) return@forEach
                val p = com.qitong.gateway.model.Provider(
                    name = pName,
                    type = obj["type"]?.jsonPrimitive?.content ?: "OpenAI Compatible",
                    baseUrl = obj["baseUrl"]?.jsonPrimitive?.content ?: "",
                    port = obj["port"]?.jsonPrimitive?.content ?: "",
                    apiKey = obj["apiKey"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
                    chatPath = obj["chatPath"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
                    customId = obj["customId"]?.jsonPrimitive?.content ?: "",
                    isPublic = if (isAdmin) (obj["isPublic"]?.jsonPrimitive?.content == "true") else false,
                    ownerId = if (isAdmin) (obj["ownerId"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L) else user.id
                )
                database.addProvider(p); imported++
            }
            // 导入模型（admin全量，用户自己的）
            body["models"]?.jsonArray?.forEach { item ->
                val obj = item.jsonObject
                val providerId = obj["providerId"]?.jsonPrimitive?.content?.toLongOrNull() ?: return@forEach
                val modelId = obj["modelId"]?.jsonPrimitive?.content ?: return@forEach
                val display = obj["displayName"]?.jsonPrimitive?.content ?: modelId
                if (database.getModelByKey(providerId, modelId) == null) {
                    database.addModel(com.qitong.gateway.model.AiModel(
                        providerId = providerId, modelId = modelId, displayName = display,
                        isEnabled = obj["isEnabled"]?.jsonPrimitive?.content != "false",
                        customAlias = obj["customAlias"]?.jsonPrimitive?.content ?: "",
                        isPublic = if (isAdmin) (obj["isPublic"]?.jsonPrimitive?.content == "true") else false,
                        price = obj["price"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                        ownerId = if (isAdmin) (obj["ownerId"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L) else user.id
                    )); imported++
                }
            }
            // 导入人格
            if (!isAdmin) {
                body["persona"]?.jsonObject?.let { p ->
                    database.savePersona(com.qitong.gateway.model.Persona(
                        userId = user.id,
                        name = p["name"]?.jsonPrimitive?.content ?: "",
                        age = p["age"]?.jsonPrimitive?.content ?: "",
                        personality = p["personality"]?.jsonPrimitive?.content ?: "",
                        tone = p["tone"]?.jsonPrimitive?.content ?: "",
                        background = p["background"]?.jsonPrimitive?.content ?: "",
                        openness = p["openness"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.5,
                        conscientiousness = p["conscientiousness"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.5,
                        extraversion = p["extraversion"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.5,
                        agreeableness = p["agreeableness"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.5,
                        neuroticism = p["neuroticism"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.5,
                        memoryEnabled = p["memoryEnabled"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true
                    )); imported++
                }
                // 导入记忆
                body["memories"]?.jsonArray?.forEach { item ->
                    val obj = item.jsonObject
                    database.addMemory(
                        user.id,
                        obj["title"]?.jsonPrimitive?.content ?: "",
                        obj["content"]?.jsonPrimitive?.content ?: "",
                        obj["type"]?.jsonPrimitive?.content ?: "short",
                        obj["emotion"]?.jsonPrimitive?.content ?: "neutral",
                        obj["importance"]?.jsonPrimitive?.content?.toIntOrNull() ?: 5,
                        "import", "", ""
                    ); imported++
                }
            }
            // 导入自定义技能（按用户隔离）
            body["customSkills"]?.let { cs ->
                if (cs is JsonArray || cs is JsonObject) {
                    database.setUserConfig(user.id, "custom_skills", cs.toString())
                    imported++
                }
            }
            // ★ 系统级导入（仅管理员）：QQ机器人/群配置/系统配置/API密钥
            if (isAdmin) {
                body["qqBots"]?.jsonArray?.forEach { item ->
                    val obj = item.jsonObject
                    val appid = obj["appid"]?.jsonPrimitive?.content ?: return@forEach
                    database.upsertQqBot(
                        appid,
                        obj["name"]?.jsonPrimitive?.content ?: "",
                        obj["appSecret"]?.jsonPrimitive?.content ?: "",
                        obj["useSandbox"]?.jsonPrimitive?.content == "true",
                        obj["name"]?.jsonPrimitive?.content ?: appid,
                        obj["enabled"]?.jsonPrimitive?.content != "false",
                        obj["aiModel"]?.jsonPrimitive?.content ?: "qtai-sj",
                        obj["systemPrompt"]?.jsonPrimitive?.content ?: "",
                        obj["welcome"]?.jsonPrimitive?.content ?: ""
                    ); imported++
                }
                body["qqGroups"]?.jsonArray?.forEach { item ->
                    val obj = item.jsonObject
                    val gOpenid = obj["groupOpenid"]?.jsonPrimitive?.content ?: return@forEach
                    database.updateQqGroup(
                        gOpenid,
                        obj["aiEnabled"]?.jsonPrimitive?.content != "false",
                        obj["welcomeEnabled"]?.jsonPrimitive?.content != "false",
                        obj["greeting"]?.jsonPrimitive?.content ?: "",
                        obj["adminMute"]?.jsonPrimitive?.content != "false",
                        obj["adminKick"]?.jsonPrimitive?.content != "false",
                        obj["adminManage"]?.jsonPrimitive?.content != "false",
                        obj["groupName"]?.jsonPrimitive?.content ?: "",
                        obj["groupPrompt"]?.jsonPrimitive?.content ?: ""
                    ); imported++
                }
                body["systemConfig"]?.jsonArray?.forEach { item ->
                    val obj = item.jsonObject
                    val k = obj["key"]?.jsonPrimitive?.content ?: return@forEach
                    val v = obj["value"]?.jsonPrimitive?.content ?: ""
                    database.setConfig(k, v); imported++
                }
                body["apiKeys"]?.jsonArray?.forEach { item ->
                    val obj = item.jsonObject
                    val k = obj["key"]?.jsonPrimitive?.content ?: return@forEach
                    database.addApiKey(com.qitong.gateway.model.ApiKeyEntry(
                        key = k,
                        label = obj["label"]?.jsonPrimitive?.content ?: "导入",
                        enabled = obj["enabled"]?.jsonPrimitive?.content != "false"
                    )); imported++
                }
            }
            AdminApi.ok(call, mapOf("imported" to imported), "导入成功 $imported 项")
        }
        // ===== 自定义技能（按用户隔离，无需系统配置权限） =====
        get("/api/skills") {
            val user = call.requireAuth(database) ?: return@get
            val s = database.getUserConfig(user.id, "custom_skills", "[]")
            val arr = try {
                kotlinx.serialization.json.Json.decodeFromString<JsonArray>(s)
            } catch (_: Exception) { JsonArray(emptyList()) }
            AdminApi.ok(call, arr.toList(), "ok")
        }
        post("/api/skills") {
            val user = call.requireAuth(database) ?: return@post
            val body = call.receive<JsonObject>()
            val name = body["name"]?.jsonPrimitive?.content?.trim() ?: ""
            val desc = body["description"]?.jsonPrimitive?.content?.trim() ?: ""
            if (name.isBlank() || desc.isBlank()) { AdminApi.fail(call, "请填写技能名称和描述", 400); return@post }
            val triggers = body["triggers"]?.jsonArray?.map { it.jsonPrimitive.content.trim() }?.filter { it.isNotBlank() } ?: emptyList()
            val s = database.getUserConfig(user.id, "custom_skills", "[]")
            val arr = try {
                kotlinx.serialization.json.Json.decodeFromString<JsonArray>(s)
            } catch (_: Exception) { JsonArray(emptyList()) }
            val list = arr.toMutableList()
            list.add(buildJsonObject {
                put("name", JsonPrimitive(name)); put("description", JsonPrimitive(desc))
                put("triggers", JsonArray(triggers.map { JsonPrimitive(it) }))
            })
            database.setUserConfig(user.id, "custom_skills", JsonArray(list).toString())
            AdminApi.ok(call, mapOf("count" to list.size), "技能已添加")
        }
        delete("/api/skills/{idx}") {
            val user = call.requireAuth(database) ?: return@delete
            val idx = call.parameters["idx"]?.toIntOrNull() ?: -1
            val s = database.getUserConfig(user.id, "custom_skills", "[]")
            val arr = try {
                kotlinx.serialization.json.Json.decodeFromString<JsonArray>(s)
            } catch (_: Exception) { JsonArray(emptyList()) }
            if (idx >= 0 && idx < arr.size) {
                val newList = arr.toMutableList().apply { removeAt(idx) }
                database.setUserConfig(user.id, "custom_skills", JsonArray(newList).toString())
                AdminApi.ok(call, mapOf("count" to newList.size), "技能已删除")
            } else AdminApi.fail(call, "技能索引无效", 400)
        }
        // 从 URL/Git raw 导入技能（支持 JSON 数组或对象格式）
        post("/api/skills/import") {
            val user = call.requireAuth(database) ?: return@post
            val body = call.receive<JsonObject>()
            val url = body["url"]?.jsonPrimitive?.content ?: ""
            if (url.isBlank()) { AdminApi.fail(call, "请输入技能URL", 400); return@post }
            // 请求远程技能文件
            var imported = 0
            try {
                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                val req = okhttp3.Request.Builder().url(url).header("User-Agent", "qitong-gateway").build()
                val resp = client.newCall(req).execute()
                val text = resp.body?.string() ?: ""
                resp.close()
                if (text.isBlank()) { AdminApi.fail(call, "远程内容为空", 400); return@post }
                // 解析 JSON（数组或对象）
                val parsed = try { kotlinx.serialization.json.Json.parseToJsonElement(text) } catch (_: Exception) { null }
                val items = when (parsed) {
                    is JsonArray -> parsed.toList()
                    is JsonObject -> listOf(parsed)
                    else -> emptyList()
                }
                if (items.isEmpty()) { AdminApi.fail(call, "无法解析技能格式（应为JSON数组）", 400); return@post }
                val s = database.getUserConfig(user.id, "custom_skills", "[]")
                val arr = try { kotlinx.serialization.json.Json.decodeFromString<JsonArray>(s) } catch (_: Exception) { JsonArray(emptyList()) }
                val list = arr.toMutableList()
                items.forEach { item ->
                    if (item is JsonObject) {
                        val name = item["name"]?.jsonPrimitive?.content ?: item["title"]?.jsonPrimitive?.content ?: ""
                        val desc = item["description"]?.jsonPrimitive?.content ?: item["desc"]?.jsonPrimitive?.content ?: ""
                        if (name.isNotBlank() && desc.isNotBlank() && !list.any { it is JsonObject && it["name"]?.jsonPrimitive?.content == name }) {
                            val trigs = mutableListOf<String>()
                            item["triggers"]?.let { t ->
                                if (t is JsonArray) t.forEach { trigs.add(it.jsonPrimitive.content) }
                                else if (t is JsonPrimitive) trigs.add(t.content)
                            }
                            list.add(buildJsonObject {
                                put("name", JsonPrimitive(name)); put("description", JsonPrimitive(desc))
                                put("triggers", JsonArray(trigs.map { JsonPrimitive(it) }))
                            })
                            imported++
                        }
                    }
                }
                if (imported > 0) {
                    database.setUserConfig(user.id, "custom_skills", JsonArray(list).toString())
                    AdminApi.ok(call, mapOf("imported" to imported, "count" to list.size), "成功导入 $imported 个技能")
                } else {
                    AdminApi.ok(call, mapOf("imported" to 0, "count" to list.size), "没有新技能可导入（可能已存在）")
                }
            } catch (e: Exception) {
                AdminApi.fail(call, "导入失败: ${e.message}", 400)
            }
        }
        // ===== 大脑记忆（按用户隔离） =====
        get("/api/memory") {
            val user = call.requireAuth(database) ?: return@get
            AdminApi.ok(call, database.getMemories(user.id), "ok")
        }
        post("/api/memory") {
            val user = call.requireAuth(database) ?: return@post
            val body = call.receive<JsonObject>()
            val title = body["title"]?.jsonPrimitive?.content ?: ""
            val content = body["content"]?.jsonPrimitive?.content ?: ""
            val type = body["type"]?.jsonPrimitive?.content ?: "short"
            val emotion = body["emotion"]?.jsonPrimitive?.content ?: "neutral"
            val importance = body["importance"]?.jsonPrimitive?.content?.toIntOrNull() ?: 5
            val tags = body["tags"]?.jsonPrimitive?.content ?: ""
            val modelId = body["modelId"]?.jsonPrimitive?.content ?: ""
            val id = database.addMemory(user.id, title, content, type, emotion, importance, "manual", tags, modelId)
            AdminApi.ok(call, mapOf("id" to id), "记忆已保存")
        }
        delete("/api/memory/{id}") {
            val id = call.parameters["id"]?.toLongOrNull() ?: return@delete
            val user = call.requireAuth(database) ?: return@delete
            database.deleteMemory(id, user.id)
            AdminApi.ok(call, null, "已删除")
        }
        post("/api/memory/clear") {
            val user = call.requireAuth(database) ?: return@post
            database.clearMemories(user.id)
            AdminApi.ok(call, null, "记忆已清空")
        }
                // ===== qtai-sj 大脑绑定（按用户） =====
        get("/api/qtai/brain") {
            val user = call.requireAuth(database) ?: return@get
            val brain = database.getUserConfig(user.id, "qtai_brain", "")
            AdminApi.ok(call, mapOf("brain" to brain), "ok")
        }
        post("/api/qtai/brain") {
            val user = call.requireAuth(database) ?: return@post
            val body = call.receive<JsonObject>()
            val brain = body["brain"]?.jsonPrimitive?.content ?: ""
            database.setUserConfig(user.id, "qtai_brain", brain)
            AdminApi.ok(call, null, if (brain.isBlank()) "已解除大脑绑定" else "大脑已绑定: $brain")
        }
        // ===== 记忆配置（对齐原APP MemoryConfig：模型独立记忆开关） =====
        get("/api/memory/config") {
            val user = call.requireAuth(database) ?: return@get
            AdminApi.ok(call, database.getMemoryConfig(user.id), "ok")
        }
        post("/api/memory/config") {
            val user = call.requireAuth(database) ?: return@post
            val body = call.receive<JsonObject>()
            val config = mutableMapOf<String, Any?>()
            body["enabled"]?.let { config["enabled"] = it.jsonPrimitive.content == "true" }
            body["saveMode"]?.let { config["saveMode"] = it.jsonPrimitive.content }
            body["empathyLevel"]?.let { config["empathyLevel"] = it.jsonPrimitive.content.toIntOrNull() ?: 8 }
            body["thinkingDepth"]?.let { config["thinkingDepth"] = it.jsonPrimitive.content.toIntOrNull() ?: 3 }
            body["catchphrases"]?.let { config["catchphrases"] = it.jsonPrimitive.content }
            body["forbiddenWords"]?.let { config["forbiddenWords"] = it.jsonPrimitive.content }
            body["expertise"]?.let { config["expertise"] = it.jsonPrimitive.content }
            body["communicationStyle"]?.let { config["communicationStyle"] = it.jsonPrimitive.content }
            body["modelIndependent"]?.let { config["modelIndependent"] = it.jsonPrimitive.content == "true" }
            database.saveMemoryConfig(user.id, config)
            AdminApi.ok(call, null, "记忆配置已保存")
        }
        // ===== 公告（首页顶部，任意用户可见） =====
        get("/api/announcements") {
            call.requireAuth(database) ?: return@get
            AdminApi.ok(call, database.getAnnouncements(), "ok")
        }
        // ===== 公告管理（仅管理员） =====
        post("/api/announcements") {
            val user = call.requireAuth(database) ?: return@post
            if (user.role != "admin") { AdminApi.fail(call, "无权限", 403); return@post }
            val body = call.receive<JsonObject>()
            val title = body["title"]?.jsonPrimitive?.content ?: ""
            val content = body["content"]?.jsonPrimitive?.content ?: ""
            val isPinned = body["isPinned"]?.jsonPrimitive?.content == "true"
            if (title.isBlank() || content.isBlank()) { AdminApi.fail(call, "标题和内容不能为空", 400); return@post }
            database.addAnnouncement(title, content, user.id, isPinned)
            database.addOpLog(user.id, user.username, "新增公告", title, call.request.local.remoteHost)
            AdminApi.ok(call, null, "公告已发布")
        }
        post("/api/announcements/update") {
            val user = call.requireAuth(database) ?: return@post
            if (user.role != "admin") { AdminApi.fail(call, "无权限", 403); return@post }
            val body = call.receive<JsonObject>()
            val id = body["id"]?.jsonPrimitive?.content?.toLongOrNull() ?: return@post
            val title = body["title"]?.jsonPrimitive?.content ?: ""
            val content = body["content"]?.jsonPrimitive?.content ?: ""
            val isPinned = body["isPinned"]?.jsonPrimitive?.content == "true"
            database.updateAnnouncement(id, title, content, isPinned)
            database.addOpLog(user.id, user.username, "更新公告", title, call.request.local.remoteHost)
            AdminApi.ok(call, null, "公告已更新")
        }
        post("/api/announcements/delete") {
            val user = call.requireAuth(database) ?: return@post
            if (user.role != "admin") { AdminApi.fail(call, "无权限", 403); return@post }
            val body = call.receive<JsonObject>()
            val id = body["id"]?.jsonPrimitive?.content?.toLongOrNull() ?: return@post
            database.deleteAnnouncement(id)
            database.addOpLog(user.id, user.username, "删除公告", "id=$id", call.request.local.remoteHost)
            AdminApi.ok(call, null, "公告已删除")
        }
        // ===== 工单（用户提交，管理员可查看/反馈） =====
        get("/api/tickets") {
            val user = call.requireAuth(database) ?: return@get
            val isAdmin = user.role == "admin"
            val tickets = database.getTickets(if (isAdmin) null else user.id, isAdmin)
            // 补充用户昵称
            AdminApi.ok(call, tickets, "ok")
        }
        post("/api/tickets") {
            val user = call.requireAuth(database) ?: return@post
            val body = call.receive<JsonObject>()
            val title = body["title"]?.jsonPrimitive?.content ?: ""
            if (title.isBlank()) { AdminApi.fail(call, "请填写标题", 400); return@post }
            val id = database.addTicket(user.id, title)
            database.addOpLog(user.id, user.username, "提交工单", title, call.request.local.remoteHost)
            // 新工单通知管理员
            Thread {
                try { kotlinx.coroutines.runBlocking { com.qitong.gateway.notify.NotificationManager.notify(
                    database, "🎫 新工单",
                    "用户 ${user.username} 提交工单: $title"
                ) } } catch (_: Exception) {}
            }.start()
            AdminApi.ok(call, mapOf("id" to id), "工单已提交")
        }
        get("/api/tickets/{id}/messages") {
            val user = call.requireAuth(database) ?: return@get
            val id = call.parameters["id"]?.toLongOrNull() ?: return@get
            val isAdmin = user.role == "admin"
            if (!isAdmin && !database.isTicketOwner(id, user.id)) { AdminApi.fail(call, "无权限查看该工单", 403); return@get }
            AdminApi.ok(call, database.getTicketMessages(id), "ok")
        }
        post("/api/tickets/{id}/messages") {
            val user = call.requireAuth(database) ?: return@post
            val id = call.parameters["id"]?.toLongOrNull() ?: return@post
            val isAdmin = user.role == "admin"
            if (!isAdmin && !database.isTicketOwner(id, user.id)) { AdminApi.fail(call, "无权限", 403); return@post }
            val body = call.receive<JsonObject>()
            val content = body["content"]?.jsonPrimitive?.content ?: ""
            if (content.isBlank()) { AdminApi.fail(call, "消息不能为空", 400); return@post }
            database.addTicketMessage(id, user.id, if (isAdmin) "admin" else "user", content)
            AdminApi.ok(call, null, "已回复")
        }
        post("/api/tickets/{id}/status") {
            val user = call.requireAuth(database) ?: return@post
            val id = call.parameters["id"]?.toLongOrNull() ?: return@post
            val isAdmin = user.role == "admin"
            if (!isAdmin) { AdminApi.fail(call, "仅管理员可变更状态", 403); return@post }
            val body = call.receive<JsonObject>()
            val status = body["status"]?.jsonPrimitive?.content ?: "open"
            database.updateTicketStatus(id, status)
            AdminApi.ok(call, null, "状态已更新为 $status")
        }
        post("/api/tickets/{id}/delete") {
            val user = call.requireAuth(database) ?: return@post
            val id = call.parameters["id"]?.toLongOrNull() ?: return@post
            val isAdmin = user.role == "admin"
            if (!isAdmin && !database.isTicketOwner(id, user.id)) { AdminApi.fail(call, "无权限", 403); return@post }
            database.deleteTicket(id)
            AdminApi.ok(call, null, "工单已删除")
        }
        // ===== 操作日志（管理员=全部；普通用户=自己的，独立可见） =====
        get("/api/logs") {
            val user = call.requireAuth(database) ?: return@get
            // 普通用户只看自己的操作日志，管理员看全部
            val logs = if (user.role == "admin") database.getOpLogs()
                else database.getOpLogs(user.id)
            AdminApi.ok(call, logs, "ok")
        }
        // 登录日志（宝塔风格：登录IP/成功失败/时间；管理员=全部，普通用户=自己的）
        get("/api/logs/login") {
            val user = call.requireAuth(database) ?: return@get
            val logs = if (user.role == "admin") database.getLoginLogs()
                else database.getLoginLogs(user.id)
            AdminApi.ok(call, logs, "ok")
        }
        post("/api/logs/clear") {
            val user = call.requireAuth(database) ?: return@post
            if (user.role != "admin") { AdminApi.fail(call, "无权限", 403); return@post }
            database.clearOpLogs()
            AdminApi.ok(call, null, "日志已清空")
        }
        post("/api/logs/login/clear") {
            val user = call.requireAuth(database) ?: return@post
            if (user.role != "admin") { AdminApi.fail(call, "无权限", 403); return@post }
            database.clearLoginLogs()
            AdminApi.ok(call, null, "登录日志已清空")
        }
        // ===== 通知设置（钉钉Webhook + 邮箱SMTP，仅管理员） =====
        get("/api/notify/config") {
            val u = call.requireAuth(database) ?: return@get
            if (u.role != "admin") { AdminApi.fail(call, "无权限", 403); return@get }
            AdminApi.ok(call, com.qitong.gateway.notify.NotificationManager.getConfig(database), "ok")
        }
        post("/api/notify/config") {
            val u = call.requireAuth(database) ?: return@post
            if (u.role != "admin") { AdminApi.fail(call, "无权限", 403); return@post }
            val body = call.receive<JsonObject>()
            val cfg = mutableMapOf<String, String>()
            body["dingtalk_webhook"]?.let { cfg["dingtalk_webhook"] = it.jsonPrimitive.content }
            body["email_smtp_host"]?.let { cfg["email_smtp_host"] = it.jsonPrimitive.content }
            body["email_smtp_port"]?.let { cfg["email_smtp_port"] = it.jsonPrimitive.content }
            body["email_username"]?.let { cfg["email_username"] = it.jsonPrimitive.content }
            body["email_password"]?.let { cfg["email_password"] = it.jsonPrimitive.content }
            body["email_to"]?.let { cfg["email_to"] = it.jsonPrimitive.content }
            body["email_tls"]?.let { cfg["email_tls"] = it.jsonPrimitive.content }
            body["enable_dingtalk"]?.let { cfg["enable_dingtalk"] = it.jsonPrimitive.content }
            body["enable_email"]?.let { cfg["enable_email"] = it.jsonPrimitive.content }
            com.qitong.gateway.notify.NotificationManager.saveConfig(database, cfg)
            database.addOpLog(u.id, u.username, "更新通知配置", "钉钉/邮箱", call.request.local.remoteHost)
            AdminApi.ok(call, null, "通知配置已保存")
        }
        post("/api/notify/test") {
            val u = call.requireAuth(database) ?: return@post
            if (u.role != "admin") { AdminApi.fail(call, "无权限", 403); return@post }
            val body = call.receive<JsonObject>()
            val msg = body["message"]?.jsonPrimitive?.content ?: "【綦桐AI网关】测试通知，配置成功！"
            com.qitong.gateway.notify.NotificationManager.notify(database, "綦桐AI网关通知", msg)
            AdminApi.ok(call, null, "通知已发送（钉钉/邮箱）")
        }
        // ===== qtai-sj MCP 控制接口（对齐APP远程控制） =====
        // action: status(状态) / setbrain(设大脑) / setactive(设活跃模型) / speedtest(测速) / toggle(启停) / forced(强制池)
        post("/api/qtai/mcp") {
            val user = call.requireAuth(database) ?: return@post
            val body = call.receive<JsonObject>()
            val action = body["action"]?.jsonPrimitive?.content ?: "status"
            when (action) {
                "status" -> {
                    val brain = database.getUserConfig(user.id, "qtai_brain", "")
                    val active = database.getConfig("active_model_key", "").substringAfter("::", "")
                    AdminApi.ok(call, mapOf(
                        "running" to GatewayProxy.running,
                        "brain" to brain,
                        "activeModel" to active,
                        "autoFailover" to database.getConfig("auto_failover", "true"),
                        "balance" to user.balance
                    ), "ok")
                }
                "setbrain" -> {
                    val brain = body["brain"]?.jsonPrimitive?.content ?: ""
                    database.setUserConfig(user.id, "qtai_brain", brain)
                    AdminApi.ok(call, mapOf("brain" to brain), "大脑已绑定")
                }
                "setactive" -> {
                    val modelKey = body["modelKey"]?.jsonPrimitive?.content ?: ""
                    if (modelKey.isNotBlank()) {
                        database.setConfig("active_model_key", modelKey)
                        AdminApi.ok(call, mapOf("activeModel" to modelKey.substringAfter("::", modelKey)), "活跃模型已设置")
                    } else AdminApi.fail(call, "modelKey不能为空", 400)
                }
                "speedtest" -> {
                    val results = GatewayScheduler.refreshHealthCache(database, force = true)
                    GatewayScheduler.buildPipelineSortedModels(database)
                    val ok = results.count { it.isHealthy }
                    AdminApi.ok(call, mapOf("total" to results.size, "ok" to ok), "测速完成")
                }
                "toggle" -> {
                    GatewayProxy.running = !GatewayProxy.running
                    AdminApi.ok(call, mapOf("running" to GatewayProxy.running), if (GatewayProxy.running) "网关已启动" else "网关已停止")
                }
                "forced" -> {
                    val modelKey = body["modelKey"]?.jsonPrimitive?.content ?: ""
                    val current = database.getUserConfig(user.id, "forced_pool_keys", "").split(",").filter { it.isNotBlank() }.toMutableList()
                    if (modelKey.isNotBlank()) {
                        // 点灯=强制切换：无论是否已在池中，都移到首位
                        current.remove(modelKey)
                        current.add(0, modelKey)
                        database.setUserConfig(user.id, "forced_pool_keys", current.joinToString(","))
                        database.setUserConfig(user.id, "active_model_key", modelKey)
                        AdminApi.ok(call, mapOf("forcedPool" to current), "已强制切换到 $modelKey")
                    } else {
                        database.setUserConfig(user.id, "forced_pool_keys", "")
                        AdminApi.ok(call, mapOf("forcedPool" to emptyList<String>()), "强制池已清空")
                    }
                }
                else -> AdminApi.fail(call, "未知操作", 400)
            }
        }
        // ===== 语言设置（按用户） =====
        get("/api/me/language") {
            val user = call.requireAuth(database) ?: return@get
            val lang = database.getUserConfig(user.id, "language", "zh")
            AdminApi.ok(call, mapOf("language" to lang), "ok")
        }
        post("/api/me/language") {
            val user = call.requireAuth(database) ?: return@post
            val body = call.receive<JsonObject>()
            val lang = body["language"]?.jsonPrimitive?.content ?: "zh"
            database.setUserConfig(user.id, "language", lang)
            AdminApi.ok(call, null, "语言已切换")
        }
        // 密钥编辑（启用/停用/放模型）
        post("/api/keys/update") {
            val u = call.requireAuth(database) ?: return@post
            val body = call.receive<JsonObject>()
            val key = body["key"]?.jsonPrimitive?.content ?: run { AdminApi.fail(call, "密钥无效", 400); return@post }
            val entry = database.getApiKeys().find { it.key == key } ?: run { AdminApi.fail(call, "密钥不存在", 404); return@post }
            if (!AdminApi.canManageResource(u, AdminApi.Perm.K_MANAGE, entry.ownerId)) { AdminApi.fail(call, "无权限编辑该密钥", 403); return@post }
            val updated = entry.copy(
                label = body["label"]?.jsonPrimitive?.content ?: entry.label,
                enabled = body["enabled"]?.let {
                    when { it is JsonPrimitive && it.isString -> it.content == "true" || it.content == "1"; else -> entry.enabled }
                } ?: entry.enabled,
                allowedModels = body["allowedModels"]?.let {
                    try { kotlinx.serialization.json.Json.decodeFromString<List<String>>(it.toString()) } catch (_: Exception) { entry.allowedModels }
                } ?: entry.allowedModels,
                qtaiSjAccess = body["qtaiSjAccess"]?.let {
                    when { it is JsonPrimitive && it.isString -> it.content == "true" || it.content == "1"; else -> entry.qtaiSjAccess }
                } ?: entry.qtaiSjAccess
            )
            database.updateApiKey(updated)
            AdminApi.ok(call, null, "密钥已更新")
        }

        // 聊天（按用户隔离）
        get("/api/conversations") { val u = call.requireAuth(database) ?: return@get; AdminApi.ok(call, if (u.role == "admin") AdminApi.getConversations(database) else AdminApi.getConversationsForUser(database, u.id)) }
        post("/api/conversations") {
            val u = call.requireAuth(database) ?: return@post
            val body = call.receive<JsonObject>()
            AdminApi.ok(call, mapOf("id" to AdminApi.createConversation(database, body["title"]?.jsonPrimitive?.content ?: "新对话", u.id)), "创建成功")
        }
        get("/api/conversations/{id}") {
            val id = call.parameters["id"]?.toLongOrNull() ?: return@get
            val u = call.requireAuth(database) ?: return@get
            AdminApi.ok(call, AdminApi.getConversation(database, id))
        }
        delete("/api/conversations/{id}") {
            val id = call.parameters["id"]?.toLongOrNull() ?: return@delete
            if (call.requireAuth(database) == null) return@delete
            AdminApi.deleteConversation(database, id)
            AdminApi.ok(call, null, "已删除")
        }
        // 重命名会话（自定义标题）
        post("/api/conversations/{id}/rename") {
            val id = call.parameters["id"]?.toLongOrNull() ?: return@post
            val u = call.requireAuth(database) ?: return@post
            val body = call.receive<JsonObject>()
            val title = body["title"]?.jsonPrimitive?.content?.trim().orEmpty()
            if (title.isBlank()) { AdminApi.fail(call, "标题不能为空", 400); return@post }
            AdminApi.renameConversation(database, id, title.take(40))
            database.addOpLog(u.id, u.username, "重命名会话", "会话$id → $title", call.request.local.remoteHost)
            AdminApi.ok(call, null, "标题已更新")
        }
        post("/api/chat") {
            val u = call.requireAuth(database) ?: return@post
            val body = call.receive<JsonObject>()
            AdminApi.ok(call, AdminApi.chat(database, body, u), "完成")
        }
        // 上传图片/文件（base64），返回可访问 URL
        post("/api/upload") {
            val u = call.requireAuth(database) ?: return@post
            val body = call.receive<JsonObject>()
            val filename = body["filename"]?.jsonPrimitive?.content?.trim().orEmpty().ifBlank { "file" }
            val b64 = body["data"]?.jsonPrimitive?.content.orEmpty()
            val mime = body["mime"]?.jsonPrimitive?.content.orEmpty()
            if (b64.length < 4) { AdminApi.fail(call, "内容为空", 400); return@post }
            val uploadDir = java.io.File(System.getenv("DATA_DIR") ?: "/data/qitong", "uploads")
            uploadDir.mkdirs()
            val safeName = filename.replace(Regex("[^a-zA-Z0-9._\\-\\u4e00-\\u9fa5]"), "_")
            val out = java.io.File(uploadDir, "${System.currentTimeMillis()}_$safeName")
            runCatching {
                val clean = b64.replace(Regex("^data:.*;base64,"), "")
                val bytes = java.util.Base64.getDecoder().decode(clean)
                out.writeBytes(bytes)
            }.onFailure { AdminApi.fail(call, "上传失败: ${it.message}", 500); return@post }
            AdminApi.ok(call, mapOf("url" to "/uploads/${out.name}", "name" to safeName, "size" to out.length(), "mime" to mime), "上传成功")
        }
        // 静态访问上传的文件
        staticFiles("/uploads", java.io.File(System.getenv("DATA_DIR") ?: "/data/qitong", "uploads"))

        // 状态
        get("/api/status") {
            val viewer = call.authUser(database)   // 当前登录用户（可能为null=本地/未登录）
            val viewerId = viewer?.id ?: 0L
            val isAdmin = viewer?.role == "admin"
            AdminApi.respondJson(call, buildJsonObject {
                put("code", JsonPrimitive(0)); put("msg", JsonPrimitive("ok"))
                put("data", buildJsonObject {
                    put("status", JsonPrimitive("ok"))
                    put("version", JsonPrimitive("3.18.22-78"))
                    // running：管理员=全局网关状态；普通用户=自己的API开关(api_enabled)
                    val userRunning = if (isAdmin) GatewayProxy.running
                    else if (viewerId > 0) database.getUserConfig(viewerId, "api_enabled", "true").toBoolean()
                    else GatewayProxy.running
                    put("running", JsonPrimitive(userRunning))
                    put("uptime", JsonPrimitive((System.currentTimeMillis() - GatewayProxy.startTime) / 1000))
                    put("balance", JsonPrimitive(viewer?.balance ?: 0.0))
                    put("role", JsonPrimitive(viewer?.role ?: ""))
                    put("requireApiKey", JsonPrimitive(database.getConfig("require_api_key", "true").toBoolean()))
                    put("autoFailover", JsonPrimitive(database.getConfig("auto_failover", "true").toBoolean()))
                    // 网关地址
                    put("gatewayPort", JsonPrimitive(database.getConfig("gateway_port", "18889")))
                    put("serverIp", JsonPrimitive(serverIp()))
                    put("localAddr", JsonPrimitive("http://" + serverIp() + ":" + database.getConfig("gateway_port", "18889") + "/v1"))
                    // 当前访问域名（匹配反代域名，如 ai.jili5.cn；无域名则为空）
                    val reqHost = call.request.headers["Host"] ?: ""
                    val domain = reqHost.substringBefore(":").trim().takeIf { it.isNotBlank() && !it.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+")) && it != "localhost" } ?: ""
                    put("serverDomain", JsonPrimitive(domain))
                    put("domainAddr", JsonPrimitive(if (domain.isNotBlank()) "https://$domain/v1" else ""))
                    // 强制故障池：管理员=全局池；用户=自己的池
                    val forcedPoolStr = if (isAdmin) {
                        database.getConfig("forced_pool_keys", "")
                    } else if (viewerId > 0) {
                        database.getUserConfig(viewerId, "forced_pool_keys", "")
                    } else {
                        database.getConfig("forced_pool_keys", "")
                    }
                    // 活跃模型：用户=自己的（点灯后）；管理员=全局
                    val activeStr = if (isAdmin) {
                        database.getConfig("active_model_key", "").substringAfter("::", "")
                    } else if (viewerId > 0) {
                        val userActive = database.getUserConfig(viewerId, "active_model_key", "")
                        if (userActive.isNotBlank()) userActive.substringAfter("::", userActive)
                        else database.getConfig("active_model_key", "").substringAfter("::", "")
                    } else {
                        database.getConfig("active_model_key", "").substringAfter("::", "")
                    }
                    put("activeModel", JsonPrimitive(activeStr))
                    // 当前真实活跃（含强制池首位）
                    val forcedActive = forcedPoolStr.split(",").filter { it.isNotBlank() }.firstOrNull()
                    put("forcedActive", JsonPrimitive(forcedActive?.substringAfter("::", forcedActive) ?: ""))
                    // 最近一次实际成功命中的模型（自动故障转移后灯跟随它）
                    put("runtimeActive", JsonPrimitive(com.qitong.gateway.http.GatewayProxy.lastServedKey.substringAfter("::", "")))
                    put("runtimeActiveKey", JsonPrimitive(com.qitong.gateway.http.GatewayProxy.lastServedKey))
                    put("runtimeActiveAt", JsonPrimitive(com.qitong.gateway.http.GatewayProxy.lastServedAt))
                    put("forcedPool", JsonArray(forcedPoolStr.split(",").filter { it.isNotBlank() }.map { JsonPrimitive(it) }))
                    // 自动测速：管理员=全局；用户=自己的
                    val autoSpeedStr = if (isAdmin) {
                        database.getConfig("auto_speedtest", "false")
                    } else if (viewerId > 0) {
                        database.getUserConfig(viewerId, "auto_speedtest", "false")
                    } else {
                        "false"
                    }
                    put("autoSpeedTest", JsonPrimitive(autoSpeedStr.toBoolean()))
                    put("speedIntervalMin", JsonPrimitive(
                        if (isAdmin) database.getConfig("speed_interval_min", "240")
                        else if (viewerId > 0) database.getUserConfig(viewerId, "speed_interval_min", "240")
                        else "240"
                    ))
                    // 排行榜数据源：用户只看 公用+自己的模型；管理员看全部
                    val allModels = database.getModels()
                    val visibleModels = if (isAdmin) allModels
                    else if (viewerId > 0) allModels.filter { m -> m.isPublic || m.ownerId == viewerId }
                    else allModels.filter { it.isPublic }
                    val enabledModels = visibleModels.filter { it.isEnabled }
                    // 可见模型 key 集合（过滤全局健康缓存）
                    val visibleKeys = visibleModels.map { GatewayScheduler.routeKey(it.providerId, it.modelId) }.toSet()
                    val pipSorted = GatewayScheduler.buildPipelineSortedModels(database).filter { it in visibleKeys }
                    val sortedAll = pipSorted + enabledModels
                        .filter { GatewayScheduler.routeKey(it.providerId, it.modelId) !in pipSorted }
                        .map { GatewayScheduler.routeKey(it.providerId, it.modelId) }
                    put("pipelineSorted", JsonArray(sortedAll.map { JsonPrimitive(it) }))
                    put("healthCache", JsonArray(synchronized(GatewayScheduler.healthCache) {
                        GatewayScheduler.healthCache.entries.filter { (k, _) -> k in sortedAll }.map { (k, v) ->
                            buildJsonObject {
                                put("key", JsonPrimitive(k))
                                put("modelId", JsonPrimitive(v.modelId))
                                put("providerId", JsonPrimitive(v.providerId))
                                put("latencyMs", JsonPrimitive(if (v.isHealthy) v.totalMs else -1))
                                put("ttftMs", JsonPrimitive(v.ttftMs))
                                put("tps", JsonPrimitive(v.tps))
                                put("totalMs", JsonPrimitive(v.totalMs))
                                put("isHealthy", JsonPrimitive(v.isHealthy))
                                put("successCount", JsonPrimitive(v.successCount))
                            }
                        }
                    }))
                })
            }, 200)
        }

        // 网关启停控制：管理员=总开关；用户=自己key的可用开关（记录到用户配置）
        post("/api/gateway/toggle") {
            val u = call.requireAuth(database) ?: return@post
            val body = call.receive<JsonObject>()
            val action = body["action"]?.jsonPrimitive?.content ?: "toggle"
            if (u.role == "admin") {
                val newState = GatewayProxy.toggleGateway(action, database)
                AdminApi.ok(call, mapOf("running" to newState), if (newState) "网关已启动" else "网关已停止")
            } else {
                // 用户级开关：允许/禁止自己的key转发
                val newState = database.getUserConfig(u.id, "api_enabled", "true") == "false"
                database.setUserConfig(u.id, "api_enabled", newState.toString())
                AdminApi.ok(call, mapOf("running" to newState), if (newState) "API 已启用" else "API 已暂停")
            }
        }

        // 设置活跃模型（qtai-sj 解析目标）
        post("/api/gateway/active-model") {
            val u = call.requireAuth(database) ?: return@post
            val body = call.receive<JsonObject>()
            val key = body["modelKey"]?.jsonPrimitive?.content ?: ""
            if (key.isNotBlank()) {
                database.setConfig("active_model_key", key)
                database.addOpLog(u.id, u.username, "切换模型", "设置活跃模型: ${key.substringAfter("::", key)}", call.request.local.remoteHost)
            }
            AdminApi.ok(call, null, "已设置活跃模型")
        }

        // 强制故障池 + 点灯强制切换：管理员=全局池；用户=自己的池（按key属主隔离）
        post("/api/gateway/forced-pool") {
            val u = call.requireAuth(database) ?: return@post
            val body = call.receive<JsonObject>()
            val action = body["action"]?.jsonPrimitive?.content ?: "add"
            val modelKey = body["modelKey"]?.jsonPrimitive?.content ?: ""
            val isAdmin = u.role == "admin"
            // 非管理员走用户级隔离（用户自己的池 + 活跃模型互不串）
            if (!isAdmin) {
                val proxy = GatewayProxy(database)
                when (action) {
                    "add" -> {
                        proxy.userForceModel(u.id, modelKey) // 点灯=强制切到该模型（置首位+活跃）
                        val pool = database.getUserConfig(u.id, "forced_pool_keys", "").split(",").filter { it.isNotBlank() }
                        AdminApi.ok(call, mapOf("forcedPool" to pool, "activeModel" to modelKey.substringAfter("::", modelKey)), "已强制切换到 $modelKey")
                    }
                    "remove" -> {
                        proxy.userUnforceModel(u.id, modelKey)
                        val pool = database.getUserConfig(u.id, "forced_pool_keys", "").split(",").filter { it.isNotBlank() }
                        AdminApi.ok(call, mapOf("forcedPool" to pool, "activeModel" to database.getUserConfig(u.id, "active_model_key", "").substringAfter("::", "")), "已移出强制池")
                    }
                    "clear" -> {
                        database.setUserConfig(u.id, "forced_pool_keys", "")
                        database.setUserConfig(u.id, "active_model_key", "")
                        AdminApi.ok(call, mapOf("forcedPool" to emptyList<String>(), "activeModel" to ""), "已清空，恢复自动选择")
                    }
                    else -> AdminApi.fail(call, "未知操作", 400)
                }
                return@post
            }
            // 管理员：全局池（不改用户级）
            val key = "forced_pool_keys"
            val current = database.getConfig(key, "").split(",").filter { it.isNotBlank() }.toMutableList()
            when (action) {
                "add" -> {
                    // 点灯=强制切换：无论是否已在池中，都移到首位（对齐原APP点灯语义）
                    if (modelKey.isNotBlank()) {
                        current.remove(modelKey)
                        current.add(0, modelKey)
                        database.setConfig("active_model_key", modelKey)
                    }
                }
                "remove" -> current.remove(modelKey)
                "clear" -> current.clear()
            }
            database.setConfig(key, current.joinToString(","))
            AdminApi.ok(call, mapOf("forcedPool" to current, "activeModel" to database.getConfig("active_model_key", "").substringAfter("::", "")), "已更新强制故障池")
        }

        // 自动测速开关 + 间隔：管理员=全局；用户=自己的
        post("/api/gateway/auto-speedtest") {
            val u = call.requireAuth(database) ?: return@post
            val body = call.receive<JsonObject>()
            val enabled = body["enabled"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
            val interval = body["intervalMin"]?.jsonPrimitive?.content?.toIntOrNull() ?: 240
            val isAdmin = u.role == "admin"
            if (isAdmin) {
                database.setConfig("auto_speedtest", enabled.toString())
                database.setConfig("speed_interval_min", interval.toString())
                GatewayProxy.setAutoSpeedTest(enabled, interval, database)
                AdminApi.ok(call, null, if (enabled) "全局自动测速已开启（每 ${interval} 分钟）" else "全局自动测速已停止")
            } else {
                database.setUserConfig(u.id, "auto_speedtest", enabled.toString())
                database.setUserConfig(u.id, "speed_interval_min", interval.toString())
                AdminApi.ok(call, null, if (enabled) "我的自动测速已开启（每 ${interval} 分钟）" else "我的自动测速已停止")
            }
        }
    }
}

fun io.ktor.server.application.ApplicationCall.remoteIp(): String {
    return request.headers["X-Forwarded-For"]?.substringBefore(",")?.trim()
        ?: request.headers["X-Real-IP"]
        ?: request.local.remoteHost
        ?: ""
}

/** 获取服务器公网/本机IP（用于首页展示 IP:端口） */
fun serverIp(): String {
    // 1. 尝试环境变量（部署时注入）
    System.getenv("SERVER_IP")?.let { if (it.isNotBlank()) return it }
    // 2. 尝试从网络接口获取本机IP
    try {
        val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val intf = interfaces.nextElement()
            val addrs = intf.inetAddresses
            while (addrs.hasMoreElements()) {
                val addr = addrs.nextElement()
                if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                    val ip = addr.hostAddress ?: continue
                    if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) return ip
                }
            }
        }
    } catch (_: Exception) {}
    // 3. 兜底
    return "127.0.0.1"
}

fun cors(call: io.ktor.server.application.ApplicationCall) {
    call.response.headers.append("Access-Control-Allow-Origin", "*")
    call.response.headers.append("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
    call.response.headers.append("Access-Control-Allow-Headers", "Content-Type, Authorization, x-api-key, anthropic-version, x-goog-api-key")
}

fun openAIErrorRaw(status: Int, message: String, type: String = "invalid_request_error", code: Int? = null): String {
    return buildJsonObject {
        put("error", buildJsonObject {
            put("message", JsonPrimitive(message))
            put("type", JsonPrimitive(type))
            put("param", JsonNull)
            put("code", if (code != null) JsonPrimitive(code) else JsonNull)
        })
    }.toString()
}