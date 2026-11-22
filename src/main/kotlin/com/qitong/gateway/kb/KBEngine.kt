package com.qitong.gateway.kb

import com.qitong.gateway.db.Database
import com.qitong.gateway.model.AiModel
import okhttp3.MediaType.Companion.toMediaType
import java.util.concurrent.ConcurrentHashMap

/**
 * ★ v1.194 知识库引擎（RAG 核心，纯 Kotlin 自研，无外部依赖）
 *
 * 设计：知识库 = 文档分块 + BM25 稀疏检索 + 结果注入 Prompt
 * - 递归字符分块：按段落/句子层级递归切分，块间重叠
 * - BM25 稀疏检索：词频+逆文档频率打分（经典概率模型，无需 embedding，离线可用）
 * - 检索结果注入：沙盒 kb_search 工具 + QQ/微信/内置聊天自动注入
 * - ★ v1.232 chunk 缓存：按 kbId 缓存全量 chunks，60 秒过期，避免每轮全表扫
 */
object KBEngine {

    /** ★ v1.232 chunk 缓存：kbId -> (过期时间戳, chunks)；60 秒过期，命中不重复读库 */
    private val chunkCache = ConcurrentHashMap<Long, Pair<Long, List<Map<String, Any?>>>>()

    /** chunk 缓存 TTL（毫秒）= 60 秒 */
    private const val CHUNK_CACHE_TTL_MS = 60_000L

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
        // ★ v1.232 chunk 缓存：60 秒过期，命中不重复读库
        val now = System.currentTimeMillis()
        val cached = chunkCache[kbId]
        val chunks = if (cached != null && cached.first > now) cached.second
            else {
                val fresh = try { db.getKbChunks(kbId, limit = 2000) } catch (_: Exception) { emptyList() }
                chunkCache[kbId] = (now + CHUNK_CACHE_TTL_MS) to fresh
                fresh
            }
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
        val bm25Results = top.map { (score, idx) ->
            val c = chunks[idx]
            mapOf(
                "id" to ((c["id"] as? Number)?.toLong() ?: 0L),
                "content" to (c["content"] as? String ?: ""),
                "chunkIndex" to ((c["chunkIndex"] as? Number)?.toInt() ?: 0),
                "docTitle" to (c["docTitle"] as? String ?: ""),
                "score" to Math.round(score * 1000.0) / 1000.0
            )
        }

