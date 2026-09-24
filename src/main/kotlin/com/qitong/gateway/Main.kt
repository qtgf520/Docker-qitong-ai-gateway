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
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import java.io.File

/**
 * 綦桐AI网关 · Docker 服务器版 v3.18.22-16
 * Web后台(18080) + 网关API(18889)
 */
fun main(args: Array<String>) {
    val webPort = System.getenv("WEB_PORT")?.toIntOrNull() ?: 18080
    val gatewayPort = System.getenv("GATEWAY_PORT")?.toIntOrNull() ?: 18889
    val dbPath = System.getenv("DB_PATH") ?: "/data/qitong/gateway.db"

    File(dbPath).parentFile?.mkdirs()

    println("""
        ╔══════════════════════════════════════════╗
        ║   綦桐AI网关 · Docker Server v3.18.22-16    ║
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
                put("version", JsonPrimitive("3.18.22-16"))
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
                if (user != null) database.addOpLog(user.id, user.username, "登录", "后台登录成功", call.request.local.remoteHost)
                AdminApi.ok(call, mapOf(
                    "token" to result.getOrNull(),
                    "user" to (user?.let { mapOf("id" to it.id, "username" to it.username, "role" to it.role, "displayName" to it.displayName) })
                ), "登录成功")
            } else {
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
        // 待测速模型列表（测速页默认渲染全部已启用模型为"待测速"）
        get("/api/speedtest/models") {
            val u = call.requireAuth(database) ?: return@get
            AdminApi.ok(call, AdminApi.getSpeedTestModels(database), "ok")
        }
        // 单模型测速（前端逐个调用，测一个显示一个，对齐原APP缓冲流式刷新）
        post("/api/speedtest/one") {
            val u = call.requireAuth(database) ?: return@post
            if (!AdminApi.hasPerm(u, AdminApi.Perm.SYS_SPEED)) { AdminApi.fail(call, "无权限执行测速", 403); return@post }
            val body = runCatching { call.receive<JsonObject>() }.getOrElse { buildJsonObject { } }
            val providerId = body["providerId"]?.jsonPrimitive?.content?.toLongOrNull() ?: -1
            val modelId = body["modelId"]?.jsonPrimitive?.content ?: ""
            AdminApi.ok(call, AdminApi.speedTestOneModel(database, providerId, modelId), "ok")
        }
        // 传输明细（每次调用一条：上传/下载/token，对齐原APP TokenUsage）
        get("/api/usage/recent") {
            val u = call.requireAuth(database) ?: return@get
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 200
            AdminApi.ok(call, AdminApi.getUsageRecent(database, limit), "ok")
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

        // 统计
        get("/api/stats") {
            val u = call.requireAuth(database) ?: return@get
            AdminApi.respondJson(call, buildJsonObject {
                put("code", JsonPrimitive(0)); put("msg", JsonPrimitive("ok"))
                put("data", AdminApi.getStats(database))
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

        // 用户（admin 或 users.manage 权限）
        get("/api/users") {
            val u = call.requireAuth(database) ?: return@get
            if (!AdminApi.hasPerm(u, AdminApi.Perm.U_MANAGE)) AdminApi.fail(call, "无权限", 403)
            else AdminApi.ok(call, AdminApi.getUsers(database))
        }
        // 编辑用户（昵称/角色/额度/绑定模型/权限/重置密码）
        post("/api/users/update") {
            val u = call.requireAuth(database) ?: return@post
            if (!AdminApi.hasPerm(u, AdminApi.Perm.U_MANAGE)) { AdminApi.fail(call, "无权限", 403); return@post }
            val body = call.receive<JsonObject>()
            val userId = body["id"]?.jsonPrimitive?.content?.toLongOrNull() ?: return@post
            val user = database.getUserById(userId) ?: run { AdminApi.fail(call, "用户不存在", 404); return@post }
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
        // 删除用户
        post("/api/users/delete") {
            val u = call.requireAuth(database) ?: return@post
            if (!AdminApi.hasPerm(u, AdminApi.Perm.U_MANAGE)) { AdminApi.fail(call, "无权限", 403); return@post }
            val body = call.receive<JsonObject>()
            val userId = body["id"]?.jsonPrimitive?.content?.toLongOrNull() ?: return@post
            if (userId == u.id) { AdminApi.fail(call, "不能删除自己", 400); return@post }
            database.deleteUser(userId)
            AdminApi.ok(call, null, "用户已删除")
        }
        // 用户充值（admin / users.manage）
        post("/api/users/recharge") {
            val u = call.requireAuth(database) ?: return@post
            if (!AdminApi.hasPerm(u, AdminApi.Perm.U_MANAGE)) { AdminApi.fail(call, "无权限", 403); return@post }
            val body = call.receive<JsonObject>()
            val userId = body["id"]?.jsonPrimitive?.content?.toLongOrNull() ?: run { AdminApi.fail(call, "用户ID无效", 400); return@post }
            val amount = body["amount"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: run { AdminApi.fail(call, "金额无效", 400); return@post }
            if (amount <= 0) { AdminApi.fail(call, "金额必须大于0", 400); return@post }
            if (database.rechargeBalance(userId, amount)) {
                val bal = database.getUserBalance(userId)
                AdminApi.ok(call, mapOf("balance" to bal), "充值成功，当前余额 ¥$bal")
            } else AdminApi.fail(call, "充值失败", 400)
        }
        // 管理员手动扣款
        post("/api/users/deduct") {
            val u = call.requireAuth(database) ?: return@post
            if (!AdminApi.hasPerm(u, AdminApi.Perm.U_MANAGE)) { AdminApi.fail(call, "无权限", 403); return@post }
            val body = call.receive<JsonObject>()
            val userId = body["id"]?.jsonPrimitive?.content?.toLongOrNull() ?: run { AdminApi.fail(call, "用户ID无效", 400); return@post }
            val amount = body["amount"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: run { AdminApi.fail(call, "金额无效", 400); return@post }
            if (amount <= 0) { AdminApi.fail(call, "金额必须大于0", 400); return@post }
            if (database.deductBalanceAdmin(userId, amount)) {
                val bal = database.getUserBalance(userId)
                AdminApi.ok(call, mapOf("balance" to bal), "已扣款 ¥$amount，当前余额 ¥$bal")
            } else AdminApi.fail(call, "扣款失败", 400)
        }
        // 我的分销信息（邀请码/邀请人数/累计佣金）
        get("/api/me/distribution") {
            val u = call.requireAuth(database) ?: return@get
            val inviteCount = database.getInvitedCount(u.id)
            AdminApi.ok(call, mapOf(
                "inviteCode" to (u.inviteCode.ifBlank { "QT" + (System.currentTimeMillis() % 1000000000L).toString().padStart(9, '0') }),
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
        // 个人中心：我的资料（邮箱/昵称/通知开关）
        get("/api/me/profile") {
            val u = call.requireAuth(database) ?: return@get
            AdminApi.ok(call, mapOf(
                "id" to u.id,
                "username" to u.username,
                "displayName" to u.displayName,
                "role" to u.role,
                "email" to u.email,
                "notifyEnabled" to u.notifyEnabled,
                "balance" to u.balance,
                "totalRecharge" to u.totalRecharge,
                "inviteCode" to u.inviteCode,
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
                put("version", JsonPrimitive("3.18.22-16"))
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
        // ===== 操作日志（仅管理员） =====
        get("/api/logs") {
            val user = call.requireAuth(database) ?: return@get
            if (user.role != "admin") { AdminApi.fail(call, "无权限", 403); return@get }
            AdminApi.ok(call, database.getOpLogs(), "ok")
        }
        post("/api/logs/clear") {
            val user = call.requireAuth(database) ?: return@post
            if (user.role != "admin") { AdminApi.fail(call, "无权限", 403); return@post }
            database.clearOpLogs()
            AdminApi.ok(call, null, "日志已清空")
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
                        if (modelKey in current) current.remove(modelKey) else current.add(modelKey)
                        database.setUserConfig(user.id, "forced_pool_keys", current.joinToString(","))
                        AdminApi.ok(call, mapOf("forcedPool" to current), "强制池已更新")
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
        post("/api/chat") {
            val u = call.requireAuth(database) ?: return@post
            val body = call.receive<JsonObject>()
            AdminApi.ok(call, AdminApi.chat(database, body, u), "完成")
        }

        // 状态
        get("/api/status") {
            val viewer = call.authUser(database)   // 当前登录用户（可能为null=本地/未登录）
            val viewerId = viewer?.id ?: 0L
            val isAdmin = viewer?.role == "admin"
            AdminApi.respondJson(call, buildJsonObject {
                put("code", JsonPrimitive(0)); put("msg", JsonPrimitive("ok"))
                put("data", buildJsonObject {
                    put("status", JsonPrimitive("ok"))
                    put("version", JsonPrimitive("3.18.22-16"))
                    put("running", JsonPrimitive(GatewayProxy.running))
                    put("uptime", JsonPrimitive((System.currentTimeMillis() - GatewayProxy.startTime) / 1000))
                    put("requireApiKey", JsonPrimitive(database.getConfig("require_api_key", "true").toBoolean()))
                    put("autoFailover", JsonPrimitive(database.getConfig("auto_failover", "true").toBoolean()))
                    // 网关地址
                    put("gatewayPort", JsonPrimitive(database.getConfig("gateway_port", "18889")))
                    put("serverIp", JsonPrimitive(serverIp()))
                    put("localAddr", JsonPrimitive("http://" + serverIp() + ":" + database.getConfig("gateway_port", "18889") + "/v1"))
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
            if (call.requireAuth(database) == null) return@post
            val body = call.receive<JsonObject>()
            val key = body["modelKey"]?.jsonPrimitive?.content ?: ""
            if (key.isNotBlank()) database.setConfig("active_model_key", key)
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
                    if (modelKey.isNotBlank() && modelKey !in current) current.add(0, modelKey) // 点灯=置首位
                    if (modelKey.isNotBlank()) database.setConfig("active_model_key", modelKey)
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