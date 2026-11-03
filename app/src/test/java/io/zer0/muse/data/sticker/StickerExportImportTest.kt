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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 整包导出与再导入（表情包重做 P4 收尾）。
 *
 * 测的是**闭环**而不是两个函数：导出时写的 `captions.json`
 * 如果导入侧不认，配文就只在"这一次"存在过 —— 换设备、重装、备份还原之后全丢。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [33])
class StickerExportImportTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val repository = StickerLibraryRepository(context)
    private lateinit var workDir: File

    @Before
    fun setUp() = runTest {
        repository.clearAll()
        workDir = kotlin.io.path.createTempDirectory("muse-export").toFile()
    }

    @After
    fun tearDown() = runTest {
        repository.clearAll()
        workDir.deleteRecursively()
    }

    private suspend fun seedThreeStickers() {
        val draft = repository.prepareDocument(
            Uri.fromFile(StickerTestDocs.threeImageDocx(workDir)),
            "pack.docx",
        ).getOrThrow()
        repository.commitDraft(draft.id).getOrThrow()
    }

    @Test
    fun `export writes every image plus a captions sidecar`() = runTest {
        seedThreeStickers()
        val out = ByteArrayOutputStream()

        val count = repository.exportCategoryZip("呆猫八条", out).getOrThrow()

        assertEquals(3, count)
        val entries = readZip(out.toByteArray())
        val images = entries.keys.filter { it != "captions.json" }
        assertEquals("三张图", 3, images.size)
        assertTrue("图片要按 分类/文件名 存放: $images", images.all { it.startsWith("呆猫八条/") })
        assertTrue("要带配文 sidecar: ${entries.keys}", entries.keys.contains("captions.json"))
        val sidecar = entries["captions.json"]!!.toString(Charsets.UTF_8)
        assertTrue("配文要写进 sidecar: $sidecar", sidecar.contains("小猫歪头"))
    }

    @Test
    fun `a re-imported export keeps its captions`() = runTest {
        seedThreeStickers()
        val out = ByteArrayOutputStream()
        repository.exportCategoryZip("呆猫八条", out).getOrThrow()
        val zipFile = File(workDir, "pack.zip").apply { writeBytes(out.toByteArray()) }
        repository.clearAll()

        val result = repository.importZip(Uri.fromFile(zipFile))

        assertTrue("再导入应成功: $result", result.isSuccess)
        val items = repository.listStickers()
        assertEquals(3, items.size)
        assertEquals("分类名跟着导出走", "呆猫八条", items.first().category)
        assertTrue(
            "配文必须回来: ${items.map { it.caption }}",
            items.mapNotNull { it.caption }.any { it.contains("小猫歪头") },
        )
        assertTrue("文件要真的落盘", items.all { File(context.filesDir, it.relativePath).isFile })
    }

    @Test
    fun `exporting an empty pack says so instead of writing a broken zip`() = runTest {
        val out = ByteArrayOutputStream()

        val result = repository.exportCategoryZip("没这个包", out)

        assertTrue("空包要如实失败: $result", result is Result.Error)
        assertEquals("失败时不该写出半个文件", 0, out.size())
    }

    @Test
    fun `a zip without a sidecar imports exactly as before`() = runTest {
        // 别人做的普通素材包不该因为多了 sidecar 逻辑而变化
        val zip = File(workDir, "plain.zip")
        ZipOutputStream(FileOutputStream(zip), Charsets.UTF_8).use { zos ->
            zos.putNextEntry(ZipEntry("日常/哈哈.png"))
            zos.write(StickerTestDocs.fakePng(5))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("notes.txt"))
            zos.write("not a sticker".toByteArray())
            zos.closeEntry()
        }

        assertTrue(repository.importZip(Uri.fromFile(zip)).isSuccess)

        val items = repository.listStickers()
        assertEquals(1, items.size)
        assertEquals("日常", items.first().category)
        assertEquals("没有 sidecar 就没有配文", null, items.first().caption)
    }

    private fun readZip(bytes: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(java.io.ByteArrayInputStream(bytes)).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) out[entry.name] = zis.readBytes()
                entry = zis.nextEntry
            }
        }
        return out
    }
}
