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
 * 綦桐AI网关 · Docker 服务器版 v3.18.22-1
 * Web后台(18080) + 网关API(18889)
 */
fun main(args: Array<String>) {
    val webPort = System.getenv("WEB_PORT")?.toIntOrNull() ?: 18080
    val gatewayPort = System.getenv("GATEWAY_PORT")?.toIntOrNull() ?: 18889
    val dbPath = System.getenv("DB_PATH") ?: "/data/qitong/gateway.db"

    File(dbPath).parentFile?.mkdirs()

    println("""
        ╔══════════════════════════════════════════╗
        ║   綦桐AI网关 · Docker Server v3.18.22-1    ║
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
                put("version", JsonPrimitive("3.18.22-1"))
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
            val result = AuthManager.register(
                database,
                body["username"]?.jsonPrimitive?.content ?: "",
                body["password"]?.jsonPrimitive?.content ?: "",
                body["displayName"]?.jsonPrimitive?.content ?: ""
            )
            if (result.isSuccess) AdminApi.ok(call, "注册成功")
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
            AuthManager.logout(token ?: "")
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
        get("/api/providers") { val u = call.requireAuth(database) ?: return@get; AdminApi.ok(call, AdminApi.getProviders(database)) }
        post("/api/providers") {
            if (call.requireAuth(database) == null) return@post
            val body = call.receive<JsonObject>()
            AdminApi.ok(call, mapOf("id" to AdminApi.saveProvider(database, body)), "保存成功")
        }
        delete("/api/providers/{id}") {
            val id = call.parameters["id"]?.toLongOrNull() ?: return@delete
            if (call.requireAuth(database) == null) return@delete
            AdminApi.deleteProvider(database, id)
            AdminApi.ok(call, null, "已删除")
        }
        post("/api/providers/{id}/sync") {
            val id = call.parameters["id"]?.toLongOrNull() ?: return@post
            if (call.requireAuth(database) == null) return@post
            val count = AdminApi.syncProviderModels(database, id)
            if (count > 0) AdminApi.ok(call, mapOf("synced" to count), "同步成功 $count 个模型")
            else AdminApi.fail(call, "同步失败或没有新模型", 400)
        }

        // 模型
        get("/api/models") { val u = call.requireAuth(database) ?: return@get; AdminApi.ok(call, AdminApi.getModels(database)) }
        post("/api/models") {
            if (call.requireAuth(database) == null) return@post
            val body = call.receive<JsonObject>()
            AdminApi.ok(call, mapOf("id" to AdminApi.saveModel(database, body)), "保存成功")
        }
        delete("/api/models/{id}") {
            val id = call.parameters["id"]?.toLongOrNull() ?: return@delete
            if (call.requireAuth(database) == null) return@delete
            AdminApi.deleteModel(database, id)
            AdminApi.ok(call, null, "已删除")
        }
        // 模型启用/停用
        post("/api/models/toggle") {
            if (call.requireAuth(database) == null) return@post
            val body = call.receive<JsonObject>()
            val id = body["id"]?.jsonPrimitive?.content?.toLongOrNull() ?: run { AdminApi.fail(call, "模型ID无效", 400); return@post }
            val model = database.getModelById(id) ?: run { AdminApi.fail(call, "模型不存在", 404); return@post }
            val newEnabled = body["enabled"]?.let {
                when { it is JsonPrimitive && it.isString -> it.content == "true" || it.content == "1"; else -> !model.isEnabled }
            } ?: !model.isEnabled
            database.updateModel(model.copy(isEnabled = newEnabled))
            AdminApi.ok(call, mapOf("id" to id, "enabled" to newEnabled), if (newEnabled) "模型已启用" else "模型已停用")
        }
        // 批量测速（原APP方式：逐个串行，通过自动启用，失败可选自动关闭）
        post("/api/speedtest/batch") {
            if (call.requireAuth(database) == null) return@post
            val body = runCatching { call.receive<JsonObject>() }.getOrElse { buildJsonObject { } }
            val autoClose = body["autoClose"]?.let {
                when { it is JsonPrimitive && it.isString -> it.content == "true" || it.content == "1"; else -> false }
            } ?: false
            val results = AdminApi.batchSpeedTest(database, autoClose)
            AdminApi.ok(call, results, "批量测速完成")
        }
        post("/api/models/sync/{providerId}") {
            val pid = call.parameters["providerId"]?.toLongOrNull() ?: return@post
            if (call.requireAuth(database) == null) return@post
            val count = AdminApi.syncProviderModels(database, pid)
            AdminApi.ok(call, mapOf("synced" to count), "同步完成")
        }

        // 测速
        post("/api/speedtest") {
            if (call.requireAuth(database) == null) return@post
            AdminApi.ok(call, AdminApi.speedTest(database), "测速完成")
        }

        // 配置
        get("/api/config") { val u = call.requireAuth(database) ?: return@get; AdminApi.ok(call, AdminApi.getAllConfig(database)) }
        post("/api/config") {
            if (call.requireAuth(database) == null) return@post
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
        get("/api/keys") { val u = call.requireAuth(database) ?: return@get; AdminApi.ok(call, AdminApi.getApiKeys(database)) }
        post("/api/keys") {
            if (call.requireAuth(database) == null) return@post
            val body = call.receive<JsonObject>()
            if (AdminApi.addApiKey(database, body)) AdminApi.ok(call, null, "添加成功")
            else AdminApi.fail(call, "密钥已存在或无效", 400)
        }
        delete("/api/keys/{key}") {
            val key = call.parameters["key"] ?: return@delete
            if (call.requireAuth(database) == null) return@delete
            AdminApi.deleteApiKey(database, key)
            AdminApi.ok(call, null, "已删除")
        }

        // 路由规则
        get("/api/rules") { val u = call.requireAuth(database) ?: return@get; AdminApi.ok(call, AdminApi.getRules(database)) }
        post("/api/rules") {
            if (call.requireAuth(database) == null) return@post
            val body = call.receive<JsonObject>()
            AdminApi.ok(call, mapOf("id" to AdminApi.saveRule(database, body)), "保存成功")
        }
        delete("/api/rules/{id}") {
            val id = call.parameters["id"]?.toLongOrNull() ?: return@delete
            if (call.requireAuth(database) == null) return@delete
            AdminApi.deleteRule(database, id)
            AdminApi.ok(call, null, "已删除")
        }

        // 用户
        get("/api/users") {
            val u = call.requireAuth(database) ?: return@get
            if (u.role != "admin") AdminApi.fail(call, "无权限", 403)
            else AdminApi.ok(call, AdminApi.getUsers(database))
        }
        // 编辑用户（昵称/角色/额度/绑定模型/重置密码）
        post("/api/users/update") {
            val admin = call.requireAuth(database) ?: return@post
            if (admin.role != "admin") { AdminApi.fail(call, "无权限", 403); return@post }
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
                } ?: user.bindModels
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
            val admin = call.requireAuth(database) ?: return@post
            if (admin.role != "admin") { AdminApi.fail(call, "无权限", 403); return@post }
            val body = call.receive<JsonObject>()
            val userId = body["id"]?.jsonPrimitive?.content?.toLongOrNull() ?: return@post
            if (userId == admin.id) { AdminApi.fail(call, "不能删除自己", 400); return@post }
            database.deleteUser(userId)
            AdminApi.ok(call, null, "用户已删除")
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
        // 密钥编辑（启用/停用/放模型）
        post("/api/keys/update") {
            if (call.requireAuth(database) == null) return@post
            val body = call.receive<JsonObject>()
            val key = body["key"]?.jsonPrimitive?.content ?: run { AdminApi.fail(call, "密钥无效", 400); return@post }
            val entry = database.getApiKeys().find { it.key == key } ?: run { AdminApi.fail(call, "密钥不存在", 404); return@post }
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

        // 聊天
        get("/api/conversations") { val u = call.requireAuth(database) ?: return@get; AdminApi.ok(call, AdminApi.getConversations(database)) }
        post("/api/conversations") {
            if (call.requireAuth(database) == null) return@post
            val body = call.receive<JsonObject>()
            AdminApi.ok(call, mapOf("id" to AdminApi.createConversation(database, body["title"]?.jsonPrimitive?.content ?: "新对话")), "创建成功")
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
            if (call.requireAuth(database) == null) return@post
            val body = call.receive<JsonObject>()
            AdminApi.ok(call, AdminApi.chat(database, body), "完成")
        }

        // 状态
        get("/api/status") {
            AdminApi.respondJson(call, buildJsonObject {
                put("code", JsonPrimitive(0)); put("msg", JsonPrimitive("ok"))
                put("data", buildJsonObject {
                    put("status", JsonPrimitive("ok"))
                    put("version", JsonPrimitive("3.18.22-1"))
                    put("running", JsonPrimitive(GatewayProxy.running))
                    put("uptime", JsonPrimitive((System.currentTimeMillis() - GatewayProxy.startTime) / 1000))
                    put("requireApiKey", JsonPrimitive(database.getConfig("require_api_key", "true").toBoolean()))
                    put("autoFailover", JsonPrimitive(database.getConfig("auto_failover", "true").toBoolean()))
                    // 网关地址
                    put("gatewayPort", JsonPrimitive(database.getConfig("gateway_port", "18889")))
                    put("serverIp", JsonPrimitive(serverIp()))
                    put("localAddr", JsonPrimitive("http://" + serverIp() + ":" + database.getConfig("gateway_port", "18889") + "/v1"))
                    // 活跃模型
                    put("activeModel", JsonPrimitive(database.getConfig("active_model_key", "").substringAfter("::", "")))
                    // 强制故障池
                    put("forcedPool", JsonArray(database.getConfig("forced_pool_keys", "").split(",").filter { it.isNotBlank() }.map { JsonPrimitive(it) }))
                    // 自动测速
                    put("autoSpeedTest", JsonPrimitive(database.getConfig("auto_speedtest", "false").toBoolean()))
                    put("speedIntervalMin", JsonPrimitive(database.getConfig("speed_interval_min", "240")))
                    // ③ 排行榜数据源：全部已启用模型 + 健康缓存三指标
                    // 对齐原APP：所有已启用模型全显示（含待测速/失败），按健康+延迟排序
                    val allModels = database.getModels()
                    val enabledModels = allModels.filter { it.isEnabled }
                    val pipSorted = GatewayScheduler.buildPipelineSortedModels(database)
                    val sortedAll = pipSorted + enabledModels
                        .filter { GatewayScheduler.routeKey(it.providerId, it.modelId) !in pipSorted }
                        .map { GatewayScheduler.routeKey(it.providerId, it.modelId) }
                    put("pipelineSorted", JsonArray(sortedAll.map { JsonPrimitive(it) }))
                    put("healthCache", JsonArray(synchronized(GatewayScheduler.healthCache) {
                        GatewayScheduler.healthCache.entries.map { (k, v) ->
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

        // 网关启停控制
        post("/api/gateway/toggle") {
            if (call.requireAuth(database) == null) return@post
            val body = call.receive<JsonObject>()
            val action = body["action"]?.jsonPrimitive?.content ?: "toggle"
            val newState = GatewayProxy.toggleGateway(action, database)
            AdminApi.ok(call, mapOf("running" to newState), if (newState) "网关已启动" else "网关已停止")
        }

        // 设置活跃模型（qtai-sj 解析目标）
        post("/api/gateway/active-model") {
            if (call.requireAuth(database) == null) return@post
            val body = call.receive<JsonObject>()
            val key = body["modelKey"]?.jsonPrimitive?.content ?: ""
            if (key.isNotBlank()) database.setConfig("active_model_key", key)
            AdminApi.ok(call, null, "已设置活跃模型")
        }

        // 强制故障池：添加/移除/清空
        post("/api/gateway/forced-pool") {
            if (call.requireAuth(database) == null) return@post
            val body = call.receive<JsonObject>()
            val action = body["action"]?.jsonPrimitive?.content ?: "add"
            val modelKey = body["modelKey"]?.jsonPrimitive?.content ?: ""
            val current = database.getConfig("forced_pool_keys", "").split(",").filter { it.isNotBlank() }.toMutableList()
            when (action) {
                "add" -> if (modelKey.isNotBlank() && modelKey !in current) current.add(modelKey)
                "remove" -> current.remove(modelKey)
                "clear" -> current.clear()
            }
            database.setConfig("forced_pool_keys", current.joinToString(","))
            AdminApi.ok(call, mapOf("forcedPool" to current), "已更新强制故障池")
        }

        // 自动测速开关 + 间隔
        post("/api/gateway/auto-speedtest") {
            if (call.requireAuth(database) == null) return@post
            val body = call.receive<JsonObject>()
            val enabled = body["enabled"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
            val interval = body["intervalMin"]?.jsonPrimitive?.content?.toIntOrNull() ?: 240
            database.setConfig("auto_speedtest", enabled.toString())
            database.setConfig("speed_interval_min", interval.toString())
            // 启动/停止自动测速协程
            GatewayProxy.setAutoSpeedTest(enabled, interval, database)
            AdminApi.ok(call, null, if (enabled) "自动测速已开启（每 ${interval} 分钟）" else "自动测速已停止")
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