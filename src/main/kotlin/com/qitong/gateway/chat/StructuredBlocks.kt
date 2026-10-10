package com.qitong.gateway.chat

/**
 * ★ v1.121 结构化消息块 —— 「思考→工具→结果→答案」流水渲染模型（自研实现）
 *
 * 把助手回复（可能含 <thinking>/<think>/<tool>/<tool_result>/<search>/<details> 等 XML 标签）
 * 解析成扁平块，再按「思考与工具」语义分组，前端据此渲染成折叠卡片流水。
 *
 * 纯 Kotlin 实现：手写流式 XML 切分（自研流式 XML 切分器）+ 递归分组。
 */

/** 块类型：文本 或 XML 标签 */
enum class BlockKind { TEXT, XML }

/** 数据结构（对应前端 WebMessageContentBlock 的 JSON 结构） */
data class ContentBlock(
    val kind: String,                 // "text" / "xml" / "group"
    val content: String? = null,      // 文本或标签内内容
    val xml: String? = null,          // 原始 XML 串
    val tagName: String? = null,      // 归一化标签名（tool / tool_result / think...）
    val attrs: Map<String, String> = emptyMap(),
    val closed: Boolean = true,       // 标签是否完整闭合（流式中未闭合=true 表示仍在输出）
    val groupType: String? = null,    // group 专用: think_tools / tools_only / search_only
    val children: List<ContentBlock>? = null
)

/** 结构化渲染偏好 */
object StructuredBlocks {

    private val READ_ONLY_TOOLS = setOf(
        "list_files", "grep_code", "grep_context", "read_file", "read_file_part",
        "find_files", "visit_web", "query_memory", "get_memory_by_title", "read_file_full"
    )

    private val IGNORABLE_TAGS = setOf("meta", "status", "font", "mood")

    // ============ 1. XML 切分（纯 Kotlin 扫描，替代 JNI） ============

    /**
     * 把整段内容切成 [TEXT 块, XML 块, TEXT 块, ...]。
     * 识别 <tag ...> ... </tag> 与自闭合 <tag .../>；未闭合的也算一个块（closed=false，流式中）。
     * 支持 tool_xxx / tool_result_xxx 等带后缀标签。
     */
    fun split(content: String): List<Block> {
        if (content.isBlank()) return emptyList()
        val blocks = mutableListOf<Block>()
        var i = 0
        val n = content.length
        var textStart = 0
        while (i < n) {
            val lt = content.indexOf('<', i)
            if (lt < 0) break
            // 有可能是标签开头：下一个字符是字母
            val next = if (lt + 1 < n) content[lt + 1] else ' '
            if (!next.isLetter()) { i = lt + 1; continue }
            // 找标签名
            var j = lt + 1
            while (j < n && (content[j].isLetterOrDigit() || content[j] == '_')) j++
            if (j >= n) { i = lt + 1; continue }
            val tagName = content.substring(lt + 1, j)
            if (tagName.startsWith("!--")) { i = lt + 1; continue }  // 注释跳过
            // 找这个开始标签的 '>' 结束位置
            val closing = findTagEnd(content, j)
            if (closing < 0) { i = lt + 1; continue }
            val afterOpenBracket = closing + 1
            val isSelfClosing = content[closing - 1] == '/'
            // 开始标签前的文本
            if (lt > textStart) {
                val chunk = content.substring(textStart, lt)
                if (chunk.isNotBlank()) blocks.add(Block(BlockKind.TEXT, chunk, chunk))
            }
            if (isSelfClosing) {
                blocks.add(Block(BlockKind.XML, content.substring(lt, afterOpenBracket), "",
                    tagName, extractAttrs(content.substring(lt, closing + 1)), closed = true))
                i = afterOpenBracket; textStart = i; continue
            }
            // 找闭合标签 </tagName>
            val endTag = "</$tagName"
            val endIdx = findCloseTag(content, afterOpenBracket, endTag)
            if (endIdx >= 0) {
                val endTagEnd = content.indexOf('>', endIdx)
                if (endTagEnd >= 0) {
                    val raw = content.substring(lt, endTagEnd + 1)
                    val inner = content.substring(afterOpenBracket, endIdx)
                    blocks.add(Block(BlockKind.XML, raw, inner, tagName, extractAttrs(content.substring(lt, closing + 1)), closed = true))
                    i = endTagEnd + 1; textStart = i; continue
                }
            }
            // 未闭合：作为 open 块（流式中）
            blocks.add(Block(BlockKind.XML, content.substring(lt), content.substring(afterOpenBracket),
                tagName, extractAttrs(content.substring(lt, closing + 1)), closed = false))
            i = n; textStart = n
        }
        if (textStart < n) {
            val tail = content.substring(textStart)
            if (tail.isNotBlank()) blocks.add(Block(BlockKind.TEXT, tail, tail))
        }
        return blocks
    }

    private fun findTagEnd(s: String, from: Int): Int {
        var i = from
        var inQuote = ' '
        while (i < s.length) {
            val c = s[i]
            if (inQuote != ' ') {
                if (c == inQuote) inQuote = ' '
            } else if (c == '"' || c == '\'') {
                inQuote = c
            } else if (c == '>') {
                return i
            }
            i++
        }
        return -1
    }

