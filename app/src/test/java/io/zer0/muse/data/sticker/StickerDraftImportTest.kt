package io.zer0.muse.data.sticker

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.zer0.common.Result
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
 * 走草稿的导入确认流程（表情包重做 P3）。
 *
 * 这一段是 PDF/文档导入的**必要环节**：切格与挑图准不准没法用数字判定，
 * 所以必须能在入库前勾掉。这里锁三件事：
 *  1. 只提交勾选的那些（不是全有或全无）；
 *  2. 取消之后库里一张都不该有，草稿目录也不该留；
 *  3. 老的"直接导入"入口结果不变（自动化/分享入口还在用它）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [33])
class StickerDraftImportTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val repository = StickerLibraryRepository(context)
    private lateinit var workDir: File

    @Before
    fun setUp() = runTest {
        repository.clearAll()
        workDir = kotlin.io.path.createTempDirectory("muse-draft-import").toFile()
    }

    @After
    fun tearDown() = runTest {
        repository.clearAll()
        workDir.deleteRecursively()
    }

    /** 三张图 + 各自配文的 docx。 */
    private fun writeDocx(): Uri {
        val document = "<w:document><w:body>" +
            "<w:p><w:r><w:t>测试包</w:t></w:r></w:p>" +
            imagePara("rId1") + textPara("第一张") +
            imagePara("rId2") + textPara("第二张") +
            imagePara("rId3") + textPara("第三张") +
            "</w:body></w:document>"
        fun png(seed: Int) =
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, seed.toByte(), (seed * 3).toByte(), (seed + 7).toByte())
        val file = File(workDir, "pack.docx")
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
            (1..3).forEach { i -> put("word/media/image$i.png", png(i)) }
        }
        return Uri.fromFile(file)
    }

    private fun imagePara(rid: String) = "<w:p><w:r><w:drawing><a:blip r:embed=\"" + rid + "\"/></w:drawing></w:r></w:p>"

    private fun textPara(text: String) = "<w:p><w:r><w:t>" + text + "</w:t></w:r></w:p>"

    @Test
    fun `prepare stages everything without touching the library`() = runTest {
        val draft = repository.prepareDocument(writeDocx(), "pack.docx").getOrThrow()

        assertEquals("解析出三张", 3, draft.items.size)
        assertEquals("分类取正文首段", "测试包", draft.category)
        assertTrue("配文要在", draft.items.map { it.caption }.contains("第二张"))
        assertEquals("此时库里应当一张都没有", 0, repository.listStickers().size)
        assertTrue("文件已在草稿目录里", draft.items.all { it.file.isFile })
    }

    @Test
    fun `commit keeps only the checked ones`() = runTest {
        val draft = repository.prepareDocument(writeDocx(), "pack.docx").getOrThrow()
        val kept = draft.items.take(2).map { it.token }.toSet()

        val result = repository.commitDraft(draft.id, kept)

        assertTrue(result.isSuccess)
        assertEquals(2, result.getOrNull())
        val items = repository.listStickers()
        assertEquals(2, items.size)
        assertTrue("入库的配文应该是被勾的那两张", items.map { it.caption }.containsAll(listOf("第一张", "第二张")))
        assertEquals("提交后草稿要清干净", null, repository.draftOf(draft.id))
    }

    @Test
    fun `cancel leaves nothing in the library`() = runTest {
        val draft = repository.prepareDocument(writeDocx(), "pack.docx").getOrThrow()

        repository.cancelDraft(draft.id)

        assertEquals(0, repository.listStickers().size)
        assertNull(repository.draftOf(draft.id))
    }

    @Test
    fun `committing nothing is refused instead of silently importing zero`() = runTest {
        val draft = repository.prepareDocument(writeDocx(), "pack.docx").getOrThrow()

        val result = repository.commitDraft(draft.id, setOf("no-such-token"))

        assertTrue("一张没选要失败: $result", result is Result.Error)
        assertTrue((result as Result.Error).message.contains("一张都没选"))
        assertEquals(0, repository.listStickers().size)
    }

    @Test
    fun `the direct import entry still imports everything as before`() = runTest {
        // 自动化与分享入口用的是 importUri：草稿化之后这条结果不能变
        val result = repository.importDocument(writeDocx(), "pack.docx")

        assertTrue(result.isSuccess)
        assertEquals(3, result.getOrNull())
        assertEquals(3, repository.listStickers().size)
        assertNotNull(repository.listStickers().first().category)
    }

    @Test
    fun `a draft id that no longer exists reports honestly`() = runTest {
        val result = repository.commitDraft("gone")

        assertTrue(result is Result.Error)
        assertTrue((result as Result.Error).message.contains("重新选取"))
    }

    @Test
    fun `the preview entry only takes documents and pdfs`() = runTest {
        // 压缩包与相册图是用户自己组织的，没有"自动挑错"问题 —— 直接导入，不进预览
        val zip = File(workDir, "packs.zip")
        java.util.zip.ZipOutputStream(java.io.FileOutputStream(zip), Charsets.UTF_8).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("猫猫/001.png"))
            zos.write(byteArrayOf(1, 2, 3, 4, 5))
            zos.closeEntry()
        }

        val result = repository.prepareUri(Uri.fromFile(zip))

        assertTrue("压缩包不该走预览: $result", result is Result.Error)
        assertTrue((result as Result.Error).message.contains("不需要预览"))
    }

    @Test
    fun `committing a draft leaves no staging directory behind`() = runTest {
        val draft = repository.prepareDocument(writeDocx(), "pack.docx").getOrThrow()

        repository.commitDraft(draft.id)

        val leftovers = File(context.filesDir, "stickers").listFiles()
            ?.filter { it.name.startsWith(".staging_") || it.name == ".drafts" }
            ?.filter { it.isDirectory && (it.listFiles()?.isEmpty() == false) }
            ?: emptyList()
        assertTrue("不该留下中间产物: ${'$'}{leftovers.map { it.name }}", leftovers.isEmpty())
    }
}
