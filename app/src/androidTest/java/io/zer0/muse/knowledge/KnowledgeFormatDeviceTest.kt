package io.zer0.muse.knowledge

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.zer0.muse.doc.DocumentParser
import io.zer0.muse.knowledge.ingest.DocumentParserAdapter
import io.zer0.muse.knowledge.ingest.ParseCoverage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * R10 步骤「验证扫描/部分PDF、归档条目、XLSX表格…」(计划 R10)。
 *
 * 真实 Android 设备面解析:用 R0 固定夹具验证 PDF 损坏/超页/扫描件与 XLSX 表格。
 * 归档条目（special-paths.zip）与外部服务错误/取消另行覆盖。
 */
@RunWith(AndroidJUnit4::class)
class KnowledgeFormatDeviceTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val adapter = DocumentParserAdapter(DocumentParser())
    private val tempFiles = mutableListOf<File>()

    @After
    fun tearDown() {
        tempFiles.forEach { it.delete() }
        tempFiles.clear()
    }

    private fun assetFile(assetPath: String): Uri {
        val file = File(context.cacheDir, "kb-fixture-${assetPath.substringAfterLast('/')}")
        InstrumentationRegistry.getInstrumentation().context.assets.open(assetPath).use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        tempFiles.add(file)
        return Uri.fromFile(file)
    }

    @Test
    fun corruptPdfIsFailedNotFakeComplete() {
        val outcome = adapter.parseToOutcome(
            uri = assetFile("knowledge/r0-v1/parse/corrupt.pdf"),
            context = context,
            fileName = "corrupt.pdf",
        )
        assertEquals("损坏 PDF 必须是 FAILED", ParseCoverage.FAILED, outcome.coverage)
        assertTrue("错误不得进入 blocks", outcome.blocks.none { it.text.contains("错误") || it.text.contains("Exception") })
    }

    @Test
    fun overLimitPdfIsPartialWithCoverageNote() {
        val outcome = adapter.parseToOutcome(
            uri = assetFile("knowledge/r0-v1/parse/over-200-pages.pdf"),
            context = context,
            fileName = "over-200-pages.pdf",
        )
        assertTrue(
            "超页 PDF 应为 PARTIAL(实际=${outcome.coverage})",
            outcome.coverage == ParseCoverage.PARTIAL,
        )
        assertTrue("PARTIAL 必须带覆盖说明", outcome.coverageNote.isNotBlank())
    }

    @Test
    fun scannedPdfDoesNotPretendCompleteText() {
        val outcome = adapter.parseToOutcome(
            uri = assetFile("knowledge/r0-v1/parse/scanned-needs-ocr.pdf"),
            context = context,
            fileName = "scanned-needs-ocr.pdf",
        )
        // 扫描件在旧解析链无 OCR 时不得产出可检索正文;允许 NO_TEXT 或 FAILED。
        assertTrue(
            "扫描 PDF 不得假装 COMPLETE(实际=${outcome.coverage})",
            outcome.coverage == ParseCoverage.NO_TEXT || outcome.coverage == ParseCoverage.FAILED,
        )
    }

    @Test
    fun xlsxTableParsesCompleteWithCellText() = runBlocking {
        val outcome = adapter.parseToOutcome(
            uri = assetFile("knowledge/r0-v1/parse/table.xlsx"),
            context = context,
            fileName = "table.xlsx",
        )
        assertEquals("XLSX 应完整解析(实际=${outcome.coverage}, error=${outcome.error})", ParseCoverage.COMPLETE, outcome.coverage)
        val text = outcome.blocks.joinToString("\n") { it.text }
        assertTrue("表格单元格内容必须保留: $text", text.contains("XLSX-TAIL"))
    }
}
