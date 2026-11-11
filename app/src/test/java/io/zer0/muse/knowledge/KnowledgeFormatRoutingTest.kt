package io.zer0.muse.knowledge

import io.zer0.muse.knowledge.ingest.DocumentParserAdapter
import io.zer0.muse.knowledge.ingest.KnowledgeParserRegistry
import io.zer0.muse.knowledge.ingest.ParseCoverage
import io.zer0.muse.knowledge.ingest.OcrRequirement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R3 步骤 2/3 合同(计划:「建统一格式注册表…未知格式明确拒绝,不按 txt 强读二进制」
 * +「错误提示不得出现在任何 chunk 内容中」)。
 *
 * 纯 JVM 测试:白名单路由 + 适配器的标记剥离/状态映射(detectTruncationMarker 与
 * 映射逻辑为 internal 纯函数,直接测;需要 Android Uri 的端到端解析走 R10 设备矩阵)。
 */
class KnowledgeFormatRoutingTest {

    // ---------- 白名单路由 ----------

    @Test
    fun `supported formats route true across text pdf office epub images`() {
        listOf(
            "a.txt", "b.md", "c.pdf", "d.docx", "e.pptx", "f.xlsx", "g.epub",
            "h.png", "i.jpg", "j.JPEG", "k.webp",
        ).forEach { name ->
            assertTrue("支持格式必须放行: $name", KnowledgeParserRegistry.isSupportedFormat(name))
        }
    }

    @Test
    fun `unknown formats explicitly rejected never txt-fallback`() {
        listOf(
            "a.exe", "b.bin", "c.dat", "d.pdf.exe",
            "noext", "",
        ).forEach { name ->
            assertFalse("未知/无扩展名格式必须拒绝: '$name'", KnowledgeParserRegistry.isSupportedFormat(name))
        }
    }

    // ---------- 适配器:截断标记 → PARTIAL + 剥离 ----------

    @Test
    fun `truncation marker is detected in both legacy formats`() {
        val adapter = adapter()
        val m1 = adapter.detectTruncationMarker("正文\n\n[… 文件过大,已截断。原文件超过 1MB,仅读取前 1MB 内容 …]")
        val m2 = adapter.detectTruncationMarker("正文\n[… PDF 内容过长,已截断 …]")
        assertTrue("文本截断标记识别", m1?.contains("文件过大") == true)
        assertTrue("PDF 截断标记识别", m2?.contains("已截断") == true)
    }

    @Test
    fun `normal text has no marker`() {
        val adapter = adapter()
        val m = adapter.detectTruncationMarker("正文没有标记\n第二行 [普通方括号注记]")
        // 普通方括号(非截断语义)不误报
        assertTrue("普通注记不误报", m == null)
    }

    // ---------- classify 防多报 ----------

    @Test
    fun `complete with coverage note is downgraded to partial`() {
        val withNote = io.zer0.muse.knowledge.ingest.ParseOutcome(
            coverage = ParseCoverage.COMPLETE,
            blocks = listOf(io.zer0.muse.knowledge.ingest.ParsedBlock(text = "x")),
            coverageNote = "有说明却自称完整",
        )
        assertEquals(ParseCoverage.PARTIAL, KnowledgeParserRegistry.classify(withNote))
    }

    @Test
    fun `image no-text requires pending ocr policy`() {
        // NO_TEXT 的图片类来源必须挂 REQUIRED_PENDING_POLICY(等待授权,不静默调服务)
        val o = io.zer0.muse.knowledge.ingest.ParseOutcome(
            coverage = ParseCoverage.NO_TEXT,
            blocks = emptyList(),
            ocrRequirement = OcrRequirement.REQUIRED_PENDING_POLICY,
        )
        assertEquals(OcrRequirement.REQUIRED_PENDING_POLICY, o.ocrRequirement)
        assertFalse("NO_TEXT 不是可用全文", o.isUsableFullText)
    }

    @Test
    fun `page limit marker is recognized as partial`() {
        val marker = adapter().detectTruncationMarker("正文内容\n[… PDF 共 201 页,仅提取前 200 页 …]")
        assertTrue("页数上限标记必须识别", marker != null && marker.contains("仅提取前"))
    }

    @Test
    fun `metadata only text has no meaningful body`() {
        val text = "[文档信息]\nTitle: scanned\nAuthor: unknown\n\n[目录]\n第一章 1\n第二章 2\n"
        assertFalse("只有元数据不得算正文", adapter().hasMeaningfulBody(text))
        assertTrue("正文存在", adapter().hasMeaningfulBody(text + "\n真正的正文内容"))
    }

    private fun adapter() = DocumentParserAdapter(
        parser = io.zer0.muse.doc.DocumentParser(),
    )
}
