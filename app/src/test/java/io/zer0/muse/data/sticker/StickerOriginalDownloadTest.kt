package io.zer0.muse.data.sticker

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.zer0.common.Result
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
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
 * "按原图链接下载"这条批处理（表情包重做 P5）。
 *
 * 联网那段用**注入的 fetcher** 测：真实 HTTP 留给真机，
 * 而"单条失败不掀整批""没有链接要如实说""草稿图被替换而不是叠加"
 * 这些是逻辑，能在 JVM 上钉死。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [33])
class StickerOriginalDownloadTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val repository = StickerLibraryRepository(context)
    private lateinit var workDir: File

    @Before
    fun setUp() = runTest {
        repository.clearAll()
        workDir = kotlin.io.path.createTempDirectory("muse-dl").toFile()
    }

    @After
    fun tearDown() = runTest {
        repository.clearAll()
        workDir.deleteRecursively()
    }

    /** 三张图，其中两张正文里带原图直链。 */
    private fun docxWithLinks(): Uri {
        val document = "<w:document><w:body>" +
            "<w:p><w:r><w:t>链接包</w:t></w:r></w:p>" +
            img("rId1") + txt("https://93.184.216.34/i/one.jpg（一张）") +
            img("rId2") + txt("（二张，没有链接）") +
            img("rId3") + txt("https://93.184.216.34/i/three.jpg（三张）") +
            "</w:body></w:document>"
        val file = File(workDir, "links.docx")
        ZipOutputStream(FileOutputStream(file), Charsets.UTF_8).use { zos ->
            fun put(path: String, bytes: ByteArray) {
                zos.putNextEntry(ZipEntry(path))
                zos.write(bytes)
                zos.closeEntry()
            }
            put("word/document.xml", document.toByteArray(Charsets.UTF_8))
            val rels = (1..3).joinToString("") { i ->
                "<Relationship Id=\"rId" + i + "\" Type=\"image\" Target=\"media/image" + i + ".png\"/>"
            }
            put("word/_rels/document.xml.rels", ("<Relationships>" + rels + "</Relationships>").toByteArray(Charsets.UTF_8))
            (1..3).forEach { i ->
                put(
                    "word/media/image$i.png",
                    byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, i.toByte(), 9, 9),
                )
            }
        }
        return Uri.fromFile(file)
    }

    private fun img(rid: String) = "<w:p><w:r><w:drawing><a:blip r:embed=\"" + rid + "\"/></w:drawing></w:r></w:p>"

    private fun txt(text: String) = "<w:p><w:r><w:t>" + text + "</w:t></w:r></w:p>"

    private fun pngBytes(seed: Int) = ByteArray(64) { ((it + seed) % 251).toByte() }

    @Test
    fun `links found in the document are kept on the draft`() = runTest {
        val draft = repository.prepareDocument(docxWithLinks(), "links.docx").getOrThrow()

        val withUrl = draft.items.filter { it.sourceUrl != null }
        assertEquals("两张带链接", 2, withUrl.size)
        assertTrue(
            "链接要落在对应那张上: ${withUrl.map { it.sourceUrl }}",
            withUrl.map { it.sourceUrl }.any { it!!.endsWith("one.jpg") },
        )
    }

    @Test
    fun `download replaces the draft image and counts only successes`() = runTest {
        val draft = repository.prepareDocument(docxWithLinks(), "links.docx").getOrThrow()
        var calls = 0

        val result = repository.downloadDraftOriginals(draft.id) { url, target ->
            calls++
            target.writeBytes(pngBytes(url.length))
            null
        }

        val (replaced, failures) = result.getOrThrow()
        assertEquals("两条链接都成功", 2, replaced)
        assertEquals("每条链接只被请求一次", 2, calls)
        assertTrue("不该有失败: $failures", failures.isEmpty())
        val sizes = draft.items.map { it.file.length() }
        assertTrue("无链接那张不该被动: $sizes", sizes.contains(11L))
        assertTrue("有链接的已被替换成 64 字节", sizes.count { it == 64L } == 2)
    }

    @Test
    fun `one bad link does not fail the whole batch`() = runTest {
        val draft = repository.prepareDocument(docxWithLinks(), "links.docx").getOrThrow()

        val result = repository.downloadDraftOriginals(draft.id) { url, target ->
            if (url.endsWith("one.jpg")) {
                "HTTP 404"
            } else {
                target.writeBytes(pngBytes(7))
                null
            }
        }

        val (replaced, failures) = result.getOrThrow()
        assertEquals("坏的那条不挡好的", 1, replaced)
        assertEquals("失败要报出来", 1, failures.size)
    }

    @Test
    fun `a fetcher writing nothing is a failure not a zero-byte sticker`() = runTest {
        val draft = repository.prepareDocument(docxWithLinks(), "links.docx").getOrThrow()

        val result = repository.downloadDraftOriginals(draft.id) { _, target ->
            target.writeBytes(ByteArray(0))
            null
        }

        val (replaced, failures) = result.getOrThrow()
        assertEquals(0, replaced)
        assertEquals("空内容也算失败", 2, failures.size)
        assertTrue("草稿原图不能被清空", draft.items.all { it.file.length() > 0 })
    }

    @Test
    fun `a draft without any link says so instead of pretending success`() = runTest {
        val document = "<w:body>" + img("rId1") + txt("（没有链接）") + "</w:body>"
        val file = File(workDir, "nolink.docx")
        ZipOutputStream(FileOutputStream(file), Charsets.UTF_8).use { zos ->
            zos.putNextEntry(ZipEntry("word/document.xml"))
            zos.write(document.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("word/_rels/document.xml.rels"))
            zos.write("<Relationships><Relationship Id=\"rId1\" Type=\"image\" Target=\"media/image1.png\"/></Relationships>".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("word/media/image1.png"))
            zos.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3))
            zos.closeEntry()
        }
        val draft = repository.prepareDocument(Uri.fromFile(file), "nolink.docx").getOrThrow()

        val result = repository.downloadDraftOriginals(draft.id) { _, _ -> null }

        assertTrue("没有可用链接要如实失败: $result", result is Result.Error)
        assertTrue((result as Result.Error).message.contains("原图链接"))
    }

    @Test
    fun `only the checked items get fetched`() = runTest {
        val draft = repository.prepareDocument(docxWithLinks(), "links.docx").getOrThrow()
        val first = draft.items.first { it.sourceUrl != null }
        var asked = 0

        val result = repository.downloadDraftOriginals(draft.id, setOf(first.token)) { _, target ->
            asked++
            target.writeBytes(pngBytes(3))
            null
        }

        assertEquals("只取勾中的那一张", 1, asked)
        assertEquals(1, result.getOrThrow().first)
    }
}
