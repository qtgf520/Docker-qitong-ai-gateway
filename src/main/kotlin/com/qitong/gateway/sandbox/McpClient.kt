package com.qitong.gateway.sandbox

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * MCP 客户端（v47）：调用已配置的 MCP 服务器工具（Streamable HTTP）
 * 对齐 Kai 9000 的 MCP server 支持——远程工具服务器通过 Model Context Protocol 接入
 */
object McpClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val jsonCt = "application/json; charset=utf-8".toMediaType()

    /** 列出已配置且启用的 MCP 服务器 */
    data class McpServer(val id: Long, val name: String, val serverType: String, val url: String, val authToken: String)

    /** 获取启用的 MCP 服务器列表 */
    fun listEnabled(db: com.qitong.gateway.db.Database): List<McpServer> =
        db.getMcpConfigs()
            .filter { (it["enabled"] as? Boolean) != false }
            .map { McpServer(
                ((it["id"] as? Number)?.toLong() ?: 0),
                (it["name"] as? String ?: ""),
                (it["serverType"] as? String ?: "http"),
                (it["url"] as? String ?: ""),
                (it["authToken"] as? String ?: "")
            ) }
            .filter { it.url.isNotBlank() }

    /**
     * 调用 MCP 服务器工具（Streamable HTTP JSON-RPC）
     * 用 tools/call 方法调用工具名 + 参数
     */
    fun callTool(server: McpServer, toolName: String, argsJson: JSONObject): String {
        val payload = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", System.currentTimeMillis())
            .put("method", "tools/call")
            .put("params", JSONObject()
                .put("name", toolName)
                .put("arguments", argsJson))
            .toString()
        val reqBuilder = Request.Builder()
            .url(server.url.trim())
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json, text/event-stream")
        if (server.authToken.isNotBlank()) reqBuilder.addHeader("Authorization", "Bearer ${server.authToken}")
        val req = reqBuilder.post(payload.toRequestBody(jsonCt)).build()
        return try {
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) "❌ MCP ${server.name} HTTP ${resp.code}: ${body.take(200)}"
                else runCatching {
                    val obj = JSONObject(body)
                    val result = obj.optJSONObject("result")
                    if (result != null) {
                        // 标准 MCP 返回：content 数组
                        val content = result.optJSONArray("content")
                        if (content != null && content.length() > 0) {
                            (0 until content.length()).joinToString("\n") { i ->
                                val c = content.getJSONObject(i)
                                c.optString("text").ifBlank { c.optString("content") }
                            }.ifBlank { result.toString().take(1000) }
                        } else result.toString().take(1000)
                    } else obj.toString().take(1000)
                }.getOrElse { body.take(1000) }
            }
        } catch (e: Exception) {
            "❌ MCP 调用失败 ${server.name}: ${e.message}"
        }
    }

    /** 探测 MCP 服务器可用性：调用 tools/list */
    fun listTools(server: McpServer): String {
        val payload = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", System.currentTimeMillis())
            .put("method", "tools/list")
            .put("params", JSONObject())
            .toString()
        val reqBuilder = Request.Builder()
            .url(server.url.trim())
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json, text/event-stream")
        if (server.authToken.isNotBlank()) reqBuilder.addHeader("Authorization", "Bearer ${server.authToken}")
        val req = reqBuilder.post(payload.toRequestBody(jsonCt)).build()
        return try {
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) "❌ MCP ${server.name} HTTP ${resp.code}"
                else runCatching {
                    val obj = JSONObject(body)
                    val result = obj.optJSONObject("result")
                    val tools = result?.optJSONArray("tools")
                    if (tools != null && tools.length() > 0) {
                        "📋 MCP「${server.name}」可用工具：\n" + (0 until tools.length()).joinToString("\n") { i ->
                            val t = tools.getJSONObject(i)
                            "· ${t.optString("name")} — ${t.optString("description", "").take(80)}"
                        }
                    } else "📋 MCP「${server.name}」在线（无工具）"
                }.getOrElse { body.take(300) }
            }
        } catch (e: Exception) {
            "❌ MCP 探测失败 ${server.name}: ${e.message}"
        }
    }
}