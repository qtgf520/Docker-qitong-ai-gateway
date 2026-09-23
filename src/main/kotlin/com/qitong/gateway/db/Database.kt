package com.qitong.gateway.db

import com.qitong.gateway.model.*
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.Statement

/**
 * 数据访问层 —— 使用 JDBC + SQLite 对齐原 APP 的 Room 数据库（7表 + users + config + api_keys）
 */
class Database(private val dbPath: String) {

    @Volatile
    private var connRef: Connection? = null

    private val conn: Connection
        get() {
            connRef?.let { return it }
            synchronized(this) {
                connRef?.let { return it }
                Class.forName("org.sqlite.JDBC")
                val c = DriverManager.getConnection("jdbc:sqlite:$dbPath")
                c.createStatement().use { st ->
                    st.execute("PRAGMA journal_mode=WAL")
                    st.execute("PRAGMA foreign_keys=ON")
                }
                connRef = c
                return c
            }
        }

    init {
        createTables()
        seedDefaults()
    }

    fun close() {
        synchronized(this) {
            connRef?.let { runCatching { it.close() } }
            connRef = null
        }
    }

    /** 建表（对齐原APP Room 实体 + 新增 users/config/api_keys） */
    private fun createTables() {
        conn.createStatement().use { st ->
            // 服务商
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS providers (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL,
                    type TEXT NOT NULL,
                    base_url TEXT NOT NULL,
                    port TEXT NOT NULL DEFAULT '',
                    api_key TEXT,
                    is_enabled INTEGER NOT NULL DEFAULT 1,
                    order_index INTEGER NOT NULL DEFAULT 0,
                    chat_path TEXT,
                    supports_system_role INTEGER NOT NULL DEFAULT 0,
                    custom_id TEXT NOT NULL DEFAULT ''
                )"""
            )
            // 模型
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS models (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    provider_id INTEGER NOT NULL,
                    model_id TEXT NOT NULL,
                    display_name TEXT NOT NULL,
                    is_default INTEGER NOT NULL DEFAULT 0,
                    sync_status TEXT NOT NULL DEFAULT 'Pending',
                    is_enabled INTEGER NOT NULL DEFAULT 1,
                    custom_alias TEXT NOT NULL DEFAULT '',
                    use_proxy INTEGER NOT NULL DEFAULT 1,
                    context_window INTEGER NOT NULL DEFAULT 4096,
                    FOREIGN KEY(provider_id) REFERENCES providers(id) ON DELETE CASCADE
                )"""
            )
            // 会话
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS conversations (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    title TEXT NOT NULL DEFAULT '新对话',
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    user_id INTEGER
                )"""
            )
            // 聊天消息
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS chat_messages (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    conversation_id INTEGER NOT NULL,
                    role TEXT NOT NULL,
                    content TEXT NOT NULL,
                    model_id TEXT NOT NULL DEFAULT '',
                    created_at INTEGER NOT NULL,
                    FOREIGN KEY(conversation_id) REFERENCES conversations(id) ON DELETE CASCADE
                )"""
            )
            // 用量统计
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS token_usage (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    model_key TEXT NOT NULL,
                    model_name TEXT NOT NULL DEFAULT '',
                    provider_id INTEGER NOT NULL DEFAULT 0,
                    prompt_tokens INTEGER NOT NULL DEFAULT 0,
                    completion_tokens INTEGER NOT NULL DEFAULT 0,
                    total_tokens INTEGER NOT NULL DEFAULT 0,
                    upload_bytes INTEGER NOT NULL DEFAULT 0,
                    download_bytes INTEGER NOT NULL DEFAULT 0,
                    api_key_label TEXT NOT NULL DEFAULT '',
                    created_at INTEGER NOT NULL
                )"""
            )
            // 测速历史
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS speed_history (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    model_key TEXT NOT NULL,
                    model_name TEXT NOT NULL,
                    provider_id INTEGER NOT NULL,
                    ttft_ms INTEGER NOT NULL,
                    tps REAL NOT NULL,
                    total_ms INTEGER NOT NULL,
                    success INTEGER NOT NULL,
                    measured_at INTEGER NOT NULL
                )"""
            )
            // 路由规则
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS routing_rule (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL,
                    enabled INTEGER NOT NULL DEFAULT 1,
                    priority INTEGER NOT NULL DEFAULT 0,
                    path_pattern TEXT NOT NULL DEFAULT '',
                    model_pattern TEXT NOT NULL DEFAULT '',
                    api_key_pattern TEXT NOT NULL DEFAULT '',
                    provider_id INTEGER,
                    target_model_key TEXT NOT NULL DEFAULT '',
                    action TEXT NOT NULL DEFAULT 'route',
                    block_message TEXT NOT NULL DEFAULT '',
                    created_at INTEGER NOT NULL
                )"""
            )
            // 后台用户（新增）
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS users (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    username TEXT NOT NULL UNIQUE,
                    password_hash TEXT NOT NULL,
                    role TEXT NOT NULL DEFAULT 'admin',
                    display_name TEXT NOT NULL DEFAULT '',
                    created_at INTEGER NOT NULL,
                    last_login_at INTEGER NOT NULL DEFAULT 0,
                    quota_limit INTEGER NOT NULL DEFAULT 0,
                    quota_used INTEGER NOT NULL DEFAULT 0,
                    bind_models TEXT NOT NULL DEFAULT '[]'
                )"""
            )
            // 兼容旧库：补齐 owner_id（0=系统资源，>0=用户私有）与权限列
            try { st.executeUpdate("ALTER TABLE providers ADD COLUMN owner_id INTEGER NOT NULL DEFAULT 0") } catch (_: Exception) {}
            try { st.executeUpdate("ALTER TABLE providers ADD COLUMN is_public INTEGER NOT NULL DEFAULT 0") } catch (_: Exception) {}
            try { st.executeUpdate("ALTER TABLE models ADD COLUMN owner_id INTEGER NOT NULL DEFAULT 0") } catch (_: Exception) {}
            try { st.executeUpdate("ALTER TABLE models ADD COLUMN is_public INTEGER NOT NULL DEFAULT 0") } catch (_: Exception) {}
            try { st.executeUpdate("ALTER TABLE models ADD COLUMN price REAL NOT NULL DEFAULT 0") } catch (_: Exception) {}
            try { st.executeUpdate("ALTER TABLE routing_rule ADD COLUMN owner_id INTEGER NOT NULL DEFAULT 0") } catch (_: Exception) {}
            try { st.executeUpdate("ALTER TABLE api_keys ADD COLUMN owner_id INTEGER NOT NULL DEFAULT 0") } catch (_: Exception) {}
            try { st.executeUpdate("ALTER TABLE users ADD COLUMN permissions TEXT NOT NULL DEFAULT '[]'") } catch (_: Exception) {}
            try { st.executeUpdate("ALTER TABLE users ADD COLUMN balance REAL NOT NULL DEFAULT 0") } catch (_: Exception) {}
            try { st.executeUpdate("ALTER TABLE users ADD COLUMN total_recharge REAL NOT NULL DEFAULT 0") } catch (_: Exception) {}
            try { st.executeUpdate("ALTER TABLE users ADD COLUMN inviter_id INTEGER NOT NULL DEFAULT 0") } catch (_: Exception) {}
            try { st.executeUpdate("ALTER TABLE users ADD COLUMN invite_code TEXT NOT NULL DEFAULT ''") } catch (_: Exception) {}
            try { st.executeUpdate("ALTER TABLE users ADD COLUMN commission_rate REAL NOT NULL DEFAULT 0.1") } catch (_: Exception) {}
            try { st.executeUpdate("ALTER TABLE token_usage ADD COLUMN user_id INTEGER NOT NULL DEFAULT 0") } catch (_: Exception) {}
            try { st.executeUpdate("ALTER TABLE token_usage ADD COLUMN cost REAL NOT NULL DEFAULT 0") } catch (_: Exception) {}
            // 人格配置表（对齐原APP人设系统）
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS persona (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    user_id INTEGER NOT NULL DEFAULT 0,
                    name TEXT NOT NULL DEFAULT '',
                    age TEXT NOT NULL DEFAULT '',
                    personality TEXT NOT NULL DEFAULT '',
                    tone TEXT NOT NULL DEFAULT '',
                    background TEXT NOT NULL DEFAULT '',
                    openness REAL NOT NULL DEFAULT 0.5,
                    conscientiousness REAL NOT NULL DEFAULT 0.5,
                    extraversion REAL NOT NULL DEFAULT 0.5,
                    agreeableness REAL NOT NULL DEFAULT 0.5,
                    neuroticism REAL NOT NULL DEFAULT 0.5,
                    memory_enabled INTEGER NOT NULL DEFAULT 1,
                    updated_at INTEGER NOT NULL
                )"""
            )
            // 网关配置（替代 SharedPreferences）
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS gateway_config (
                    key TEXT PRIMARY KEY,
                    value TEXT NOT NULL DEFAULT ''
                )"""
            )
            // API密钥（替代 KeyManager 的SP存储）
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS api_keys (
                    key TEXT PRIMARY KEY,
                    label TEXT NOT NULL DEFAULT '',
                    enabled INTEGER NOT NULL DEFAULT 1,
                    allowed_models TEXT NOT NULL DEFAULT '[]',
                    qtai_sj_access INTEGER NOT NULL DEFAULT 1,
                    created_at INTEGER NOT NULL
                )"""
            )
            // 登录会话（7天免登录）
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS sessions (
                    token TEXT PRIMARY KEY,
                    user_id INTEGER NOT NULL,
                    expires_at INTEGER NOT NULL
                )"""
            )
            // 大脑记忆（按用户隔离，对齐原APP BrainMemoryManager）
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS brain_memory (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    user_id INTEGER NOT NULL DEFAULT 0,
                    title TEXT NOT NULL DEFAULT '',
                    content TEXT NOT NULL DEFAULT '',
                    type TEXT NOT NULL DEFAULT 'short',
                    emotion TEXT NOT NULL DEFAULT 'neutral',
                    importance INTEGER NOT NULL DEFAULT 5,
                    timestamp INTEGER NOT NULL,
                    access_count INTEGER NOT NULL DEFAULT 0,
                    source TEXT NOT NULL DEFAULT 'chat',
                    tags TEXT NOT NULL DEFAULT '',
                    model_id TEXT NOT NULL DEFAULT ''
                )"""
            )
            // 记忆配置（每用户一条，含模型独立记忆开关，对齐原APP MemoryConfig）
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS memory_config (
                    user_id INTEGER PRIMARY KEY,
                    enabled INTEGER NOT NULL DEFAULT 1,
                    save_mode TEXT NOT NULL DEFAULT 'normal',
                    empathy_level INTEGER NOT NULL DEFAULT 8,
                    thinking_depth INTEGER NOT NULL DEFAULT 3,
                    catchphrases TEXT NOT NULL DEFAULT '',
                    forbidden_words TEXT NOT NULL DEFAULT '',
                    expertise TEXT NOT NULL DEFAULT '全栈通用',
                    communication_style TEXT NOT NULL DEFAULT '自然亲切、像朋友聊天',
                    model_independent INTEGER NOT NULL DEFAULT 0,
                    updated_at INTEGER NOT NULL
                )"""
            )
            // 公告（管理员发布，首页顶部展示）
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS announcements (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    title TEXT NOT NULL DEFAULT '',
                    content TEXT NOT NULL DEFAULT '',
                    author_id INTEGER NOT NULL DEFAULT 0,
                    is_pinned INTEGER NOT NULL DEFAULT 0,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )"""
            )
            // 工单（用户可提交，管理员可反馈，聊天式）
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS tickets (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    user_id INTEGER NOT NULL,
                    title TEXT NOT NULL DEFAULT '',
                    status TEXT NOT NULL DEFAULT 'open',
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    closed_at INTEGER NOT NULL DEFAULT 0
                )"""
            )
            // 工单消息（跟聊天一样）
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS ticket_messages (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    ticket_id INTEGER NOT NULL,
                    sender_id INTEGER NOT NULL,
                    role TEXT NOT NULL DEFAULT 'user',
                    content TEXT NOT NULL DEFAULT '',
                    created_at INTEGER NOT NULL,
                    FOREIGN KEY(ticket_id) REFERENCES tickets(id) ON DELETE CASCADE
                )"""
            )
            // 操作日志（管理员查看，用户不可见）
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS op_logs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    user_id INTEGER NOT NULL DEFAULT 0,
                    username TEXT NOT NULL DEFAULT '',
                    action TEXT NOT NULL DEFAULT '',
                    detail TEXT NOT NULL DEFAULT '',
                    ip TEXT NOT NULL DEFAULT '',
                    created_at INTEGER NOT NULL
                )"""
            )
        }
    }

    private fun seedDefaults() {
        // 默认管理员账号 admin / admin888（首次启动自动创建）
        val count = queryOne("SELECT COUNT(*) FROM users WHERE username='admin'") ?: 0
        if (count == 0L) {
            val hash = org.mindrot.jbcrypt.BCrypt.hashpw("admin888", org.mindrot.jbcrypt.BCrypt.gensalt())
            stmt("INSERT INTO users (username, password_hash, role, display_name, created_at) VALUES ('admin', ?, 'admin', '管理员', ?)", hash, System.currentTimeMillis())
            println("✅ 已创建默认管理员账号：admin / admin888")
        }
    }

    // ============ 通用工具 ============

    private fun stmt(sql: String, vararg args: Any?) {
        conn.prepareStatement(sql).use { ps ->
            args.forEachIndexed { i, arg -> ps.setObject(i + 1, arg) }
            ps.executeUpdate()
        }
    }

    private fun query(sql: String, vararg args: Any?): List<Map<String, Any?>> {
        conn.prepareStatement(sql).use { ps ->
            args.forEachIndexed { i, arg -> ps.setObject(i + 1, arg) }
            ps.executeQuery().use { rs ->
                val cols = rs.metaData.columnCount
                val list = mutableListOf<Map<String, Any?>>()
                while (rs.next()) {
                    val row = mutableMapOf<String, Any?>()
                    for (c in 1..cols) row[rs.getMetaData().getColumnLabel(c)] = rs.getObject(c)
                    list.add(row)
                }
                return list
            }
        }
    }

    private fun queryOne(sql: String, vararg args: Any?): Long? {
        return query(sql, *args).firstOrNull()?.values?.firstOrNull()?.let {
            when (it) {
                is Number -> it.toLong()
                else -> it.toString().toLongOrNull()
            }
        }
    }

    private fun rowToProvider(r: Map<String, Any?>) = Provider(
        id = (r["id"] as Number).toLong(),
        name = r["name"] as? String ?: "",
        type = r["type"] as? String ?: "",
        baseUrl = r["base_url"] as? String ?: "",
        port = r["port"] as? String ?: "",
        apiKey = r["api_key"] as? String,
        isEnabled = (r["is_enabled"] as? Number)?.toInt() == 1,
        orderIndex = (r["order_index"] as? Number)?.toInt() ?: 0,
        chatPath = r["chat_path"] as? String,
        supportsSystemRole = (r["supports_system_role"] as? Number)?.toInt() == 1,
        customId = r["custom_id"] as? String ?: "",
        ownerId = (r["owner_id"] as? Number)?.toLong() ?: 0,
        isPublic = (r["is_public"] as? Number)?.toInt() == 1
    )

    private fun rowToModel(r: Map<String, Any?>) = AiModel(
        id = (r["id"] as Number).toLong(),
        providerId = (r["provider_id"] as Number).toLong(),
        modelId = r["model_id"] as? String ?: "",
        displayName = r["display_name"] as? String ?: "",
        isDefault = (r["is_default"] as? Number)?.toInt() == 1,
        syncStatus = r["sync_status"] as? String ?: "Pending",
        isEnabled = (r["is_enabled"] as? Number)?.toInt() == 1,
        customAlias = r["custom_alias"] as? String ?: "",
        useProxy = (r["use_proxy"] as? Number)?.toInt() == 1,
        contextWindow = (r["context_window"] as? Number)?.toInt() ?: 4096,
        ownerId = (r["owner_id"] as? Number)?.toLong() ?: 0,
        isPublic = (r["is_public"] as? Number)?.toInt() == 1,
        price = (r["price"] as? Number)?.toDouble() ?: 0.0
    )

    private fun rowToRoute(r: Map<String, Any?>) = RoutingRule(
        id = (r["id"] as Number).toLong(),
        name = r["name"] as? String ?: "",
        enabled = (r["enabled"] as? Number)?.toInt() == 1,
        priority = (r["priority"] as? Number)?.toInt() ?: 0,
        pathPattern = r["path_pattern"] as? String ?: "",
        modelPattern = r["model_pattern"] as? String ?: "",
        apiKeyPattern = r["api_key_pattern"] as? String ?: "",
        providerId = (r["provider_id"] as? Number)?.toLong(),
        targetModelKey = r["target_model_key"] as? String ?: "",
        action = r["action"] as? String ?: "route",
        blockMessage = r["block_message"] as? String ?: "",
        createdAt = (r["created_at"] as? Number)?.toLong() ?: 0,
        ownerId = (r["owner_id"] as? Number)?.toLong() ?: 0
    )

    // ============ 服务商 ============

    fun getProviders(): List<Provider> =
        query("SELECT * FROM providers ORDER BY order_index").map { rowToProvider(it) }

    fun getProviderById(id: Long): Provider? =
        query("SELECT * FROM providers WHERE id=?", id).firstOrNull()?.let { rowToProvider(it) }

    fun addProvider(p: Provider): Long {
        stmt(
            "INSERT INTO providers (name,type,base_url,port,api_key,is_enabled,order_index,chat_path,supports_system_role,custom_id,owner_id,is_public) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
            p.name, p.type, p.baseUrl, p.port, p.apiKey, if (p.isEnabled) 1 else 0, p.orderIndex, p.chatPath, if (p.supportsSystemRole) 1 else 0, p.customId, p.ownerId, if (p.isPublic) 1 else 0
        )
        return lastInsertId()
    }

    fun updateProvider(p: Provider) {
        stmt(
            "UPDATE providers SET name=?,type=?,base_url=?,port=?,api_key=?,is_enabled=?,order_index=?,chat_path=?,supports_system_role=?,custom_id=?,owner_id=?,is_public=? WHERE id=?",
            p.name, p.type, p.baseUrl, p.port, p.apiKey, if (p.isEnabled) 1 else 0, p.orderIndex, p.chatPath, if (p.supportsSystemRole) 1 else 0, p.customId, p.ownerId, if (p.isPublic) 1 else 0, p.id
        )
    }

    fun deleteProvider(id: Long) {
        stmt("DELETE FROM providers WHERE id=?", id)
    }

    /** 仅获取某用户私有服务商 */
    fun getProvidersByOwner(ownerId: Long): List<Provider> =
        query("SELECT * FROM providers WHERE owner_id=? ORDER BY order_index", ownerId).map { rowToProvider(it) }

    /** 系统资源 + 某用户私有资源 */
    fun getVisibleProviders(ownerId: Long): List<Provider> =
        query("SELECT * FROM providers WHERE owner_id=0 OR owner_id=? ORDER BY order_index", ownerId).map { rowToProvider(it) }

    private fun lastInsertId(): Long {
        return queryOne("SELECT last_insert_rowid()") ?: 0
    }

    // ============ 模型 ============

    fun getModels(): List<AiModel> =
        query("SELECT * FROM models ORDER BY id").map { rowToModel(it) }

    fun getEnabledModels(): List<AiModel> =
        query("SELECT * FROM models WHERE is_enabled=1 ORDER BY id").map { rowToModel(it) }

    fun getModelById(id: Long): AiModel? =
        query("SELECT * FROM models WHERE id=?", id).firstOrNull()?.let { rowToModel(it) }

    fun getModelByKey(providerId: Long, modelId: String): AiModel? =
        query("SELECT * FROM models WHERE provider_id=? AND model_id=?", providerId, modelId).firstOrNull()?.let { rowToModel(it) }

    /** 系统资源 + 某用户私有资源 */
    fun getVisibleModels(ownerId: Long): List<AiModel> =
        query("SELECT * FROM models WHERE owner_id=0 OR owner_id=? ORDER BY id", ownerId).map { rowToModel(it) }

    fun addModel(m: AiModel): Long {
        stmt(
            "INSERT INTO models (provider_id,model_id,display_name,is_default,sync_status,is_enabled,custom_alias,use_proxy,context_window,owner_id,is_public,price) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
            m.providerId, m.modelId, m.displayName, if (m.isDefault) 1 else 0, m.syncStatus, if (m.isEnabled) 1 else 0, m.customAlias, if (m.useProxy) 1 else 0, m.contextWindow, m.ownerId, if (m.isPublic) 1 else 0, m.price
        )
        return lastInsertId()
    }

    fun updateModel(m: AiModel) {
        stmt(
            "UPDATE models SET provider_id=?,model_id=?,display_name=?,is_default=?,sync_status=?,is_enabled=?,custom_alias=?,use_proxy=?,context_window=?,owner_id=?,is_public=?,price=? WHERE id=?",
            m.providerId, m.modelId, m.displayName, if (m.isDefault) 1 else 0, m.syncStatus, if (m.isEnabled) 1 else 0, m.customAlias, if (m.useProxy) 1 else 0, m.contextWindow, m.ownerId, if (m.isPublic) 1 else 0, m.price, m.id
        )
    }

    fun deleteModel(id: Long) {
        stmt("DELETE FROM models WHERE id=?", id)
    }

    fun deleteModelsByProvider(providerId: Long) {
        stmt("DELETE FROM models WHERE provider_id=?", providerId)
    }

    // ============ 会话 ============
    fun getConversations(): List<Conversation> =
        query("SELECT * FROM conversations ORDER BY updated_at DESC").map {
            Conversation(
                id = (it["id"] as Number).toLong(),
                title = it["title"] as? String ?: "新对话",
                createdAt = (it["created_at"] as? Number)?.toLong() ?: 0,
                updatedAt = (it["updated_at"] as? Number)?.toLong() ?: 0,
                userId = (it["user_id"] as? Number)?.toLong()
            )
        }

    /** 按用户获取会话（多租户：用户只看自己的） */
    fun getConversationsByUser(userId: Long): List<Conversation> =
        query("SELECT * FROM conversations WHERE user_id=? OR user_id IS NULL ORDER BY updated_at DESC", userId).map {
            Conversation(
                id = (it["id"] as Number).toLong(),
                title = it["title"] as? String ?: "新对话",
                createdAt = (it["created_at"] as? Number)?.toLong() ?: 0,
                updatedAt = (it["updated_at"] as? Number)?.toLong() ?: 0,
                userId = (it["user_id"] as? Number)?.toLong()
            )
        }

    fun getConversationById(id: Long): Conversation? =
        query("SELECT * FROM conversations WHERE id=?", id).firstOrNull()?.let {
            Conversation(
                id = (it["id"] as Number).toLong(),
                title = it["title"] as? String ?: "新对话",
                createdAt = (it["created_at"] as? Number)?.toLong() ?: 0,
                updatedAt = (it["updated_at"] as? Number)?.toLong() ?: 0,
                userId = (it["user_id"] as? Number)?.toLong()
            )
        }

    fun addConversation(title: String, userId: Long? = null): Long {
        val now = System.currentTimeMillis()
        stmt("INSERT INTO conversations (title, created_at, updated_at, user_id) VALUES (?,?,?,?)", title, now, now, userId)
        return lastInsertId()
    }

    fun updateConversationTitle(id: Long, title: String) {
        stmt("UPDATE conversations SET title=?, updated_at=? WHERE id=?", title, System.currentTimeMillis(), id)
    }

    fun deleteConversation(id: Long) {
        stmt("DELETE FROM conversations WHERE id=?", id)
    }

    // ============ 消息 ============

    fun getMessagesByConversation(convId: Long): List<ChatMessage> =
        query("SELECT * FROM chat_messages WHERE conversation_id=? ORDER BY id", convId).map {
            ChatMessage(
                id = (it["id"] as Number).toLong(),
                conversationId = (it["conversation_id"] as Number).toLong(),
                role = it["role"] as? String ?: "user",
                content = it["content"] as? String ?: "",
                modelId = it["model_id"] as? String ?: "",
                createdAt = (it["created_at"] as? Number)?.toLong() ?: 0
            )
        }

    fun addMessage(msg: ChatMessage): Long {
        stmt(
            "INSERT INTO chat_messages (conversation_id, role, content, model_id, created_at) VALUES (?,?,?,?,?)",
            msg.conversationId, msg.role, msg.content, msg.modelId, msg.createdAt
        )
        return lastInsertId()
    }

    // ============ 用量统计 ============

    fun addTokenUsage(t: TokenUsage) {
        stmt(
            "INSERT INTO token_usage (model_key,model_name,provider_id,prompt_tokens,completion_tokens,total_tokens,upload_bytes,download_bytes,api_key_label,user_id,cost,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
            t.modelKey, t.modelName, t.providerId, t.promptTokens, t.completionTokens, t.totalTokens, t.uploadBytes, t.downloadBytes, t.apiKeyLabel, t.userId, t.cost, t.createdAt
        )
    }

    fun getTokenUsageSummary(): List<Map<String, Any?>> =
        query("SELECT model_key, model_name, provider_id, SUM(prompt_tokens) as prompt_tokens, SUM(completion_tokens) as completion_tokens, SUM(total_tokens) as total_tokens, SUM(upload_bytes) as upload_bytes, SUM(download_bytes) as download_bytes, SUM(cost) as cost, COUNT(*) as calls FROM token_usage GROUP BY model_key ORDER BY total_tokens DESC")

    /** 按用户维度的用量汇总（商业化） */
    fun getTokenUsageByUser(userId: Long): List<Map<String, Any?>> =
        query("SELECT model_key, model_name, provider_id, SUM(prompt_tokens) as prompt_tokens, SUM(completion_tokens) as completion_tokens, SUM(total_tokens) as total_tokens, SUM(cost) as cost, COUNT(*) as calls FROM token_usage WHERE user_id=? GROUP BY model_key ORDER BY total_tokens DESC", userId)

    fun getTokenUsageRecent(limit: Int = 200): List<TokenUsage> =
        query("SELECT * FROM token_usage ORDER BY id DESC LIMIT $limit").map {
            TokenUsage(
                id = (it["id"] as Number).toLong(),
                modelKey = it["model_key"] as? String ?: "",
                modelName = it["model_name"] as? String ?: "",
                providerId = (it["provider_id"] as? Number)?.toLong() ?: 0,
                promptTokens = (it["prompt_tokens"] as? Number)?.toLong() ?: 0,
                completionTokens = (it["completion_tokens"] as? Number)?.toLong() ?: 0,
                totalTokens = (it["total_tokens"] as? Number)?.toLong() ?: 0,
                uploadBytes = (it["upload_bytes"] as? Number)?.toLong() ?: 0,
                downloadBytes = (it["download_bytes"] as? Number)?.toLong() ?: 0,
                apiKeyLabel = it["api_key_label"] as? String ?: "",
                createdAt = (it["created_at"] as? Number)?.toLong() ?: 0
            )
        }

    fun clearTokenUsage() {
        stmt("DELETE FROM token_usage")
    }

    // ============ 测速历史 ============

    fun addSpeedHistory(s: SpeedHistory) {
        stmt(
            "INSERT INTO speed_history (model_key,model_name,provider_id,ttft_ms,tps,total_ms,success,measured_at) VALUES (?,?,?,?,?,?,?,?)",
            s.modelKey, s.modelName, s.providerId, s.ttftMs, s.tps, s.totalMs, if (s.success) 1 else 0, s.measuredAt
        )
    }

    fun getSpeedHistoryByModel(modelKey: String, limit: Int = 50): List<SpeedHistory> =
        query("SELECT * FROM speed_history WHERE model_key=? ORDER BY id DESC LIMIT $limit", modelKey).map {
            SpeedHistory(
                id = (it["id"] as Number).toLong(),
                modelKey = it["model_key"] as? String ?: "",
                modelName = it["model_name"] as? String ?: "",
                providerId = (it["provider_id"] as? Number)?.toLong() ?: 0,
                ttftMs = (it["ttft_ms"] as? Number)?.toInt() ?: 0,
                tps = (it["tps"] as? Number)?.toDouble() ?: 0.0,
                totalMs = (it["total_ms"] as? Number)?.toInt() ?: 0,
                success = (it["success"] as? Number)?.toInt() == 1,
                measuredAt = (it["measured_at"] as? Number)?.toLong() ?: 0
            )
        }

    // ============ 路由规则 ============

    fun getRoutingRules(): List<RoutingRule> =
        query("SELECT * FROM routing_rule ORDER BY priority DESC").map { rowToRoute(it) }

    fun getVisibleRoutingRules(ownerId: Long): List<RoutingRule> =
        query("SELECT * FROM routing_rule WHERE owner_id=0 OR owner_id=? ORDER BY priority DESC", ownerId).map { rowToRoute(it) }

    fun addRoutingRule(r: RoutingRule): Long {
        stmt(
            "INSERT INTO routing_rule (name,enabled,priority,path_pattern,model_pattern,api_key_pattern,provider_id,target_model_key,action,block_message,created_at,owner_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
            r.name, if (r.enabled) 1 else 0, r.priority, r.pathPattern, r.modelPattern, r.apiKeyPattern, r.providerId, r.targetModelKey, r.action, r.blockMessage, r.createdAt, r.ownerId
        )
        return lastInsertId()
    }

    fun deleteRoutingRule(id: Long) {
        stmt("DELETE FROM routing_rule WHERE id=?", id)
    }

    // ============ 用户 ============

    fun getUserByUsername(username: String): User? =
        query("SELECT * FROM users WHERE username=?", username).firstOrNull()?.let { rowToUser(it) }

    fun getUserById(id: Long): User? =
        query("SELECT * FROM users WHERE id=?", id).firstOrNull()?.let { rowToUser(it) }

    fun getUserByInviteCode(code: String): User? =
        query("SELECT * FROM users WHERE invite_code=? LIMIT 1", code).firstOrNull()?.let { rowToUser(it) }

    /** 邀请人数（分销） */
    fun getInvitedCount(inviterId: Long): Long =
        queryOne("SELECT COUNT(*) FROM users WHERE inviter_id=?", inviterId) ?: 0

    fun getUsers(): List<User> =
        query("SELECT * FROM users ORDER BY id").map { rowToUser(it) }

    /** 行转User（含额度/绑定模型/权限/余额） */
    private fun rowToUser(it: Map<String, Any?>): User = User(
        id = (it["id"] as Number).toLong(),
        username = it["username"] as? String ?: "",
        passwordHash = it["password_hash"] as? String ?: "",
        role = it["role"] as? String ?: "user",
        displayName = it["display_name"] as? String ?: "",
        createdAt = (it["created_at"] as? Number)?.toLong() ?: 0,
        lastLoginAt = (it["last_login_at"] as? Number)?.toLong() ?: 0,
        quotaLimit = (it["quota_limit"] as? Number)?.toLong() ?: 0,
        quotaUsed = (it["quota_used"] as? Number)?.toLong() ?: 0,
        bindModels = try {
            kotlinx.serialization.json.Json.decodeFromString<List<String>>(it["bind_models"] as? String ?: "[]")
        } catch (_: Exception) { emptyList() },
        permissions = try {
            kotlinx.serialization.json.Json.decodeFromString<List<String>>(it["permissions"] as? String ?: "[]")
        } catch (_: Exception) { emptyList() },
        balance = (it["balance"] as? Number)?.toDouble() ?: 0.0,
        totalRecharge = (it["total_recharge"] as? Number)?.toDouble() ?: 0.0,
        inviterId = (it["inviter_id"] as? Number)?.toLong() ?: 0,
        inviteCode = it["invite_code"] as? String ?: "",
        commissionRate = (it["commission_rate"] as? Number)?.toDouble() ?: 0.1
    )

    fun addUser(username: String, passwordHash: String, role: String = "user", displayName: String = "", inviterId: Long = 0): Long {
        // 生成邀请码
        val code = "QT" + (System.currentTimeMillis() % 1000000000L).toString().padStart(9, '0')
        stmt(
            "INSERT INTO users (username, password_hash, role, display_name, created_at, inviter_id, invite_code) VALUES (?,?,?,?,?,?,?)",
            username, passwordHash, role, displayName, System.currentTimeMillis(), inviterId, code
        )
        return lastInsertId()
    }

    fun updateUserLogin(id: Long) {
        stmt("UPDATE users SET last_login_at=? WHERE id=?", System.currentTimeMillis(), id)
    }

    fun updateUserPassword(id: Long, newHash: String) {
        stmt("UPDATE users SET password_hash=? WHERE id=?", newHash, id)
    }

    fun deleteUser(id: Long) {
        stmt("DELETE FROM users WHERE id=?", id)
    }

    /** 更新用户资料（昵称/角色/额度/绑定模型/权限） */
    fun updateUser(user: User) {
        val bindModels = kotlinx.serialization.json.Json.encodeToString(user.bindModels)
        val permissions = kotlinx.serialization.json.Json.encodeToString(user.permissions)
        stmt(
            "UPDATE users SET role=?, display_name=?, quota_limit=?, quota_used=?, bind_models=?, permissions=? WHERE id=?",
            user.role, user.displayName, user.quotaLimit, user.quotaUsed, bindModels, permissions, user.id
        )
    }

    /** 用户额度增加使用量（返回是否超额，超额则拒绝） */
    fun consumeQuota(userId: Long, tokens: Long): Boolean {
        val user = getUserById(userId) ?: return false
        if (user.quotaLimit <= 0) return true  // 不限额度
        if (user.quotaUsed + tokens > user.quotaLimit) return false  // 超额
        stmt("UPDATE users SET quota_used = quota_used + ? WHERE id=?", tokens, userId)
        return true
    }

    // ============ 商业化：余额/充值/扣费 ============

    /** 充值（增加余额 + 累计充值 + 给邀请人返佣） */
    fun rechargeBalance(userId: Long, amount: Double): Boolean {
        val user = getUserById(userId) ?: return false
        if (amount < 0) return false
        stmt("UPDATE users SET balance = balance + ?, total_recharge = total_recharge + ? WHERE id=?", amount, amount, userId)
        // 分销返佣：邀请人获得 amount * commissionRate 佣金
        if (user.inviterId > 0 && user.inviterId != userId) {
            val inviter = getUserById(user.inviterId)
            if (inviter != null) {
                val commission = amount * (inviter.commissionRate.takeIf { it in 0.0..1.0 } ?: 0.1)
                if (commission > 0) {
                    stmt("UPDATE users SET balance = balance + ? WHERE id=?", commission, user.inviterId)
                }
            }
        }
        return true
    }

    /** 管理员手动扣款（余额扣减，扣成负数也允许标记欠费） */
    fun deductBalanceAdmin(userId: Long, amount: Double): Boolean {
        if (amount <= 0) return false
        stmt("UPDATE users SET balance = balance - ? WHERE id=?", amount, userId)
        return true
    }

    /** 扣费：从余额扣款，返回是否成功（余额不足返回 false） */
    fun deductBalance(userId: Long, amount: Double): Boolean {
        if (amount <= 0) return true
        val user = getUserById(userId) ?: return false
        if (user.balance + 1e-9 < amount) return false  // 余额不足
        stmt("UPDATE users SET balance = balance - ? WHERE id=?", amount, userId)
        return true
    }

    /** 查询用户余额 */
    fun getUserBalance(userId: Long): Double = getUserById(userId)?.balance ?: 0.0

    // ============ 人格配置 ============

    fun getPersona(userId: Long): Persona? =
        query("SELECT * FROM persona WHERE user_id=?", userId).firstOrNull()?.let { rowToPersona(it) }

    private fun rowToPersona(it: Map<String, Any?>): Persona = Persona(
        id = (it["id"] as Number).toLong(),
        userId = (it["user_id"] as? Number)?.toLong() ?: 0,
        name = it["name"] as? String ?: "",
        age = it["age"] as? String ?: "",
        personality = it["personality"] as? String ?: "",
        tone = it["tone"] as? String ?: "",
        background = it["background"] as? String ?: "",
        openness = (it["openness"] as? Number)?.toDouble() ?: 0.5,
        conscientiousness = (it["conscientiousness"] as? Number)?.toDouble() ?: 0.5,
        extraversion = (it["extraversion"] as? Number)?.toDouble() ?: 0.5,
        agreeableness = (it["agreeableness"] as? Number)?.toDouble() ?: 0.5,
        neuroticism = (it["neuroticism"] as? Number)?.toDouble() ?: 0.5,
        memoryEnabled = (it["memory_enabled"] as? Number)?.toInt() == 1,
        updatedAt = (it["updated_at"] as? Number)?.toLong() ?: 0
    )

    /** 保存人格配置（不存在则插入） */
    fun savePersona(p: Persona) {
        val now = System.currentTimeMillis()
        val exists = queryOne("SELECT COUNT(*) FROM persona WHERE user_id=?", p.userId) ?: 0
        if (exists > 0) {
            stmt(
                "UPDATE persona SET name=?, age=?, personality=?, tone=?, background=?, openness=?, conscientiousness=?, extraversion=?, agreeableness=?, neuroticism=?, memory_enabled=?, updated_at=? WHERE user_id=?",
                p.name, p.age, p.personality, p.tone, p.background, p.openness, p.conscientiousness, p.extraversion, p.agreeableness, p.neuroticism,
                if (p.memoryEnabled) 1 else 0, now, p.userId
            )
        } else {
            stmt(
                "INSERT INTO persona (user_id,name,age,personality,tone,background,openness,conscientiousness,extraversion,agreeableness,neuroticism,memory_enabled,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                p.userId, p.name, p.age, p.personality, p.tone, p.background, p.openness, p.conscientiousness, p.extraversion, p.agreeableness, p.neuroticism,
                if (p.memoryEnabled) 1 else 0, now
            )
        }
    }

    // ============ 大脑记忆（按用户隔离） ============

    fun getMemories(userId: Long, limit: Int = 100): List<Map<String, Any?>> =
        query("SELECT * FROM brain_memory WHERE user_id=? ORDER BY importance DESC, timestamp DESC LIMIT $limit", userId).map {
            mapOf(
                "id" to ((it["id"] as Number).toLong()),
                "title" to (it["title"] as? String ?: ""),
                "content" to (it["content"] as? String ?: ""),
                "type" to (it["type"] as? String ?: "short"),
                "emotion" to (it["emotion"] as? String ?: "neutral"),
                "importance" to ((it["importance"] as? Number)?.toInt() ?: 5),
                "timestamp" to ((it["timestamp"] as? Number)?.toLong() ?: 0),
                "accessCount" to ((it["access_count"] as? Number)?.toInt() ?: 0),
                "source" to (it["source"] as? String ?: "chat"),
                "tags" to (it["tags"] as? String ?: ""),
                "modelId" to (it["model_id"] as? String ?: "")
            )
        }

    fun addMemory(userId: Long, title: String, content: String, type: String, emotion: String, importance: Int, source: String, tags: String, modelId: String): Long {
        val now = System.currentTimeMillis()
        stmt(
            "INSERT INTO brain_memory (user_id,title,content,type,emotion,importance,timestamp,source,tags,model_id) VALUES (?,?,?,?,?,?,?,?,?,?)",
            userId, title, content, type, emotion, importance, now, source, tags, modelId
        )
        return lastInsertId()
    }

    fun deleteMemory(id: Long, userId: Long) {
        stmt("DELETE FROM brain_memory WHERE id=? AND user_id=?", id, userId)
    }

    fun clearMemories(userId: Long) {
        stmt("DELETE FROM brain_memory WHERE user_id=?", userId)
    }

    // ============ 配置（替代 SharedPreferences） ============

    fun getConfig(key: String, default: String = ""): String {
        return query("SELECT value FROM gateway_config WHERE key=?", key).firstOrNull()?.values?.firstOrNull()?.toString() ?: default
    }

    fun setConfig(key: String, value: String) {
        val exists = queryOne("SELECT COUNT(*) FROM gateway_config WHERE key=?", key) ?: 0
        if (exists > 0) {
            stmt("UPDATE gateway_config SET value=? WHERE key=?", value, key)
        } else {
            stmt("INSERT INTO gateway_config (key, value) VALUES (?,?)", key, value)
        }
    }

    /** 用户级配置：key = "user:{userId}:{confKey}" */
    fun getUserConfig(userId: Long, key: String, default: String = ""): String =
        getConfig("user:$userId:$key", default)

    fun setUserConfig(userId: Long, key: String, value: String) {
        setConfig("user:$userId:$key", value)
    }

    // ============ 登录会话（7天免登录） ============

    /** 保存会话token，days天后过期 */
    fun saveSession(token: String, userId: Long, days: Long) {
        val expires = System.currentTimeMillis() + days * 24 * 60 * 60 * 1000
        stmt("INSERT OR REPLACE INTO sessions (token, user_id, expires_at) VALUES (?,?,?)", token, userId, expires)
    }

    /** 按token取用户ID（过期返回null） */
    fun getSessionUser(token: String): Long? {
        val row = query("SELECT user_id, expires_at FROM sessions WHERE token=?", token).firstOrNull() ?: return null
        val expires = (row["expires_at"] as? Number)?.toLong() ?: 0
        if (System.currentTimeMillis() > expires) {
            stmt("DELETE FROM sessions WHERE token=?", token)
            return null
        }
        return (row["user_id"] as? Number)?.toLong()
    }

    /** 删除会话（注销） */
    fun deleteSession(token: String) {
        stmt("DELETE FROM sessions WHERE token=?", token)
    }

    fun getAllConfig(): Map<String, String> =
        query("SELECT * FROM gateway_config").associate { (it["key"] as? String ?: "") to (it["value"] as? String ?: "") }

    // ============ API 密钥 ============

    fun getApiKeys(): List<ApiKeyEntry> =
        query("SELECT * FROM api_keys ORDER BY created_at").map { rowToApiKey(it) }

    fun getApiKeysByOwner(ownerId: Long): List<ApiKeyEntry> =
        query("SELECT * FROM api_keys WHERE owner_id=0 OR owner_id=? ORDER BY created_at", ownerId).map { rowToApiKey(it) }

    private fun rowToApiKey(it: Map<String, Any?>): ApiKeyEntry = ApiKeyEntry(
        key = it["key"] as? String ?: "",
        label = it["label"] as? String ?: "",
        enabled = (it["enabled"] as? Number)?.toInt() == 1,
        allowedModels = (it["allowed_models"] as? String ?: "[]").let { s ->
            try { kotlinx.serialization.json.Json.decodeFromString<List<String>>(s) } catch (_: Exception) { emptyList() }
        },
        qtaiSjAccess = (it["qtai_sj_access"] as? Number)?.toInt() == 1,
        createdAt = (it["created_at"] as? Number)?.toLong() ?: 0,
        ownerId = (it["owner_id"] as? Number)?.toLong() ?: 0
    )

    fun addApiKey(e: ApiKeyEntry): Boolean {
        val exists = queryOne("SELECT COUNT(*) FROM api_keys WHERE key=?", e.key) ?: 0
        if (exists > 0) return false
        val allowed = kotlinx.serialization.json.Json.encodeToString(e.allowedModels)
        stmt("INSERT INTO api_keys (key,label,enabled,allowed_models,qtai_sj_access,created_at,owner_id) VALUES (?,?,?,?,?,?,?)", e.key, e.label, if (e.enabled) 1 else 0, allowed, if (e.qtaiSjAccess) 1 else 0, e.createdAt, e.ownerId)
        return true
    }

    fun deleteApiKey(key: String) {
        stmt("DELETE FROM api_keys WHERE key=?", key)
    }

    fun updateApiKey(e: ApiKeyEntry): Boolean {
        val exists = queryOne("SELECT COUNT(*) FROM api_keys WHERE key=?", e.key) ?: 0
        if (exists == 0L) return false
        val allowed = kotlinx.serialization.json.Json.encodeToString(e.allowedModels)
        stmt("UPDATE api_keys SET label=?,enabled=?,allowed_models=?,qtai_sj_access=?,owner_id=? WHERE key=?", e.label, if (e.enabled) 1 else 0, allowed, if (e.qtaiSjAccess) 1 else 0, e.ownerId, e.key)
        return true
    }

    fun validateApiKey(key: String): ApiKeyEntry? =
        query("SELECT * FROM api_keys WHERE key=? AND enabled=1", key).firstOrNull()?.let {
            ApiKeyEntry(
                key = it["key"] as? String ?: "",
                label = it["label"] as? String ?: "",
                enabled = true,
                allowedModels = (it["allowed_models"] as? String ?: "[]").let { s ->
                    try { kotlinx.serialization.json.Json.decodeFromString<List<String>>(s) } catch (_: Exception) { emptyList() }
                },
                qtaiSjAccess = (it["qtai_sj_access"] as? Number)?.toInt() == 1,
                createdAt = (it["created_at"] as? Number)?.toLong() ?: 0
            )
        }

    // ============ 记忆配置（对齐原APP MemoryConfig：模型独立记忆） ============

    fun getMemoryConfig(userId: Long): Map<String, Any?> {
        val row = query("SELECT * FROM memory_config WHERE user_id=?", userId).firstOrNull()
        return mapOf(
            "userId" to userId,
            "enabled" to (((row?.get("enabled") as? Number)?.toInt() ?: 1) == 1),
            "saveMode" to (row?.get("save_mode") as? String ?: "normal"),
            "empathyLevel" to ((row?.get("empathy_level") as? Number)?.toInt() ?: 8),
            "thinkingDepth" to ((row?.get("thinking_depth") as? Number)?.toInt() ?: 3),
            "catchphrases" to (row?.get("catchphrases") as? String ?: ""),
            "forbiddenWords" to (row?.get("forbidden_words") as? String ?: ""),
            "expertise" to (row?.get("expertise") as? String ?: "全栈通用"),
            "communicationStyle" to (row?.get("communication_style") as? String ?: "自然亲切、像朋友聊天"),
            "modelIndependent" to (((row?.get("model_independent") as? Number)?.toInt() ?: 0) == 1)
        )
    }

    fun saveMemoryConfig(userId: Long, config: Map<String, Any?>) {
        val now = System.currentTimeMillis()
        val enabled = if ((config["enabled"] as? Boolean) == true) 1 else 0
        val saveMode = config["saveMode"] as? String ?: "normal"
        val empathy = (config["empathyLevel"] as? Number)?.toInt() ?: 8
        val thinking = (config["thinkingDepth"] as? Number)?.toInt() ?: 3
        val catchphrases = config["catchphrases"] as? String ?: ""
        val forbidden = config["forbiddenWords"] as? String ?: ""
        val expertise = config["expertise"] as? String ?: "全栈通用"
        val style = config["communicationStyle"] as? String ?: "自然亲切、像朋友聊天"
        val independent = if ((config["modelIndependent"] as? Boolean) == true) 1 else 0
        val exists = queryOne("SELECT COUNT(*) FROM memory_config WHERE user_id=?", userId) ?: 0
        if (exists > 0) {
            stmt("UPDATE memory_config SET enabled=?, save_mode=?, empathy_level=?, thinking_depth=?, catchphrases=?, forbidden_words=?, expertise=?, communication_style=?, model_independent=?, updated_at=? WHERE user_id=?",
                enabled, saveMode, empathy, thinking, catchphrases, forbidden, expertise, style, independent, now, userId)
        } else {
            stmt("INSERT INTO memory_config (user_id,enabled,save_mode,empathy_level,thinking_depth,catchphrases,forbidden_words,expertise,communication_style,model_independent,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                userId, enabled, saveMode, empathy, thinking, catchphrases, forbidden, expertise, style, independent, now)
        }
    }

    // ============ 公告（管理员发布，首页顶部展示） ============

    fun getAnnouncements(limit: Int = 50): List<Map<String, Any?>> =
        query("SELECT * FROM announcements ORDER BY is_pinned DESC, created_at DESC LIMIT $limit").map {
            mapOf(
                "id" to ((it["id"] as Number).toLong()),
                "title" to (it["title"] as? String ?: ""),
                "content" to (it["content"] as? String ?: ""),
                "authorId" to ((it["author_id"] as? Number)?.toLong() ?: 0),
                "isPinned" to (((it["is_pinned"] as? Number)?.toInt() ?: 0) == 1),
                "createdAt" to ((it["created_at"] as? Number)?.toLong() ?: 0),
                "updatedAt" to ((it["updated_at"] as? Number)?.toLong() ?: 0)
            )
        }

    fun addAnnouncement(title: String, content: String, authorId: Long, isPinned: Boolean) {
        val now = System.currentTimeMillis()
        stmt("INSERT INTO announcements (title,content,author_id,is_pinned,created_at,updated_at) VALUES (?,?,?,?,?,?)",
            title, content, authorId, if (isPinned) 1 else 0, now, now)
    }

    fun updateAnnouncement(id: Long, title: String, content: String, isPinned: Boolean) {
        stmt("UPDATE announcements SET title=?, content=?, is_pinned=?, updated_at=? WHERE id=?",
            title, content, if (isPinned) 1 else 0, System.currentTimeMillis(), id)
    }

    fun deleteAnnouncement(id: Long) {
        stmt("DELETE FROM announcements WHERE id=?", id)
    }

    // ============ 工单（用户提交，管理员反馈，聊天式） ============

    fun getTickets(userId: Long?, isAdmin: Boolean, limit: Int = 100): List<Map<String, Any?>> {
        val sql = if (isAdmin) "SELECT * FROM tickets ORDER BY updated_at DESC LIMIT $limit"
            else "SELECT * FROM tickets WHERE user_id=? ORDER BY updated_at DESC LIMIT $limit"
        return (if (isAdmin) query(sql) else query(sql, userId)).map {
            mapOf(
                "id" to ((it["id"] as Number).toLong()),
                "userId" to ((it["user_id"] as? Number)?.toLong() ?: 0),
                "title" to (it["title"] as? String ?: ""),
                "status" to (it["status"] as? String ?: "open"),
                "createdAt" to ((it["created_at"] as? Number)?.toLong() ?: 0),
                "updatedAt" to ((it["updated_at"] as? Number)?.toLong() ?: 0),
                "closedAt" to ((it["closed_at"] as? Number)?.toLong() ?: 0),
                "lastMsg" to getTicketLastMessage((it["id"] as Number).toLong())
            )
        }
    }

    fun getTicketLastMessage(ticketId: Long): String =
        query("SELECT content FROM ticket_messages WHERE ticket_id=? ORDER BY id DESC LIMIT 1", ticketId)
            .firstOrNull()?.get("content") as? String ?: ""

    fun addTicket(userId: Long, title: String): Long {
        val now = System.currentTimeMillis()
        stmt("INSERT INTO tickets (user_id,title,status,created_at,updated_at) VALUES (?,?,?,?,?)",
            userId, title, "open", now, now)
        return lastInsertId()
    }

    fun getTicketMessages(ticketId: Long): List<Map<String, Any?>> =
        query("SELECT * FROM ticket_messages WHERE ticket_id=? ORDER BY id ASC", ticketId).map {
            mapOf(
                "id" to ((it["id"] as Number).toLong()),
                "ticketId" to ((it["ticket_id"] as? Number)?.toLong() ?: 0),
                "senderId" to ((it["sender_id"] as? Number)?.toLong() ?: 0),
                "role" to (it["role"] as? String ?: "user"),
                "content" to (it["content"] as? String ?: ""),
                "createdAt" to ((it["created_at"] as? Number)?.toLong() ?: 0)
            )
        }

    fun addTicketMessage(ticketId: Long, senderId: Long, role: String, content: String) {
        val now = System.currentTimeMillis()
        stmt("INSERT INTO ticket_messages (ticket_id,sender_id,role,content,created_at) VALUES (?,?,?,?,?)",
            ticketId, senderId, role, content, now)
        stmt("UPDATE tickets SET updated_at=? WHERE id=?", now, ticketId)
    }

    fun updateTicketStatus(ticketId: Long, status: String) {
        val closedAt = if (status == "closed") System.currentTimeMillis() else 0
        stmt("UPDATE tickets SET status=?, closed_at=?, updated_at=? WHERE id=?", status, closedAt, System.currentTimeMillis(), ticketId)
    }

    fun deleteTicket(ticketId: Long) {
        stmt("DELETE FROM tickets WHERE id=?", ticketId)
    }

    fun isTicketOwner(ticketId: Long, userId: Long): Boolean =
        (queryOne("SELECT user_id FROM tickets WHERE id=?", ticketId)?.takeIf { it == userId } ?: 0L) == userId

    // ============ 操作日志（管理员查看） ============

    fun addOpLog(userId: Long, username: String, action: String, detail: String, ip: String = "") {
        try {
            stmt("INSERT INTO op_logs (user_id,username,action,detail,ip,created_at) VALUES (?,?,?,?,?,?)",
                userId, username, action, detail, ip, System.currentTimeMillis())
        } catch (_: Exception) {}
    }

    fun getOpLogs(limit: Int = 200): List<Map<String, Any?>> =
        query("SELECT * FROM op_logs ORDER BY id DESC LIMIT $limit").map {
            mapOf(
                "id" to ((it["id"] as Number).toLong()),
                "userId" to ((it["user_id"] as? Number)?.toLong() ?: 0),
                "username" to (it["username"] as? String ?: ""),
                "action" to (it["action"] as? String ?: ""),
                "detail" to (it["detail"] as? String ?: ""),
                "ip" to (it["ip"] as? String ?: ""),
                "createdAt" to ((it["created_at"] as? Number)?.toLong() ?: 0)
            )
        }

    fun clearOpLogs() {
        stmt("DELETE FROM op_logs")
    }
}