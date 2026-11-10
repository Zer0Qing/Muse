package io.zer0.muse.data.miniphone

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.data.sticker.StickerLibraryRepository
import io.zer0.muse.miniphone.api.MpSticker
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 表情库的只读互通。
 *
 * 要钉住的是那条容易糊过去的边界：库里的记录还在、文件却已经不在磁盘上时，
 * 界面拿到的应该少一条，而不是一个点不开的 uri。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [33])
class MiniPhoneLibraryBridgeTest {

    private val context: android.content.Context = ApplicationProvider.getApplicationContext()

    private companion object {
        val PNG_MAGIC: ByteArray = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    }

    @Test
    fun `表情投影指向真实落盘文件`() = runBlocking {
        val repository = StickerLibraryRepository(context)
        importOne(repository, "bridge-sticker")
        val item = repository.listStickers().single()
        val projected: List<MpSticker> = MiniPhoneLibraryBridge(repository).listStickers()
        assertEquals(1, projected.size)
        // 不去 Uri→path 还原：Windows 宿主上 Uri.fromFile 会把 `C:\...` 当 authority 编码，
        // .path 恒为空（Android 上路径以 / 开头，不存在这个问题）。
        // 改成断言「投影出的 uri 恰好是那张真实落盘文件的平台 uri」，同样能抓住指错文件。
        val onDisk = repository.fileOf(item)
        assertTrue("文件必须真的落盘: ${onDisk.absolutePath}", onDisk.isFile)
        assertEquals(Uri.fromFile(onDisk).toString(), projected.single().uri)
    }

    @Test
    fun `文件被删掉的表情不再投影出来`() = runBlocking {
        val repository = StickerLibraryRepository(context)
        importOne(repository, "bridge-gone")
        val item = repository.listStickers().single()
        assertTrue(repository.fileOf(item).delete())
        assertEquals(0, MiniPhoneLibraryBridge(repository).listStickers().size)
    }

    /** 造一张真图导进库里；导入本身失败或条数不对都直接判红。 */
    private suspend fun importOne(repository: StickerLibraryRepository, prefix: String) {
        val source = File.createTempFile(prefix, ".png").apply {
            writeBytes(PNG_MAGIC + ByteArray(128) { it.toByte() })
        }
        val imported = repository.importImages(listOf(Uri.fromFile(source)), "互通测试")
        assertTrue("导入本身要成功: $imported", imported.isSuccess)
        assertEquals("导入条数应为 1，实际结果=$imported", 1, imported.getOrNull())
    }
}
