package io.zer0.muse.data.sticker

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.zer0.common.Result
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 表情包管理操作（表情包重做 P4）。
 *
 * 这些操作同时改清单与文件系统，所以每条断言都**两边都看**：
 * "清单说挪走了、文件还在原处"正是这类改动最容易留下的鬼状态，
 * 用户看到的是图在两个包里都出现、或者点开是空白。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [33])
class StickerLibraryManagementTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val repository = StickerLibraryRepository(context)
    private lateinit var workDir: File

    private val stickersRoot get() = File(context.filesDir, "stickers")

    @Before
    fun setUp() = runTest {
        repository.clearAll()
        workDir = kotlin.io.path.createTempDirectory("muse-sticker-mgmt").toFile()
    }

    @After
    fun tearDown() = runTest {
        repository.clearAll()
        workDir.deleteRecursively()
    }

    /**
     * 一张"图片"。
     *
     * 头部用真的 PNG 魔数：生产代码现在按字节判类型（文件名在部分入口拿不到），
     * 夹具再写一堆随机字节就会被正确地挡掉 —— 那不是我们要测的东西。
     * 后面的负载故意不是可解码的 PNG：尺寸解不出时应按"中性方图"收下，这条也要被覆盖。
     */
    private fun imageFile(name: String, seed: Int): File =
        File(workDir, name).apply {
            val magic = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
            writeBytes(magic + ByteArray(128) { (it + seed).toByte() })
        }

    /** 往某个包里放两张图；断言"批量添加本身是好的"，后面的用例才有干净起点。 */
    private suspend fun seedPack(category: String) {
        val result = repository.importImages(
            listOf(Uri.fromFile(imageFile("a.png", 1)), Uri.fromFile(imageFile("b.png", 2))),
            category,
        )
        assertTrue("批量添加应成功: $result", result.isSuccess)
        assertEquals(2, result.getOrNull())
    }

    @Test
    fun `batch add puts files under the chosen pack`() = runTest {
        seedPack("猫猫")

        val items = repository.listStickers()
        assertEquals(2, items.size)
        items.forEach { item ->
            assertEquals("猫猫", item.category)
            assertTrue("文件要真的落盘: ${item.relativePath}", File(context.filesDir, item.relativePath).isFile)
        }
    }

    @Test
    fun `non-images are skipped instead of aborting the batch`() = runTest {
        val txt = File(workDir, "notes.txt").apply { writeText("hello") }

        val result = repository.importImages(listOf(Uri.fromFile(txt), Uri.fromFile(imageFile("c.png", 3))), "杂项")

        assertTrue(result.isSuccess)
        assertEquals("只有图片被收下", 1, repository.listStickers().size)
    }

    @Test
    fun `a batch with nothing usable says so rather than importing zero`() = runTest {
        val txt = File(workDir, "notes.txt").apply { writeText("hello") }

        val result = repository.importImages(listOf(Uri.fromFile(txt)), "杂项")

        assertTrue("全被跳过时要失败并说明: $result", result is Result.Error)
        assertTrue(
            "原因要写清跳过了几个",
            (result as Result.Error).message.contains("跳过"),
        )
        assertEquals(0, repository.listStickers().size)
    }

    @Test
    fun `renaming a pack moves the files and leaves no copy behind`() = runTest {
        seedPack("猫猫")

        val result = repository.renameCategory("猫猫", "柴犬")

        assertTrue("改名应成功: $result", result.isSuccess)
        val items = repository.listStickers("柴犬")
        assertEquals(2, items.size)
        items.forEach { assertTrue("新位置要有文件", File(context.filesDir, it.relativePath).isFile) }
        assertFalse("旧目录应该已经不在了", File(stickersRoot, "猫猫").exists())
        assertEquals(0, repository.listStickers("猫猫").size)
    }

    @Test
    fun `renaming onto an existing pack name is refused not silently merged`() = runTest {
        seedPack("猫猫")
        repository.importImages(listOf(Uri.fromFile(imageFile("d.png", 4))), "柴犬")

        val result = repository.renameCategory("猫猫", "柴犬")

        assertTrue("撞名要拒绝: $result", result is Result.Error)
        assertEquals("两个包都该还在", 2, repository.listCategories().size)
        assertEquals(2, repository.listStickers("猫猫").size)
    }

    @Test
    fun `moving one sticker rewrites both manifest and file location`() = runTest {
        seedPack("猫猫")
        val moving = repository.listStickers("猫猫").first()

        assertTrue(repository.moveSticker(moving.id, "备用"))

        val moved = repository.listStickers().first { it.id == moving.id }
        assertEquals("备用", moved.category)
        assertTrue("新位置要有文件", File(context.filesDir, moved.relativePath).isFile)
        assertFalse("旧位置不能再有文件", File(stickersRoot, "猫猫/" + moving.fileName).exists())
        assertEquals("旧包还剩一张", 1, repository.listStickers("猫猫").size)
    }

    @Test
    fun `moving into the same pack is refused`() = runTest {
        seedPack("猫猫")
        val item = repository.listStickers("猫猫").first()

        assertFalse(repository.moveSticker(item.id, "猫猫"))
        assertEquals(2, repository.listStickers("猫猫").size)
    }

    @Test
    fun `caption is trimmed capped and can be cleared`() = runTest {
        seedPack("猫猫")
        val item = repository.listStickers("猫猫").first()
        // 局部 suspend 函数不被允许，用挂起 lambda 读当前配文
        val captionOf: suspend () -> String? = {
            repository.listStickers().first { it.id == item.id }.caption
        }

        assertTrue(repository.setCaption(item.id, "  小猫歪头  "))
        assertEquals("小猫歪头", captionOf())

        assertTrue(repository.setCaption(item.id, "字".repeat(500)))
        assertEquals("配文有上限", 200, captionOf()!!.length)

        assertTrue(repository.setCaption(item.id, "   "))
        assertNull("全是空白等于没有配文", captionOf())
    }

    @Test
    fun `unknown ids are reported instead of pretending success`() = runTest {
        seedPack("猫猫")

        assertFalse(repository.moveSticker("no-such-id", "备用"))
        assertFalse(repository.setCaption("no-such-id", "x"))
        val renamed = repository.renameCategory("没有这个包", "别的")
        assertTrue("不存在的包要直说: $renamed", renamed is Result.Error)
        assertTrue(
            (renamed as Result.Error).message.contains("没有找到"),
        )
    }
}
