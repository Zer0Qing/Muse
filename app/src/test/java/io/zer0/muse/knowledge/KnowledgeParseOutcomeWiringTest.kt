package io.zer0.muse.knowledge

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.doc.DocumentParser
import io.zer0.muse.knowledge.ingest.DocumentParserAdapter
import io.zer0.muse.knowledge.ingest.OcrRequirement
import io.zer0.muse.knowledge.ingest.ParseCoverage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * R3 接线合同：解析型导入必须按 [ParseCoverage] 分流，不能只看「文本是否非空」。
 *
 * 背景：导入链此前直接把 `parseResult` 的字符串拿去建索引 —— 截断标记被当正文写进 chunk，
 * 扫描件（无文字层）会被当成「导入成功」，用户以为进了库，检索却永远搜不到。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class KnowledgeParseOutcomeWiringTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val adapter = DocumentParserAdapter(DocumentParser())

    // ---------- 适配器分流 ----------

    @Test
    fun `truncated text becomes partial and marker never reaches blocks`() {
        val file = File(context.cacheDir, "truncated.md")
        // 超过 1MB 上限，触发旧链截断标记
        file.writeText("正文内容".repeat(400_000), Charsets.UTF_8)

        val outcome = adapter.parseToOutcome(Uri.fromFile(file), context, "truncated.md")

        assertEquals("超限文本必须是 PARTIAL", ParseCoverage.PARTIAL, outcome.coverage)
        assertTrue("PARTIAL 必须带覆盖说明", outcome.coverageNote.isNotBlank())
        val joined = outcome.blocks.joinToString("\n") { it.text }
        assertFalse("截断标记不得进入 blocks（会被写进 chunk）", joined.contains("已截断"))
        assertFalse("不得出现截断标记的方括号形态", joined.contains("[…"))
    }

    @Test
    fun `legacy doc is failed with actionable message`() {
        val file = File(context.cacheDir, "legacy.doc")
        file.writeText("这不是真的 doc")

        val outcome = adapter.parseToOutcome(Uri.fromFile(file), context, "legacy.doc")

        assertEquals("旧版 .doc 必须 FAILED", ParseCoverage.FAILED, outcome.coverage)
        assertTrue("必须给出可执行的下一步(docx)", outcome.error.contains("docx"))
        assertTrue("FAILED 不得产出 blocks", outcome.blocks.isEmpty())
    }

    @Test
    fun `image source requires ocr instead of fake text`() {
        val file = File(context.cacheDir, "scan.png")
        // 写点二进制，模拟图片
        file.writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x00, 0x01, 0x02))

        val outcome = adapter.parseToOutcome(Uri.fromFile(file), context, "scan.png")

        assertEquals("图片不得被当文本解析", ParseCoverage.NO_TEXT, outcome.coverage)
        assertEquals(
            "图片必须挂待授权 OCR，而不是静默当空文件",
            OcrRequirement.REQUIRED_PENDING_POLICY,
            outcome.ocrRequirement,
        )
        assertTrue(outcome.blocks.isEmpty())
    }

    @Test
    fun `unknown format is failed not txt fallback`() {
        val file = File(context.cacheDir, "payload.exe")
        file.writeBytes(byteArrayOf(0x4D, 0x5A, 0x00, 0x01))

        val outcome = adapter.parseToOutcome(Uri.fromFile(file), context, "payload.exe")

        assertEquals("未知格式必须 FAILED", ParseCoverage.FAILED, outcome.coverage)
        assertTrue(outcome.blocks.isEmpty())
    }

    // ---------- partial 覆盖度写进 metadata_json ----------

    @Test
    fun `partial coverage is persisted into metadata json without new room column`() {
        val partial = io.zer0.muse.ui.knowledge.withParseCoverage(
            metadataJson = """{"folderPath":"合同"}""",
            coverage = ParseCoverage.PARTIAL,
            coverageNote = "页数上限 200",
        )

        val parsed = io.zer0.common.AppJson.parseToJsonElement(partial).let {
            it as kotlinx.serialization.json.JsonObject
        }
        assertEquals(
            "PARTIAL",
            (parsed["coverage"] as kotlinx.serialization.json.JsonPrimitive).content,
        )
        assertEquals(
            "页数上限 200",
            (parsed["coverageNote"] as kotlinx.serialization.json.JsonPrimitive).content,
        )
        assertEquals(
            "既有 folderPath 不能被覆盖",
            "合同",
            (parsed["folderPath"] as kotlinx.serialization.json.JsonPrimitive).content,
        )
    }

    @Test
    fun `coverage reader returns null for legacy documents`() {
        assertEquals(
            "旧文档没有 coverage 键，按完整处理（不改变既有行为）",
            null,
            io.zer0.muse.ui.knowledge.parseCoverageOf("""{"folderPath":""}"""),
        )
        assertEquals(
            ParseCoverage.PARTIAL,
            io.zer0.muse.ui.knowledge.parseCoverageOf("""{"coverage":"PARTIAL"}"""),
        )
    }

    @Test
    fun `complete coverage round trips`() {
        val json = io.zer0.muse.ui.knowledge.withParseCoverage(
            metadataJson = "{}",
            coverage = ParseCoverage.COMPLETE,
            coverageNote = "",
        )
        assertEquals(ParseCoverage.COMPLETE, io.zer0.muse.ui.knowledge.parseCoverageOf(json))
    }
}
