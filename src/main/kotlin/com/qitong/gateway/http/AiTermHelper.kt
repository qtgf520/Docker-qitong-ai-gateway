package com.qitong.gateway.http

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * AI 智能终端助手：把用户自然语言需求转成 Linux 命令
 * 调用网关本机大模型（/v1/chat/completions），Anthropic/OpenAI 兼容
 */
object AiTermHelper {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val jsonCt = "application/json; charset=utf-8".toMediaType()

    private val SYSTEM_PROMPT = """
        你是一个 Linux 系统运维助手。用户会用中文描述需求，你需要：
        1. 将其转换成一条（最多两条，用 && 连接）最合适、最安全的 Linux shell 命令
        2. 只输出命令本身，不要任何解释、不要 markdown 代码块、不要多余字符
        3. 只读命令优先；写操作需谨慎（删除/格式化必须明确拒绝）
        4. 禁止生成以下危险命令：rm -rf /、mkfs、dd if=、shutdown、reboot、:(){、fdisk、高危删除
        示例：
        用户：看看磁盘占用 → df -h
        用户：当前目录文件大小排序 → ls -lhS
        用户：看 nginx 是否在跑 → ps aux | grep nginx
        用户：查看内存 → free -h
        用户：删除所有日志（危险）→ 拒绝，输出 DENY
    """.trimIndent()

    /** 生成命令；失败或危险返回空字符串 */
    fun genCommand(req: String): String {
        if (req.isBlank()) return ""
        // ★ v57 先用关键词兜底（快且稳：扫IP/ping/dig 等常用需求不依赖大模型）
        val fallback = keywordCommand(req)
        if (fallback.isNotBlank()) return fallback
        return try {
            val msgs = JSONArray()
                .put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                .put(JSONObject().put("role", "user").put("content", req))
            val body = JSONObject()
                .put("model", "qtai-sj")
                .put("messages", msgs)
                .put("stream", false)
                .put("temperature", 0.1)
                .toString()
            val httpReq = Request.Builder()
                .url("http://127.0.0.1:18889/v1/chat/completions")
                .addHeader("Content-Type", "application/json")
                .post(body.toRequestBody(jsonCt))
                .build()
            client.newCall(httpReq).execute().use { resp ->
                if (!resp.isSuccessful) return fallback
                val obj = JSONObject(resp.body?.string().orEmpty())
                val content = obj.optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")?.trim().orEmpty()
                // 清理 markdown 代码块包裹
                var cmd = content.replace("""```bash""", "").replace("""```sh""", "").replace("```", "").trim()
                if (cmd.contains("DENY") || cmd.contains("拒绝")) return fallback
                cmd.take(300)
            }
        } catch (e: Exception) { fallback }
    }

    /** 关键词兜底命令生成（不依赖大模型，覆盖常用运维需求） */
    private fun keywordCommand(req: String): String {
        val r = req.lowercase()
        // 提取域名：支持「luolitu.vip」「jili5.cn」「www.xxx.com」等写法
        fun extractHost(): String? {
            val m = Regex("""[a-zA-Z0-9][a-zA-Z0-9.-]*\.[a-zA-Z]{2,}""").find(req) ?: return null
            val raw = m.value.trim().trimEnd('.')
            return raw.ifBlank { null }
        }
        val host = extractHost()
        // 扫 IP / 域名解析 / 查域名：keywords 任意命中且带域名字符都出解析命令
        if (host != null && (r.contains("扫") || r.contains("ip") || r.contains("域名") || r.contains("解析") ||
                r.contains("查") || r.contains("看看") || r.contains("跑一下") || r.contains("ping"))) {
            return "getent hosts $host || dig +short $host || nslookup $host"
        }
        if (r.contains("磁盘") || r.contains("空间") || r.contains("磁盘占用")) return "df -h"
        if (r.contains("内存") || r.contains("free")) return "free -h"
        if (r.contains("nginx")) return "ps aux | grep nginx | grep -v grep || systemctl status nginx"
        if (r.contains("端口") || r.contains("监听")) return "ss -tlnp || netstat -tlnp"
        if (r.contains("进程") || r.contains("ps")) return "ps aux --sort=-%cpu | head -20"
        if (r.contains("当前目录") || r.contains("文件大小")) return "ls -lhS"
        if (r.contains("时间") && r.contains("date")) return "date"
        return ""
    }
}