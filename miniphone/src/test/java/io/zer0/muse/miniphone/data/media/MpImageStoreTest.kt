package io.zer0.muse.miniphone.data.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import kotlinx.coroutines.runBlocking
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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * 图片落盘:命名、去重、跳过读不到的源、超限重编码。
 *
 * 输入流由测试注入:真机上是 ContentResolver,这里换成一段字节,以便钉住「同一张图
 * 选两次只留一份」与「一张失败不影响别的」这两件真正会出问题的事。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MpImageStoreTest {

    private lateinit var directory: File

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("mp-image-store").toFile()
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun sameImagePickedTwiceKeepsOneFileAndTheSamePath() = runBlocking {
        val bytes = pngBytes(width = 4, height = 4)
        val store = storeOf(bytes)

        val first = store.import(pickerUri("1"))
        val second = store.import(pickerUri("2"))

        assertNotNull(first)
        assertEquals("同一份内容应该得到同一个路径", first, second)
        val stored = directory.listFiles().orEmpty()
        assertEquals("只应存一份文件", 1, stored.size)
        assertTrue("文件名是内容摘要前 16 位 + 真实后缀", stored.single().name.matches(NAME_PATTERN))
    }

    @Test
    fun unreadableSourceIsSkippedInsteadOfFailing() = runBlocking {
        val store = MpImageStore(
            directory = directory,
            openStream = { throw IOException("原件已被删除") },
        )

        assertNull(store.import(pickerUri("missing")))
        assertEquals(0, directory.listFiles().orEmpty().size)
    }

    @Test
    fun emptySourceIsSkipped() = runBlocking {
        val store = MpImageStore(directory = directory, openStream = { ByteArrayInputStream(ByteArray(0)) })

        assertNull(store.import(pickerUri("empty")))
        assertEquals(0, directory.listFiles().orEmpty().size)
    }

    @Test
    fun oversizedImageIsReencodedDownToTheMaxEdge() = runBlocking {
        val store = storeOf(
            bytes = pngBytes(width = 2_000, height = 800),
            compressMinBytes = 0,
        )

        val path = requireNotNull(store.import(pickerUri("big")))

        assertTrue("重编码后是 JPEG", path.endsWith(".jpg"))
        val stored = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, stored)
        assertEquals(1_600, maxOf(stored.outWidth, stored.outHeight))
        assertEquals("宽高比保持不变", 800 * 1_600 / 2_000, minOf(stored.outWidth, stored.outHeight))
    }

    @Test
    fun sampleSizeStaysAtPowersOfTwoAndAboveHalfTheTarget() {
        assertEquals(1, sampleSizeFor(longEdge = 1_600, maxEdge = 1_600))
        assertEquals(2, sampleSizeFor(longEdge = 6_000, maxEdge = 1_600))
        assertEquals(8, sampleSizeFor(longEdge = 12_800, maxEdge = 1_600))
        assertTrue(sampleSizeFor(longEdge = 12_800, maxEdge = 1_600) * 1_600 <= 12_800)
    }

    private fun storeOf(bytes: ByteArray, compressMinBytes: Long = DEFAULT_COMPRESS_MIN_BYTES): MpImageStore = MpImageStore(
        directory = directory,
        openStream = { ByteArrayInputStream(bytes) },
        compressMinBytes = compressMinBytes,
    )

    private fun pickerUri(id: String): Uri = Uri.parse("content://picker/$id")

    /** 真 PNG:让尺寸读取与重编码走真实解码路径。 */
    private fun pngBytes(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
    }

    private companion object {
        /** 16 个十六进制字符 + 后缀。 */
        val NAME_PATTERN = Regex("[0-9a-f]{16}\\.(png|jpg|gif|img)")

        /** 与 MpImageStore 的默认阈值一致:测试里显式写出来,改默认值时这条会提醒。 */
        const val DEFAULT_COMPRESS_MIN_BYTES: Long = 8L * 1024 * 1024
    }
}
