package io.zer0.muse.miniphone.data.voice

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 录音器的边界:采集起不来时不落盘、采完之后交出的是一段完整 WAV、临时文件最后一定被删。
 *
 * 采集器全部换成假实现 —— 单测不开麦克风、不申请权限,只验「字节怎么进文件、怎么出来」。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MpVoiceRecorderTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    @Test
    fun `拿不到采集器时 start 返回 false 且不落任何文件`() = runTest {
        val cache = tempDir.newFolder(CACHE_DIR)

        val recorder = MpVoiceRecorder(
            directory = cache,
            factory = { _, _, _ -> null },
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        assertFalse("初始化失败必须返回 false", recorder.start())
        assertTrue("失败路径不该留下临时文件", cache.listFiles().isNullOrEmpty())
    }

    @Test
    fun `起采失败时 start 返回 false 且清掉临时文件`() = runTest {
        val cache = tempDir.newFolder(CACHE_DIR)
        val record = FakeAudioRecord(startError = IllegalStateException("麦克风被占用"))

        val recorder = MpVoiceRecorder(
            directory = cache,
            factory = { _, _, _ -> record },
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        assertFalse(recorder.start())
        assertTrue("起采失败要释放采集器", record.released)
        assertTrue("起采失败不该留下临时文件", cache.listFiles().isNullOrEmpty())
    }

    @Test
    fun `停止后交出带 WAV 头的字节并删掉临时文件`() = runTest {
        val cache = tempDir.newFolder(CACHE_DIR)
        val pcm = ByteArray(PCM_BYTES) { SAMPLE_BYTE }
        val record = FakeAudioRecord(chunks = listOf(pcm))

        val recorder = MpVoiceRecorder(
            directory = cache,
            factory = { _, _, _ -> record },
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        assertTrue(recorder.start())
        val wav = recorder.stop()

        assertNotNull("采到数据就该有音频", wav)
        assertEquals("WAV = 44 字节头 + PCM", 44 + PCM_BYTES, wav!!.size)
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals(PCM_BYTES, leInt(wav, 40))
        assertEquals(MpVoiceRecorder.SAMPLE_RATE, leInt(wav, 24))
        assertEquals("首个采样原样落到头部之后", SAMPLE_BYTE, wav[44])
        assertTrue("交付后临时文件必须删掉", cache.listFiles().isNullOrEmpty())
        assertTrue("停止后要释放采集器", record.released)
    }

    @Test
    fun `一个字节都没采到时 stop 返回 null 且不残留文件`() = runTest {
        val cache = tempDir.newFolder(CACHE_DIR)
        val record = FakeAudioRecord(chunks = emptyList())

        val recorder = MpVoiceRecorder(
            directory = cache,
            factory = { _, _, _ -> record },
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        assertTrue(recorder.start())

        assertNull("没有音频就没有结果", recorder.stop())
        assertTrue(cache.listFiles().isNullOrEmpty())
    }

    @Test
    fun `取消后停采并删掉临时文件`() = runTest {
        val cache = tempDir.newFolder(CACHE_DIR)
        val record = FakeAudioRecord(chunks = listOf(ByteArray(PCM_BYTES)))

        val recorder = MpVoiceRecorder(
            directory = cache,
            factory = { _, _, _ -> record },
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        assertTrue(recorder.start())
        recorder.cancel()

        assertTrue("取消要停采", record.stopped)
        assertTrue("取消要释放采集器", record.released)
        assertTrue("取消要删掉临时文件", cache.listFiles().isNullOrEmpty())
        assertNull("取消过就没有音频可交", recorder.stop())
    }

    @Test
    fun `清理只删超过一小时的本模块前缀文件`() {
        val cache = tempDir.newFolder(CACHE_DIR)
        val now = 1_700_000_000_000L
        val fresh = cache.recordingFile("mp-voice-fresh.pcm", touchedAt = now - 1_000L)
        val stale = cache.recordingFile("mp-voice-stale.pcm", touchedAt = now - STALE_MS - 1_000L)
        val foreign = cache.recordingFile("notes.txt", touchedAt = now - STALE_MS * 3)

        val removed = cleanupStaleRecordings(cache, now = now)

        assertEquals("只该删那一个旧的本前缀文件", 1, removed)
        assertTrue("刚改过的录音不能删", fresh.exists())
        assertTrue("不是本模块前缀的文件不能删", foreign.exists())
        assertFalse("超过一小时的残留要删掉", stale.exists())
    }

    private fun File.recordingFile(name: String, touchedAt: Long): File = File(this, name).apply {
        writeBytes(ByteArray(4))
        setLastModified(touchedAt)
    }

    private fun leInt(bytes: ByteArray, offset: Int): Int = (bytes[offset].toInt() and 0xFF) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private companion object {
        const val CACHE_DIR: String = "cache"
        const val PCM_BYTES: Int = 3_200
        const val SAMPLE_BYTE: Byte = 0x2A
        const val STALE_MS: Long = 2L * 60L * 60L * 1_000L
    }
}

/**
 * 假采集器:按给定的块吐数据,吐完就报 -1(与 AudioRecord.read 的约定一致)。
 *
 * @param chunks 依次交给调用方的 PCM 块
 * @param startError 非空时起采直接抛,用来验失败路径
 */
private class FakeAudioRecord(
    private val chunks: List<ByteArray> = emptyList(),
    private val startError: Exception? = null,
) : MpAudioRecord {

    private var cursor: Int = 0

    var stopped: Boolean = false
        private set

    var released: Boolean = false
        private set

    override fun start() {
        startError?.let { throw it }
    }

    override fun read(buffer: ByteArray, offset: Int, size: Int): Int {
        val chunk = chunks.getOrNull(cursor) ?: return -1
        cursor += 1
        val length = minOf(chunk.size, size)
        chunk.copyInto(buffer, offset, 0, length)
        return length
    }

    override fun stop() {
        stopped = true
    }

    override fun release() {
        released = true
    }
}
