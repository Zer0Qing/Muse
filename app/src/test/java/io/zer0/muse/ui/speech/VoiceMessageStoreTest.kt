package io.zer0.muse.ui.speech

import kotlinx.coroutines.runBlocking
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
 * [VoiceMessageStore] 行为测试（纯 JVM，不依赖 Android 音频栈）。
 *
 * 时长探测通过 [VoiceDurationProbe] 注入固定值，因此不触碰 MediaMetadataRetriever。
 * 覆盖：落盘/解析往返、同键复用、源文件缺失降级、越界引用拒绝、删除单条、
 * 孤儿清理（含落盘中断留下的 .tmp）。
 */
class VoiceMessageStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store(probe: VoiceDurationProbe = VoiceDurationProbe { 1234L }): VoiceMessageStore =
        VoiceMessageStore(storageDir = File(tmp.root, "voice_messages"), durationProbe = probe)

    private fun tempAudio(name: String = "chunk.wav", content: String = "RIFF-fake-wav"): File =
        File(tmp.newFolder(), name).apply { writeText(content) }

    @Test
    fun persistCopiesIntoStoreAndResolveReturnsSameContent() = runBlocking {
        val store = store()
        val source = tempAudio(content = "audio-bytes")

        val uri = store.persist(source, "msg-1")

        assertNotNull("落盘应成功", uri)
        assertTrue("引用应是 file:// 形式，实际 $uri", uri!!.startsWith("file://"))
        val stored = store.resolve(uri)
        assertNotNull("引用应能解析回文件", stored)
        assertEquals("内容应与临时文件一致", "audio-bytes", stored!!.readText())
        assertEquals("存档应在 store 目录内", storeDir(store), stored.parentFile)
        assertTrue("源文件保留（清理由调用方负责）", source.exists())
    }

    @Test
    fun persistSameKeyReusesSingleFile() = runBlocking {
        val store = store()

        val first = store.persist(tempAudio(content = "v1"), "same-text")
        val second = store.persist(tempAudio(content = "v2"), "same-text")

        assertEquals("同键应命中同一存档引用", first, second)
        assertEquals("目录里只应有一个文件", 1, storeDir(store).listFiles()!!.size)
        assertEquals("重复合成应覆盖为最新内容", "v2", store.resolve(second!!)!!.readText())
    }

    @Test
    fun persistReturnsNullWhenSourceMissingOrEmpty() = runBlocking {
        val store = store()

        assertNull("源文件不存在应降级为 null", store.persist(File(tmp.root, "nope.wav"), "k1"))
        assertNull("空文件应降级为 null", store.persist(tempAudio(content = ""), "k2"))
        assertFalse("源文件不可用时不应建出存档目录", storeDir(store).exists())
    }

    @Test
    fun resolveRejectsMissingForeignAndBlankUris() = runBlocking {
        val store = store()
        val uri = store.persist(tempAudio(), "msg-2")!!

        assertEquals("空串不可解析", null, store.resolve(""))
        assertEquals("纯空白不可解析", null, store.resolve("   "))

        val outside = File(tmp.newFolder(), "outside.wav").apply { writeText("x") }
        assertNull("store 目录外的路径不应被当成语音", store.resolve("file://" + outside.absolutePath))

        store.delete(uri)
        assertNull("文件已删除时应返回 null", store.resolve(uri))
    }

    @Test
    fun deleteRemovesOnlyTargetFile() = runBlocking {
        val store = store()
        val keep = store.persist(tempAudio(content = "keep"), "keep-key")!!
        val drop = store.persist(tempAudio(content = "drop"), "drop-key")!!

        store.delete(drop)

        assertNull("目标文件应被删除", store.resolve(drop))
        assertNotNull("其他存档不受影响", store.resolve(keep))
        store.delete(drop) // 再删一次不应抛异常
    }

    @Test
    fun cleanupOrphansKeepsReferencedAndRemovesTempFiles() = runBlocking {
        val store = store()
        val keep = store.persist(tempAudio(content = "keep"), "keep-key")!!
        val orphan = store.persist(tempAudio(content = "orphan"), "orphan-key")!!
        File(storeDir(store), "broken.wav.tmp").writeText("half-written")

        val removed = store.cleanupOrphans(listOf(keep))

        assertEquals("应删掉 1 个孤儿存档 + 1 个 .tmp", 2, removed)
        assertNotNull("被引用的存档必须保留", store.resolve(keep))
        assertNull("无引用的存档应被清理", store.resolve(orphan))
        assertEquals("目录里应只剩被引用的那一个", 1, storeDir(store).listFiles()!!.size)
    }

    @Test
    fun durationMsUsesProbeAndDegradesToZero() = runBlocking {
        val store = store(probe = VoiceDurationProbe { file -> file.length() * 10 })
        val uri = store.persist(tempAudio(content = "0123456789"), "duration-key")!!

        assertEquals("时长应来自探测器", 100L, store.durationMs(uri))
        assertEquals("引用不可解析时时长为 0", 0L, store.durationMs("file:///nowhere/missing.wav"))
    }

    @Test
    fun probeFailureDegradesToZero() {
        val store = store(probe = VoiceDurationProbe { error("decoder exploded") })

        assertEquals("探测器抛异常时时长应为 0", 0L, store.probeDurationMs(tempAudio()))
    }

    /** store 目录（构造参数为 internal，测试直接读）。 */
    private fun storeDir(store: VoiceMessageStore): File = store.storageDir
}
