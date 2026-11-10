package io.zer0.muse.data.sticker

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.zer0.common.Result
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 文档形态的表情包导入（表情包重做 P2）。
 *
 * 走的是用户真实入口 `importUri`：市面上传的素材就是 docx/pptx，
 * 只测解析器而不接入口，用户选完文件看到的仍然会是"不支持的文件类型"。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [33])
class StickerDocumentImportTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val repository = StickerLibraryRepository(context)
    private lateinit var workDir: File

    @Before
    fun setUp() = runTest {
        repository.clearAll()
        workDir = kotlin.io.path.createTempDirectory("muse-sticker-doc").toFile()
    }

    @After
    fun tearDown() = runTest {
        repository.clearAll()
        workDir.deleteRecursively()
    }

    private fun writeDocx(name: String, document: String, media: Map<String, ByteArray>): Uri {
        val file = File(workDir, name)
        ZipOutputStream(FileOutputStream(file), Charsets.UTF_8).use { zos ->
            fun put(path: String, bytes: ByteArray) {
                zos.putNextEntry(ZipEntry(path))
                zos.write(bytes)
                zos.closeEntry()
            }
            put("word/document.xml", document.toByteArray(Charsets.UTF_8))
            val rels = media.keys.mapIndexed { index, path ->
                "<Relationship Id=\"rId" + (index + 1) + "\" Type=\"image\" Target=\"" +
                    path.removePrefix("word/") + "\"/>"
            }.joinToString("")
            put("word/_rels/document.xml.rels", ("<Relationships>$rels</Relationships>").toByteArray(Charsets.UTF_8))
            media.forEach { (path, bytes) -> put(path, bytes) }
        }
        return Uri.fromFile(file)
    }

    private fun imageParagraph(rid: String) = "<w:p><w:r><w:drawing><a:blip r:embed=\"" + rid + "\"/></w:drawing></w:r></w:p>"

    private fun textParagraph(text: String) = "<w:p><w:r><w:t>" + text + "</w:t></w:r></w:p>"

    @Test
    fun `a docx goes through importUri and keeps its captions in reading order`() = runTest {
        val uri = writeDocx(
            "pack.docx",
            "<w:document><w:body>" +
                textParagraph("呆猫八条") +
                imageParagraph("rId1") +
                textParagraph("https://pic1.imgdb.cn/i/aaa.jpg（小猫歪头）") +
                imageParagraph("rId2") +
                textParagraph("小猫躲墙后") +
                "</w:body></w:document>",
            mapOf(
                "word/media/image1.png" to byteArrayOf(1, 2, 3, 4),
                "word/media/image2.png" to byteArrayOf(5, 6, 7, 8),
            ),
        )

        val result = repository.importDocument(uri, "pack.docx")

        assertTrue("应当导入成功: $result", result.isSuccess)
        assertEquals(2, result.getOrNull())
        val items = repository.listStickers()
        assertEquals("正文首段做分类名", "呆猫八条", items.first().category)
        assertEquals("图后的描述要留在配文里", "（小猫歪头）", items.first().caption)
        assertEquals("第二张的配文不能串到第一张", "小猫躲墙后", items[1].caption)
        assertEquals("按文档阅读顺序落库", "image1.png", items.first().fileName)
        assertTrue("文件要真的落盘", File(context.filesDir, items.first().relativePath).isFile)
    }

    @Test
    fun `identical bytes inside one document are deduped`() = runTest {
        val uri = writeDocx(
            "dup.docx",
            "<w:body>" + imageParagraph("rId1") + imageParagraph("rId2") + "</w:body>",
            mapOf(
                "word/media/a.png" to byteArrayOf(9, 9, 9),
                "word/media/b.png" to byteArrayOf(9, 9, 9),
            ),
        )

        val result = repository.importDocument(uri, "dup.docx")
        assertTrue("应当导入成功: $result", result.isSuccess)

        assertEquals("同一字节内容只留一张", 1, repository.listStickers().size)
    }

    @Test
    fun `the container read from a content uri sees the same entries as an in-memory one`() {
        val uri = writeDocx(
            "probe.docx",
            "<w:body>" + imageParagraph("rId1") + "</w:body>",
            mapOf("word/media/image1.png" to byteArrayOf(1, 2, 3, 4)),
        )
        val container = context.contentResolver.openInputStream(uri)?.use {
            StickerContainerReader.read(it)
        }

        assertNotNull("容器应当被认出来", container)
        // 把中间量摊开：条目名、候选数、配文，一次看清哪一环空了
        val keys = container!!.entries.keys.sorted()
        assertTrue("媒体条目要在: $keys", keys.any { it.startsWith("word/media/") })
        val scanned = StickerDocumentScanner.scan(container.kind, container.entries)
        assertEquals(
            "候选应为 1:\n  rels=${container.entries["word/_rels/document.xml.rels"]?.decodeToString()}" +
                "\n  doc=${container.entries["word/document.xml"]?.decodeToString()}" +
                "\n  candidates=${scanned.candidates}",
            1,
            scanned.candidates.size,
        )
        assertNotNull(
            "媒体要能在条目表里找到: ${scanned.candidates.map { it.mediaPath }}",
            container.entries[scanned.candidates.first().mediaPath],
        )
    }

    @Test
    fun `a non-document file is refused with a reason we can act on`() = runTest {
        val file = File(workDir, "notes.txt").apply { writeText("just text") }

        val result = repository.importDocument(Uri.fromFile(file), "notes.txt")

        assertTrue("纯文本不是素材容器", result is Result.Error)
        val message = (result as Result.Error).message
        assertTrue("要说清不是有效文档: $message", message.contains("docx") || message.contains("文档"))
    }

    @Test
    fun `a file uri without a usable name still falls back to the zip path`() = runTest {
        // 无名入口：先按文档容器嗅，不是文档再回落压缩包 —— 这条保证回落链没断
        val zip = File(workDir, "packs.zip")
        ZipOutputStream(FileOutputStream(zip), Charsets.UTF_8).use { zos ->
            zos.putNextEntry(ZipEntry("猫猫/001.png"))
            zos.write(byteArrayOf(1, 2, 3, 4, 5))
            zos.closeEntry()
        }

        val result = repository.importUri(Uri.fromFile(zip))

        assertTrue("压缩包仍然能导: $result", result.isSuccess)
        assertEquals(1, repository.listStickers().size)
    }
}
