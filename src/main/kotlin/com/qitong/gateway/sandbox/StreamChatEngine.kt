package com.qitong.gateway.sandbox

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * ★ v1.229 统一流式模型调用引擎（学 AstrBot 架构核心）
 *
 * 核心：stream=true 流式读取，AI 边想边说边推：
 *  - 正文 content 流式累积；每出现一个"完整句"（以 。！？…换行 结尾）就切出来
 *    → 若该句含行动词（我来/我去/我看/稍等/正在/再试 等）且还没进入工具标签段，
 *      实时回调 onHumanLine 推送 💭 人话思考（这就是"好的我来帮你看一下"的实时来源）
 *  - 一旦正文出现工具标签（[[沙盒: / <dots_function_call> 等）→ 停止推句（后面全是调用，不刷屏）
 *  - reasoning_content（R1 内心独白）一律不展示（后台参考），根治闲聊刷推理链
 *  - 纯闲聊：句子无行动词 → 不推任何💭，只等最终答案
 *
 * 三端（QQ/微信/公众号）共用，行为完全一致。工具执行仍由上层 Agent 循环（沙盒）完成。
 */
object StreamChatEngine {

    private val http = okhttp3.OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(150, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val jsonCt = "application/json; charset=utf-8".toMediaType()

    data class StreamResult(
        val content: String,          // 完整正文（含工具标签，供 Agent 解析）
        val reasoning: String,        // 完整推理链（后台参考，不展示）
        val cancelled: Boolean = false
    )

    /** 是否进入"工具标签段"（出现即不再推人话句） */
    fun isToolTagStart(buf: String): Boolean {
        return buf.contains("[[沙盒:") || buf.contains("[[沙盒：") ||
            buf.contains("<dots_function_call") || buf.contains("<function_call") ||
            buf.contains("<invoke") || buf.contains("```tools") || buf.contains("<tool_call")
    }

    /** 常见人话行动词（AI 过渡语特征） */
    private val ACTION_WORDS = listOf(
        "我来", "我去", "我查", "我看", "我看看", "稍等", "正在", "这就", "帮你", "先",
        "继续", "再试", "看一下", "查一下", "试试", "马上", "处理", "重新", "等一等", "别急"
    )

    fun isHumanActionLine(s: String): Boolean = ACTION_WORDS.any { s.contains(it) }

    /**
     * 流式调用模型，边读边回调人话思考句。
     * @param messages 请求消息（system+user+history）
     * @param onHumanLine 人话思考句回调（有工具轮次的过渡语，实时推 💭）；null=不推
     * @param onCancel 外部取消 hook（返回 true 中断）
     */
    fun chatStream(
        model: String,
        messages: org.json.JSONArray,
        gatewayPort: Int = 18889,
        onHumanLine: ((String) -> Unit)? = null,
        onCancel: (() -> Boolean)? = null
    ): StreamResult {
        val body = org.json.JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("stream", true)
            .put("temperature", 0.8)
            .put("max_tokens", 4096)
            .toString()

        val req = okhttp3.Request.Builder()
            .url("http://127.0.0.1:$gatewayPort/v1/chat/completions")
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody(jsonCt))
            .build()

        val contentSb = StringBuilder()
        val reasoningSb = StringBuilder()
        val sentenceBuf = StringBuilder()
        var toolMode = false
        var cancelled = false

        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val errBody = resp.body?.string().orEmpty()
                System.err.println("[StreamChat] 流式调用失败 HTTP ${resp.code}: ${errBody.take(200)}")
                return StreamResult(content = "", reasoning = "", cancelled = false)
            }
            val reader = BufferedReader(InputStreamReader(resp.body?.byteStream(), Charsets.UTF_8))
            while (true) {
                if (onCancel?.invoke() == true) { cancelled = true; break }
                val line = reader.readLine() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.substring(5).trim()
                if (data == "[DONE]") break
                try {
                    val obj = org.json.JSONObject(data)
                    val delta = obj.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta") ?: continue
                    val c = delta.optString("content").orEmpty()
                    val rsn = delta.optString("reasoning_content").orEmpty()
                        .ifEmpty { delta.optString("reasoning").orEmpty() }
                    if (rsn.isNotEmpty()) {
                        reasoningSb.append(rsn)
                    }
                    if (c.isNotEmpty()) {
                        contentSb.append(c)
                        if (onHumanLine != null && !toolMode) {
                            sentenceBuf.append(c)
                            val curFull = sentenceBuf.toString()
                            if (isToolTagStart(curFull)) {
                                toolMode = true
                                sentenceBuf.setLength(0)
                            } else {
                                var idx = -1
                                for (i in curFull.length - 1 downTo 0) {
                                    val ch = curFull[i]
                                    if (ch == '\u3002' || ch == '\uff01' || ch == '\uff1f' || ch == '\u2026' || ch == '~' || ch == '\uff5e' || ch == '\n') { idx = i; break }
                                }
                                if (idx >= 0) {
                                    val sentence = curFull.substring(0, idx + 1).trim()
                                    sentenceBuf.setLength(0)
                                    if (isToolTagStart(sentence)) { toolMode = true }
                                    else if (sentence.length in 2..60 && isHumanActionLine(sentence)) {
                                        onHumanLine(sentence)
                                    }
                                }
                            }
                        }
                    }
                } catch (_: Exception) { }
            }
        }

        val fullContent = contentSb.toString()
        return StreamResult(content = fullContent, reasoning = reasoningSb.toString(), cancelled = cancelled)
    }
}
