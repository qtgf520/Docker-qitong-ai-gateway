package com.qitong.gateway.http

import com.qitong.gateway.db.Database
import kotlinx.serialization.json.*
import org.json.JSONArray
import org.json.JSONObject

/**
 * ★ v1.264 綦桐AI网关 MCP 服务端（Model Context Protocol）
 *
 * 目的：把网关全部沙盒能力（模型管理/测速/用户/终端/MCP/插件/工作流…）
 *      以标准 MCP 协议对外暴露，让 Claude Desktop / Cursor / 其他 Agent 远程调用本网关。
 *
 * 协议：JSON-RPC 2.0 over HTTP(POST)，单端点 `/mcp`
 *  - initialize                  → 返回 serverInfo + capabilities
 *  - tools/list                  → 返回全部可用工具（name/description/inputSchema）
 *  - tools/call                  → 执行工具（复用 SandboxEngine.execute）
 *
 * 安全：需携带网关 API Key（Authorization: Bearer sk-xxx）或管理员会话；
 *      写操作按 SandboxEngine 内部权限校验（user/admin/high）兜底。
 */
object McpServer {

    /** MCP 协议版本 */
    private const val PROTOCOL_VERSION = "2024-11-05"
    private const val SERVER_NAME = "qitong-gateway"
    private const val SERVER_VERSION = "1.264"

    /**
     * 处理一条 MCP JSON-RPC 请求。
     * @param body 原始 JSON-RPC 请求体
     * @param userId 调用者用户ID（0=未认证）
     * @param isAdmin 是否管理员
     * @return JSON-RPC 响应字符串
     */
    fun handle(database: Database, body: String, userId: Long, isAdmin: Boolean): String {
        // ★ v1.264 用 org.json 解析（项目内通用且已验证可靠；kotlinx JsonObject 索引在部分版本有坑）
        val rootObj = try {
            JSONObject(body)
        } catch (e: Exception) {
            return errorJson(null, -32700, "Parse error: ${e.message}")
        }
        val idStr = if (rootObj.has("id") && !rootObj.isNull("id")) rootObj.opt("id").toString() else null
        val idNum = idStr?.toLongOrNull()
        val id: JsonElement? = when {
            idStr == null -> null
            idNum != null -> JsonPrimitive(idNum)
            else -> JsonPrimitive(idStr)
        }
        val method = rootObj.optString("method", "")
        if (method.isBlank()) return errorJson(id, -32600, "Invalid Request: missing method (body=${body.take(80)})")

        return try {
            when (method) {
                // ★ 握手：返回能力声明
                "initialize" -> resultJson(id, buildJsonObject {
                    put("protocolVersion", JsonPrimitive(PROTOCOL_VERSION))
                    put("capabilities", buildJsonObject {
                        put("tools", buildJsonObject { put("listChanged", JsonPrimitive(false)) })
                    })
                    put("serverInfo", buildJsonObject {
                        put("name", JsonPrimitive(SERVER_NAME))
                        put("version", JsonPrimitive(SERVER_VERSION))
                    })
                })
                // ★ 通知类（客户端握手后发的 notifications/initialized）：无需响应体
                "notifications/initialized", "initialized" -> ""
                // ★ 工具清单
                "tools/list" -> resultJson(id, buildJsonObject {
                    put("tools", toolsArray())
                })
                // ★ 工具调用
                "tools/call" -> {
                    val params = rootObj.optJSONObject("params")
                    val toolName = params?.optString("name", "").orEmpty()
                    val argsObj = params?.optJSONObject("arguments")
                    if (toolName.isBlank()) {
                        errorJson(id, -32602, "Invalid params: missing tool name")
                    } else {
                        val resultText = callTool(database, toolName, argsObj, userId, isAdmin)
                        resultJson(id, buildJsonObject {
                            put("content", buildJsonArray {
                                add(buildJsonObject {
                                    put("type", JsonPrimitive("text"))
                                    put("text", JsonPrimitive(resultText))
                                })
                            })
                            put("isError", JsonPrimitive(resultText.startsWith("❌") || resultText.startsWith("⛔")))
                        })
                    }
                }
                // ★ 资源/提示词（暂不支持，返回空列表保证兼容）
                "resources/list" -> resultJson(id, buildJsonObject { put("resources", JsonArray(emptyList())) })
                "prompts/list" -> resultJson(id, buildJsonObject { put("prompts", JsonArray(emptyList())) })
                // ★ ping
                "ping" -> resultJson(id, buildJsonObject { })
                else -> errorJson(id, -32601, "Method not found: $method")
            }
        } catch (e: Exception) {
            errorJson(id, -32603, "Internal error: ${e.message}")
        }
    }

