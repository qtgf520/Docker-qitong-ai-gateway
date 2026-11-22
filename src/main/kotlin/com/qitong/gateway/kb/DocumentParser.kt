package com.qitong.gateway.kb

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * ★ v1.246 知识库文件解析：PDF / DOCX / TXT / MD → 纯文本
 * 不依赖外部服务，全部本地解析；旧版 .doc 直接给出明确提示。
 */
object DocumentParser {

    const val MAX_TEXT_CHARS = 2_000_000

    fun parse(fileName: String, bytes: ByteArray): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val raw = when (ext) {
            "pdf" -> parsePdf(bytes)
            "docx" -> parseDocx(bytes)
            "doc" -> throw IllegalArgumentException("旧版 .doc 无法直接解析，请用 Word 另存为 .docx 后上传")
            "txt", "md", "markdown", "csv", "json", "log", "" -> bytes.toString(Charsets.UTF_8)
            else -> throw IllegalArgumentException("暂不支持 .$ext，支持 PDF / DOCX / TXT / MD / CSV / JSON")
        }
        return raw.replace("\r\n", "\n").replace("\u0000", "")
            .lines().joinToString("\n") { it.trimEnd() }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
            .take(MAX_TEXT_CHARS)
    }

    private fun parsePdf(bytes: ByteArray): String {
        val text = org.apache.pdfbox.Loader.loadPDF(bytes).use { doc ->
            org.apache.pdfbox.text.PDFTextStripper().apply { sortByPosition = true }.getText(doc)
        }
        if (text.isBlank()) throw IllegalArgumentException("PDF 中没有可提取的文字（可能是扫描件/图片版，请先 OCR）")
        return text
    }

    private fun parseDocx(bytes: ByteArray): String {
        val xml = ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            var found: ByteArray? = null
            while (entry != null) {
                if (entry.name == "word/document.xml") { found = zip.readBytes(); break }
                entry = zip.nextEntry
            }
            found
        } ?: throw IllegalArgumentException("不是有效的 DOCX（缺少 word/document.xml）")
        var s = String(xml, Charsets.UTF_8)
        s = s.replace("</w:p>", "\n").replace("</w:tr>", "\n").replace("</w:tc>", "\t")
            .replace("<w:tab/>", "\t").replace("<w:br/>", "\n")
        s = s.replace(Regex("<[^>]+>"), "")
        s = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&apos;", "'").replace("&amp;", "&")
        s = Regex("&#(\\d+);").replace(s) { m -> m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: "" }
        return s
    }
}
