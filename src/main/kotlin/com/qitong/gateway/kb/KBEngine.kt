package com.qitong.gateway.kb

import com.qitong.gateway.db.Database

/**
 * ★ v1.195 知识库引擎（RAG 核心，纯 Kotlin 自研，无外部依赖）
 *
 * 设计：知识库 = 文档分块 + BM25 稀疏检索 + 结果注入 Prompt
 * - 递归字符分块：按段落/句子层级递归切分，块间重叠
 * - BM25 稀疏检索：词频+逆文档频率打分（经典概率模型，无需 embedding，离线可用）
 * - 检索结果注入：沙盒 kb_search 工具 + QQ/微信/内置聊天自动注入
 */
object KBEngine {

    /** 默认分块大小（字符） */
    private const val DEFAULT_CHUNK_SIZE = 500
    /** 块间重叠（字符） */
    private const val CHUNK_OVERLAP = 80
    /** 中文停用词（精简集合，用于 BM25 打分降噪） */
    private val STOP_WORDS = setOf(
        "的", "了", "是", "在", "和", "有", "就", "不", "都", "而", "及", "与", "着", "或", "一个", "没有",
        "我们", "你们", "他们", "这个", "那个", "自己", "因为", "所以", "但是", "如果", "可以", "怎么", "什么",
        "the", "a", "an", "is", "are", "was", "were", "to", "of", "in", "on", "and", "or", "for", "with"
    )

    // ============ 文本分块 ============

    /** 递归字符分块：优先按段落/句子边界切，保证块大小不超上限，块间重叠 */
    fun chunkText(text: String, chunkSize: Int = DEFAULT_CHUNK_SIZE, overlap: Int = CHUNK_OVERLAP): List<String> {
        val cleaned = text.trim()
        if (cleaned.isEmpty()) return emptyList()
        if (cleaned.length <= chunkSize) return listOf(cleaned)

        val chunks = mutableListOf<String>()
        var start = 0
        while (start < cleaned.length) {
            val end = minOf(start + chunkSize, cleaned.length)
            var splitAt = end

            // 尽量在段落/句子边界处切分
            if (end < cleaned.length) {
                // 找最近的段落边界（\n\n）优先，其次句号/问号/感叹号/换行
                val window = cleaned.substring(start, end)
                val paraBreak = window.lastIndexOf("\n\n")
                if (paraBreak > chunkSize / 2) {
                    splitAt = start + paraBreak + 2
                } else {
                    val sentenceBreaks = listOf("。", "！", "？", "！？", "\n")
                    var best = -1
                    for (s in sentenceBreaks) {
                        val idx = window.lastIndexOf(s)
                        if (idx > chunkSize / 2 && idx > best) best = idx
                    }
                    if (best > 0) splitAt = start + best + 1
                }
            }

            var chunkEnd = splitAt.coerceAtMost(cleaned.length)
            // 避免死循环（切分点无进展时强推）
            if (chunkEnd <= start) chunkEnd = minOf(start + chunkSize, cleaned.length)
            chunks.add(cleaned.substring(start, chunkEnd).trim())
            // 重叠前进
            start = if (chunkEnd >= cleaned.length) cleaned.length + 1
                else (chunkEnd - overlap).coerceAtLeast(start + 1)
        }
        return chunks.filter { it.isNotBlank() }
    }

    /** 估算 token 数（中英文混合：中文按 1.5 字/token，英文按 4 字符/token） */
    fun estimateTokens(text: String): Int {
        if (text.isBlank()) return 0
        val cjk = text.count { it.code in 0x4E00..0x9FFF }
        val other = text.length - cjk
        return (cjk / 1.5 + other / 4.0).toInt().coerceAtLeast(1)
    }

    // ============ 分词 ============