        // ★ v1.245 语义检索：配置了 embedding 模型（或自动识别 embedding/bge 类模型）→ 向量 top-k 与 BM25 做 RRF 融合
        val vecResults = runCatching { vectorSearch(db, kbId, query, chunks, topK * 2) }.getOrNull()
        if (vecResults.isNullOrEmpty()) return bm25Results
        val rrf = HashMap<Long, Double>()   // chunkId（全局唯一）→ RRF 分
        bm25Results.forEachIndexed { rank, r -> rrf.merge((r["id"] as Long), 1.0 / (60 + rank + 1), Double::plus) }
        vecResults.forEachIndexed { rank, r -> rrf.merge((r["id"] as Long), 1.0 / (60 + rank + 1), Double::plus) }
        val byIndex = chunks.associateBy { ((it["id"] as? Number)?.toLong() ?: 0L) }
        val merged = rrf.entries.sortedByDescending { it.value }.take(topK).map { (cid, sc) ->
            val c = byIndex[cid] ?: return@map null
            mapOf(
                "id" to cid,
                "content" to (c["content"] as? String ?: ""),
                "chunkIndex" to ((c["chunkIndex"] as? Number)?.toInt() ?: 0),
                "docTitle" to (c["docTitle"] as? String ?: ""),
                "score" to Math.round((sc * 1000.0) * 1000.0) / 1000.0
            )
        }.filterNotNull()
        return merged.ifEmpty { bm25Results }
    }

    // ============ ★ v1.245 语义检索（embedding + RRF 融合） ============

    /** 选 embedding 模型：优先配置 kb_embedding_model，否则自动识别 embedding/bge/m3e/gte 类模型（要求已启用） */
    fun resolveEmbeddingModel(db: Database): AiModel? {
        val cfg = runCatching { db.getConfig("kb_embedding_model", "") }.getOrDefault("").trim()
        val models = db.getModels().filter { it.isEnabled }
        if (cfg.isNotBlank()) return models.firstOrNull { it.modelId == cfg || it.customAlias == cfg }
        val re = Regex("(embed|bge|m3e|gte|text-embedding|qwen.*embed)", RegexOption.IGNORE_CASE)
        return models.firstOrNull { re.containsMatchIn(it.modelId) || re.containsMatchIn(it.displayName) }
    }

    /** 调 provider 的 /embeddings；批量 16 条一次 */
    fun embedTexts(db: Database, model: AiModel, texts: List<String>): List<DoubleArray>? {
        if (texts.isEmpty()) return emptyList()
        val pv = db.getProviderById(model.providerId) ?: return null
        var base = pv.baseUrl.trim().trimEnd('/')
        val cp = (pv.chatPath ?: "").trim()
        val url = when {
            cp.contains("/chat/completions") -> base + cp.substringBefore("/chat/completions") + "/embeddings"
            cp.isNotBlank() && cp.startsWith("/") -> base + cp
            else -> base + "/v1/embeddings"
        }
        val out = ArrayList<DoubleArray>()
        texts.chunked(16).forEach { batch ->
            val body = org.json.JSONObject().put("model", model.modelId)
            body.put("input", org.json.JSONArray(batch))
            val req = okhttp3.Request.Builder().url(url)
                .post(okhttp3.RequestBody.create("application/json".toMediaType(), body.toString()))
                .addHeader("Content-Type", "application/json")
                .apply { pv.apiKey?.takeIf { it.isNotBlank() }?.let { addHeader("Authorization", "Bearer $it") } }
                .build()
            val client = com.qitong.gateway.network.WebToolClient.http.newBuilder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val raw = resp.body?.string() ?: return null
                val arr = runCatching { org.json.JSONObject(raw).optJSONArray("data") }.getOrNull() ?: return null
                for (i in 0 until arr.length()) {
                    val e = arr.optJSONObject(i)?.optJSONArray("embedding") ?: continue
                    val v = DoubleArray(e.length()) { e.optDouble(it, 0.0) }
                    if (v.isNotEmpty()) out.add(v)
                }
            }
        }
        return if (out.size == texts.size) out else null
    }

    private fun cos(a: DoubleArray, b: DoubleArray): Double {
        if (a.size != b.size) return 0.0
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        if (na == 0.0 || nb == 0.0) return 0.0
        return dot / (Math.sqrt(na) * Math.sqrt(nb))
    }

    /** 向量检索：有缓存直接用，缺的现算并落库；返回按相似度排序的 chunk 列表 */
    private fun vectorSearch(db: Database, kbId: Long, query: String, chunks: List<Map<String, Any?>>, topK: Int): List<Map<String, Any?>>? {
        val model = resolveEmbeddingModel(db) ?: return null
        val cached = db.getKbVectors(kbId, model.modelId)
        val missing = chunks.filter { ((it["id"] as? Number)?.toLong() ?: 0L) !in cached }
        if (missing.isNotEmpty()) {
            val texts = missing.map { it["content"] as? String ?: "" }
            val vecs = embedTexts(db, model, texts) ?: return null
            val newOnes = HashMap<Long, DoubleArray>()
            missing.forEachIndexed { i, c ->
                val cid = (c["id"] as? Number)?.toLong() ?: 0L
                newOnes[cid] = vecs[i]; cached[cid] = vecs[i]
            }
            runCatching { db.saveKbVectors(kbId, model.modelId, newOnes) }
        }
        val qv = embedTexts(db, model, listOf(query))?.firstOrNull() ?: return null
        // 相似度阈值：绝对下限 0.30，且不低于最高分的 60%，避免弱相关 chunk 靠噪声挤进 RRF
        val raw = chunks.mapNotNull { c ->
            val cid = (c["id"] as? Number)?.toLong() ?: return@mapNotNull null
            val v = cached[cid] ?: return@mapNotNull null
            Triple(cid, cos(qv, v), c)
        }.sortedByDescending { it.second }
        if (raw.isEmpty()) return null
        val top = raw.first().second
        if (top < 0.30) return null
        val floor = Math.max(0.30, top * 0.60)
        val scored = raw.filter { it.second >= floor }.take(topK)
        return scored.map { (cid, sc, c) ->
            mapOf("id" to cid, "content" to (c["content"] as? String ?: ""),
                "chunkIndex" to ((c["chunkIndex"] as? Number)?.toInt() ?: 0),
                "docTitle" to (c["docTitle"] as? String ?: ""), "score" to sc)
        }
    }

    /** ★ v1.249 重建某知识库的全部向量（清空后按当前 embedding 模型重算） */
    fun reindex(db: Database, kbId: Long): Pair<Int, String> {
        val model = resolveEmbeddingModel(db) ?: return 0 to "没有可用的 embedding 模型：请先在模型页启用一个 embedding 模型，或在下面手动指定"
        val chunks = db.getKbChunks(kbId, limit = 5000)
        if (chunks.isEmpty()) return 0 to "知识库还没有分块文档"
        val texts = chunks.map { it["content"] as? String ?: "" }
        val vecs = embedTexts(db, model, texts) ?: return 0 to "embedding 调用失败（服务商不支持 /embeddings 或返回为空）"
        val map = HashMap<Long, DoubleArray>()
        chunks.forEachIndexed { i, c -> map[((c["id"] as? Number)?.toLong() ?: 0L)] = vecs[i] }
        db.clearKbVectors(kbId)
        db.saveKbVectors(kbId, model.modelId, map)
        return map.size to "已重建 ${map.size} 块向量（模型 ${model.modelId}，维度 ${vecs.first().size}）"
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