    private fun findCloseTag(s: String, from: Int, endTag: String): Int {
        var i = from
        while (true) {
            val idx = s.indexOf(endTag, i)
            if (idx < 0) return -1
            // 确认后面是 > 或空白（避免 </toolbox> 匹配到 </tool）
            val after = if (idx + endTag.length < s.length) s[idx + endTag.length] else '>'
            if (after == '>' || after == ' ' || after == '\t' || after == '\n' || after == '\r') return idx
            i = idx + endTag.length
        }
    }

    private fun extractAttrs(startTag: String): Map<String, String> {
        if (startTag.isBlank()) return emptyMap()
        val attrs = linkedMapOf<String, String>()
        val re = Regex("""([A-Za-z_:][A-Za-z0-9_.:-]*)\s*=\s*(['"])([\s\S]*?)\2""")
        for (m in re.findAll(startTag)) {
            val name = m.groupValues.getOrNull(1)?.trim().orEmpty()
            val value = m.groupValues.getOrNull(3).orEmpty()
            if (name.isNotBlank()) attrs[name] = value
        }
        return attrs
    }

    data class Block(
        val kind: BlockKind,
        val rawContent: String,
        val content: String,
        val tagName: String? = null,
        val attrs: Map<String, String> = emptyMap(),
        val closed: Boolean = true
    )

    // ============ 2. 扁平块 → 结构化块 ============

    fun parseToBlocks(content: String): List<ContentBlock>? {
        if (content.isBlank()) return null
        val flat = split(content).mapNotNull { b ->
            when (b.kind) {
                BlockKind.TEXT -> if (b.content.isBlank()) null
                    else ContentBlock(kind = "text", content = b.content)
                BlockKind.XML -> ContentBlock(
                    kind = "xml", content = b.content, xml = b.rawContent,
                    tagName = b.tagName?.lowercase(), attrs = b.attrs, closed = b.closed
                )
            }
        }
        if (flat.isEmpty()) return null
        val grouped = group(flat)
        return grouped.takeIf { it.isNotEmpty() }
    }

    /** 分组：think/tool/tool_result/search 连续段折叠为 group（think_tools） */
    private fun group(blocks: List<ContentBlock>): List<ContentBlock> {
        val out = mutableListOf<ContentBlock>()
        var i = 0
        while (i < blocks.size) {
            val b = blocks[i]
            if (b.kind != "xml") { out += b; i++; continue }
            val tag = b.tagName ?: ""
            // 思考块开头 → 收集后续配套工具块
            if ((tag == "think" || tag == "thinking") && (blocks.size - i) > 1) {
                var j = i + 1
                var toolCount = 0; var searchCount = 0
                while (j < blocks.size) {
                    val nb = blocks[j]
                    if (nb.kind == "xml" && IGNORABLE_TAGS.contains(nb.tagName)) { j++; continue }
                    if (nb.kind == "text" && nb.content?.isBlank() == true) { j++; continue }
                    if (nb.kind != "xml") break
                    val nt = nb.tagName ?: break
                    if (nt == "think" || nt == "thinking") { j++; continue }  // 连续思考并入组
                    else if (nt == "tool" || nt == "tool_result" || nt == "search") { if (nt == "tool") toolCount++; if (nt == "search") searchCount++; j++; continue }
                    else break
                }
                if (j > i + 1) {
                    val children = blocks.subList(i, j).toList()
                    val groupType = when {
                        searchCount > 0 && toolCount > 0 -> "think_tools"
                        toolCount > 0 -> "think_tools"
                        searchCount > 0 -> "search_only"
                        else -> "think_tools"
                    }
                    out += ContentBlock(kind = "group", groupType = groupType, children = children)
                    i = j; continue
                }
            }
            // 工具块开头 → 收集后续连续工具/结果
            if (tag == "tool" || tag == "tool_result") {
                var j = i + 1
                var toolCount = if (tag == "tool") 1 else 0
                while (j < blocks.size) {
                    val nb = blocks[j]
                    if (nb.kind == "text" && nb.content?.isBlank() == true) { j++; continue }
                    if (nb.kind != "xml") break
                    val nt = nb.tagName ?: break
                    if (nt == "tool") { toolCount++; j++; continue }
                    if (nt == "tool_result" || nt == "search") { j++; continue }
                    if (IGNORABLE_TAGS.contains(nt)) { j++; continue }
                    break
                }
                if (j > i + 1) {
                    out += ContentBlock(kind = "group", groupType = "tools_only", children = blocks.subList(i, j).toList())
                    i = j; continue
                }
            }
            out += b; i++
        }
        return out
    }

    /** 判断是否该把工具折叠（read_only 类或全折叠） */
    fun shouldGroupTool(name: String?, collapseAll: Boolean = true): Boolean {
        if (collapseAll) return true
        val n = (name ?: "").trim().lowercase()
        if (n.contains("search")) return true
        return READ_ONLY_TOOLS.contains(n)
    }

    /** 递归转 Map（供 JSON 序列化——encodeElement 不认识 data class，必须转 Map） */
    fun toMap(b: ContentBlock): Map<String, Any?> = mapOf(
        "kind" to b.kind,
        "content" to b.content,
        "xml" to b.xml,
        "tagName" to b.tagName,
        "attrs" to b.attrs,
        "closed" to b.closed,
        "groupType" to b.groupType,
        "children" to b.children?.map { toMap(it) }
    )

    fun parseToBlockMaps(content: String): List<Map<String, Any?>>? =
        parseToBlocks(content)?.map { toMap(it) }
}