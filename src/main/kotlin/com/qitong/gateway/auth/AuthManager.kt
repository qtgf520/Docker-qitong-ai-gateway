package com.qitong.gateway.auth

import com.qitong.gateway.db.Database
import com.qitong.gateway.model.User
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import io.ktor.util.AttributeKey
import org.mindrot.jbcrypt.BCrypt
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * 后台认证 —— 注册/登录/会话Token
 * 密码 BCrypt 哈希，会话用随机Token（内存存储，重启失效）
 */
object AuthManager {

    private val sessions = ConcurrentHashMap<String, Long>()  // token -> userId
    private val random = SecureRandom()

    /** 注册新用户 */
    fun register(database: Database, username: String, password: String, displayName: String = ""): Result<User> {
        val name = username.trim()
        if (name.length < 3) return Result.failure(Exception("用户名至少3个字符"))
        if (password.length < 6) return Result.failure(Exception("密码至少6个字符"))
        if (database.getUserByUsername(name) != null) return Result.failure(Exception("用户名已存在"))

        val hash = BCrypt.hashpw(password, BCrypt.gensalt())
        val id = database.addUser(name, hash, role = "user", displayName = displayName.ifBlank { name })
        val user = database.getUserById(id)
        return if (user != null) Result.success(user) else Result.failure(Exception("注册失败"))
    }

    /** 登录，成功返回会话token */
    fun login(database: Database, username: String, password: String): Result<String> {
        val user = database.getUserByUsername(username.trim()) ?: return Result.failure(Exception("用户名或密码错误"))
        if (!BCrypt.checkpw(password, user.passwordHash)) return Result.failure(Exception("用户名或密码错误"))
        database.updateUserLogin(user.id)
        val token = generateToken()
        sessions[token] = user.id
        return Result.success(token)
    }

    /** 修改密码：验证旧密码，设置新密码 */
    fun changePassword(database: Database, user: User, oldPassword: String, newPassword: String): Result<Unit> {
        if (newPassword.length < 6) return Result.failure(Exception("新密码至少6个字符"))
        if (!BCrypt.checkpw(oldPassword, user.passwordHash)) return Result.failure(Exception("旧密码错误"))
        val newHash = BCrypt.hashpw(newPassword, BCrypt.gensalt())
        database.updateUserPassword(user.id, newHash)
        return Result.success(Unit)
    }

    /** 注销 */
    fun logout(token: String) {
        sessions.remove(token)
    }

    /** 校验token，返回用户 */
    fun getUserByToken(database: Database, token: String?): User? {
        if (token.isNullOrBlank()) return null
        val userId = sessions[token] ?: return null
        return database.getUserById(userId)
    }

    private fun generateToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** 获取当前登录用户（从请求） */
    fun currentUser(call: ApplicationCall, database: Database): User? {
        val token = call.request.headers["Authorization"]?.removePrefix("Bearer ")?.trim()
            ?: call.request.cookies["qt_session"]
        return getUserByToken(database, token)
    }
}

/** 路由鉴权：从请求头/请求参数拿到用户 */
fun ApplicationCall.authUser(database: Database): User? = AuthManager.currentUser(this, database)

suspend fun ApplicationCall.requireAuth(database: Database): User? {
    val user = authUser(database)
    if (user == null) {
        try {
            respondText(
                """{"error":"unauthorized","message":"请先登录"}""",
                io.ktor.http.ContentType.Application.Json,
                HttpStatusCode.Unauthorized
            )
        } catch (_: Exception) {}
    }
    return user
}