    /** 简单分词：中文按单字+常用双字组合，英文按空格；去停用词 */
    fun tokenize(text: String): List<String> {
        val lower = text.lowercase()
        val tokens = mutableListOf<String>()
        // 英文/数字词
        Regex("[a-z0-9]+").findAll(lower).forEach { tokens.add(it.value) }
        // 中文：2-4字滑动窗口 + 单字
        val cjkChars = lower.filter { it.code in 0x4E00..0x9FFF }
        if (cjkChars.isNotEmpty()) {
            // 2字词优先（更有语义），再补单字
            for (i in 0 until cjkChars.length - 1) {
                val bigram = cjkChars.substring(i, i + 2)
                if (bigram !in STOP_WORDS) tokens.add(bigram)
            }
            for (c in cjkChars) {
                if (c.toString() !in STOP_WORDS) tokens.add(c.toString())
            }
        }
        return tokens.filter { it !in STOP_WORDS && it.length >= 1 }
    }

    // ============ BM25 检索 ============

    /**
     * BM25 检索：对知识库 chunks 打分取 top-k。
     * 经典 BM25 = IDF * (f * (k1+1)) / (f + k1 * (1 - b + b * dl/avgdl))
     */
    fun search(db: Database, kbId: Long, query: String, topK: Int = 5): List<Map<String, Any?>> {
        val chunks = try { db.getKbChunks(kbId, limit = 2000) } catch (_: Exception) { emptyList() }
        if (chunks.isEmpty()) return emptyList()

        val queryTokens = tokenize(query)
        if (queryTokens.isEmpty()) return emptyList()

        // 文档集合统计
        val totalDocs = chunks.size
        val docLengths = chunks.map { (it["content"] as? String ?: "").length.toDouble() }
        val avgDl = docLengths.sum() / totalDocs.coerceAtLeast(1)

        // 每个 chunk 的词频
        val tf = mutableListOf<Map<String, Int>>()
        for (c in chunks) {
            val content = (c["content"] as? String ?: "").lowercase()
            val freq = HashMap<String, Int>()
            for (t in tokenize(content)) freq[t] = (freq[t] ?: 0) + 1
            tf.add(freq)
        }

        // 文档频率（query token 在多少 chunk 出现）
        val df = HashMap<String, Int>()
        for (freqMap in tf) {
            for (t in queryTokens) {
                if (freqMap.containsKey(t)) df[t] = (df[t] ?: 0) + 1
            }
        }

        // BM25 打分
        val k1 = 1.5
        val b = 0.75
        val scored = mutableListOf<Pair<Double, Int>>()  // (score, chunkIndex)
        for (i in 0 until totalDocs) {
            var score = 0.0
            val freqMap = tf[i]
            val dl = docLengths[i]
            for (t in queryTokens) {
                val f = freqMap[t] ?: 0
                if (f == 0) continue
                val n = df[t] ?: 1
                // IDF（平滑）
                val idf = Math.log(1.0 + (totalDocs - n + 0.5) / (n + 0.5))
                val tfNorm = f * (k1 + 1) / (f + k1 * (1 - b + b * dl / avgDl))
                score += idf * tfNorm
            }
            if (score > 0) scored.add(score to i)
        }

        // 取 top-k（按分数降序；分数相同的按 chunk_index 升序保持文档原序）
        val top = scored.sortedWith(compareByDescending<Pair<Double, Int>> { it.first }.thenBy { it.second }).take(topK)
        return top.map { (score, idx) ->
            val c = chunks[idx]
            mapOf(
                "content" to (c["content"] as? String ?: ""),
                "chunkIndex" to ((c["chunkIndex"] as? Number)?.toInt() ?: 0),
                "docTitle" to (c["docTitle"] as? String ?: ""),
                "score" to Math.round(score * 1000.0) / 1000.0
            )
        }
    }

    // ============ Agent 注入格式化 ============

    /** 把检索结果格式化为注入模型的上下文文本 */
    fun formatForPrompt(results: List<Map<String, Any?>>, kbName: String): String {
        if (results.isEmpty()) return ""
        val sb = StringBuilder("【知识库·$kbName 检索结果】\n")
        results.forEachIndexed { i, r ->
            sb.append("[${i + 1}] ").append(r["content"] as? String ?: "").append("\n")
        }
        return sb.toString().trim()
    }
}