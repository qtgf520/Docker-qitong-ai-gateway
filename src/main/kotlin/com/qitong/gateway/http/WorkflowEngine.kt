package com.qitong.gateway.http

import com.qitong.gateway.db.Database
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 工作流引擎：执行任意步骤（技能 / 终端命令 / HTTP / 大模型 / 回复）
 * 供工作流、技能、群自动化复用；可被 qtai-sj 调用执行
 */
object WorkflowEngine {

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** 执行单个步骤，返回输出文本 */
    fun runStep(database: Database, type: String, content: String): String = when (type) {
        "terminal" -> TerminalManager.runOnce(content)
        "http" -> httpGet(content)
        "skill", "code" -> runSkill(database, content)
        "ai" -> askAi(content)
        else -> content // reply / 默认
    }

    /** 直接执行一条终端命令（一次性，无会话） */
    fun terminalOnce(cmd: String): String = TerminalManager.runOnce(cmd)

    private fun runSkill(database: Database, content: String): String {
        // content 可以是 SkillExecutor 编码 或 技能名
        val code = content.trim()
        return try {
            kotlinx.coroutines.runBlocking {
                SkillExecutor.execute(database, code, "", 0)
            }
        } catch (e: Exception) { "❌ 技能执行失败: ${e.message}" }
    }

    private fun httpGet(url: String): String {
        return try {
            val req = Request.Builder().url(url).get().build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) "❌ HTTP ${r.code}" else r.body?.string()?.trim()?.take(800) ?: "(空)"
            }
        } catch (e: Exception) { "❌ HTTP 失败: ${e.message}" }
    }

    private fun askAi(prompt: String): String {
        return try {
            val body = org.json.JSONObject()
                .put("model", "qtai-sj")
                .put("messages", org.json.JSONArray()
                    .put(org.json.JSONObject().put("role", "user").put("content", prompt)))
                .put("stream", false)
                .toString()
            val req = okhttp3.Request.Builder()
                .url("http://127.0.0.1:18889/v1/chat/completions")
                .addHeader("Content-Type", "application/json")
                .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) "❌ AI 调用失败 HTTP ${resp.code}"
                else {
                    val obj = org.json.JSONObject(resp.body?.string().orEmpty())
                    obj.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")?.trim() ?: "(空)"
                }
            }
        } catch (e: Exception) { "❌ AI 失败: ${e.message}" }
    }
}