    /** 把 SandboxEngine 的 KNOWLEDGE_JSON 转成 MCP tools 数组（含 inputSchema） */
    private fun toolsArray(): JsonArray {
        return try {
            // 复用沙盒能力清单（内置函数 + 插件工具）
            val knowledge = JSONArray(SandboxEngineAccess.knowledgeJson())
            val tools = mutableListOf<JsonElement>()
            for (i in 0 until knowledge.length()) {
                val f = knowledge.optJSONObject(i) ?: continue
                // ★ v1.264 字段名对齐 SandboxEngine.func()：function_name / desc / permission / risk_level / params
                val name = f.optString("function_name", "")
                if (name.isBlank()) continue
                val desc = f.optString("desc", "")
                val risk = f.optString("risk_level", "read")
                val perm = f.optString("permission", "user")
                val paramsArr = f.optJSONArray("params") ?: JSONArray()
                // 组装 inputSchema
                val props = buildJsonObject {
                    for (j in 0 until paramsArr.length()) {
                        val p = paramsArr.optJSONObject(j) ?: continue
                        val pn = p.optString("name", "")
                        if (pn.isBlank()) continue
                        put(pn, buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive(p.optString("desc", "")))
                        })
                    }
                }
                val required = buildJsonArray {
                    for (j in 0 until paramsArr.length()) {
                        val p = paramsArr.optJSONObject(j) ?: continue
                        if (p.optBoolean("required", false)) add(JsonPrimitive(p.optString("name", "")))
                    }
                }
                tools.add(buildJsonObject {
                    put("name", JsonPrimitive(name))
                    put("description", JsonPrimitive("$desc（权限：$perm，风险：$risk）"))
                    put("inputSchema", buildJsonObject {
                        put("type", JsonPrimitive("object"))
                        put("properties", props)
                        put("required", required)
                    })
                })
            }
            JsonArray(tools)
        } catch (e: Exception) {
            JsonArray(emptyList())
        }
    }

    /** 执行工具：把 MCP arguments 转成 SandboxEngine 的 Map 参数 */
    private fun callTool(database: Database, toolName: String, args: JSONObject?, userId: Long, isAdmin: Boolean): String {
        val argMap = mutableMapOf<String, String>()
        args?.keys()?.forEach { k ->
            argMap[k] = args.opt(k)?.toString() ?: ""
        }
        return try {
            SandboxEngineAccess.execute(toolName, argMap, isAdmin, userId, database)
        } catch (e: Exception) {
            "❌ 工具执行异常：${e.message}"
        }
    }

    // ============ JSON-RPC 响应构造 ============
    private fun resultJson(id: JsonElement?, result: JsonObject): String =
        buildJsonObject {
            put("jsonrpc", JsonPrimitive("2.0"))
            if (id != null) put("id", id)
            put("result", result)
        }.toString()

    private fun errorJson(id: JsonElement?, code: Int, message: String): String =
        buildJsonObject {
            put("jsonrpc", JsonPrimitive("2.0"))
            if (id != null) put("id", id) else put("id", JsonNull)
            put("error", buildJsonObject {
                put("code", JsonPrimitive(code))
                put("message", JsonPrimitive(message))
            })
        }.toString()
}

/**
 * 沙盒能力访问桥（避免直接暴露 SandboxEngine 内部成员）
 */
internal object SandboxEngineAccess {
    fun knowledgeJson(): String = com.qitong.gateway.sandbox.SandboxEngine.KNOWLEDGE_JSON
    fun execute(fn: String, args: Map<String, String>, isAdmin: Boolean, userId: Long, db: Database): String =
        com.qitong.gateway.sandbox.SandboxEngine.execute(fn, args, isAdmin, userId, db)
}