package io.zer0.muse.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import io.zer0.muse.ui.speech.TtsSynthResult
import io.zer0.muse.ui.speech.VoiceDurationProbe
import io.zer0.muse.ui.speech.VoiceMessageArchiver
import io.zer0.muse.ui.speech.VoiceMessageStore
import io.zer0.muse.ui.speech.VoiceSynthesizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * v2.x: 助手发语音消息（`send_voice`）的工具层行为护栏。
 *
 * 合成端口与存档端口都用假实现注入 —— 单测里绝不真起 TTS 引擎（会拉起系统服务、
 * 还要等异步回调），只验工具自己的编排：投递什么结果、临时文件有没有删干净、
 * 失败时会不会白调 persist。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SendVoiceToolTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun tool(synth: VoiceSynthesizer, archive: VoiceMessageArchiver): MediaGenToolsImpl = MediaGenToolsImpl(
        context = context,
        settings = mockk(relaxed = true),
        imageService = mockk(relaxed = true),
        videoGenerationService = mockk(relaxed = true),
        voiceSynthesizer = synth,
        voiceMessageStore = archive,
    )

    @Test
    fun `合成成功时投递带 uri 与时长的 Success 并删掉临时文件`() = runBlocking {
        val synth = FakeSynthesizer()
        val archive = FakeArchive()
        val host = RecordingHost()
        val impl = tool(synth, archive).also { it.installHost(host) }

        val reply = impl.execSendVoice(mapOf("text" to "晚安，早点睡", "emotion" to "soft"))

        val success = host.results.single() as MediaGenResult.Success
        assertEquals(ARCHIVED_URI, success.voiceUri)
        assertEquals(ARCHIVE_DURATION_MS, success.voiceDurationMs)
        assertEquals("emotion 必须透传给合成端口", "soft", synth.lastEmotion)
        assertEquals("投递宿主要按语音类型开轮次", listOf(MediaGenKind.VOICE), host.kinds)
        assertFalse("临时文件没删干净", tempFileOf(synth).exists())
        assertFalse("返回给模型的文案不该带 uri: $reply", reply.contains("file://"))
        assertTrue("返回文案应说明已发出语音: $reply", reply.contains("语音"))
    }

    @Test
    fun `合成失败时投递 Failure 且不调 persist`() = runBlocking {
        val synth = FakeSynthesizer(TtsSynthResult.Failure("云端与系统都失败"))
        val archive = FakeArchive()
        val host = RecordingHost()
        val impl = tool(synth, archive).also { it.installHost(host) }

        val reply = impl.execSendVoice(mapOf("text" to "你好"))

        val failure = host.results.single() as MediaGenResult.Failure
        assertEquals("语音合成失败", failure.message)
        assertEquals("合成失败不该落盘", 0, archive.persistCalls)
        assertFalse("失败路径也要删临时文件", tempFileOf(synth).exists())
        assertTrue("工具结果应说明失败原因: $reply", reply.contains("语音合成失败"))
    }

    @Test
    fun `回落到系统音色时工具结果说明云端未生效及原因`() = runBlocking {
        val synth = FakeSynthesizer(TtsSynthResult.SystemFallback("API Key 为空"))
        val host = RecordingHost()
        val impl = tool(synth, FakeArchive()).also { it.installHost(host) }

        val reply = impl.execSendVoice(mapOf("text" to "晚安，早点睡"))

        assertTrue("回落到系统音色也仍然发出了语音: $reply", reply.contains("手机自带音色"))
        assertTrue("必须把云端未生效的原因讲清楚: $reply", reply.contains("API Key 为空"))
        assertTrue("回落也算投递成功", host.results.single() is MediaGenResult.Success)
    }

    @Test
    fun `云端合成成功时工具结果不提回落`() = runBlocking {
        val impl = tool(FakeSynthesizer(TtsSynthResult.Success), FakeArchive())
            .also { it.installHost(RecordingHost()) }

        val reply = impl.execSendVoice(mapOf("text" to "晚安，早点睡"))

        assertEquals("已发出语音消息", reply)
        assertFalse("云端正常时不能提系统音色: $reply", reply.contains("手机自带音色"))
    }

    @Test
    fun `persist 返回 null 时投递 Failure`() = runBlocking {
        val synth = FakeSynthesizer()
        val archive = FakeArchive(uri = null)
        val host = RecordingHost()
        val impl = tool(synth, archive).also { it.installHost(host) }

        val reply = impl.execSendVoice(mapOf("text" to "存档会失败的一句话"))

        assertEquals("语音文件保存失败", (host.results.single() as MediaGenResult.Failure).message)
        assertFalse("失败路径也要删临时文件", tempFileOf(synth).exists())
        assertTrue("工具结果应说明失败原因: $reply", reply.contains("语音文件保存失败"))
    }

    @Test
    fun `时长探测返回 0 时仍然投递成功`() = runBlocking {
        val host = RecordingHost()
        val impl = tool(FakeSynthesizer(), FakeArchive(durationMs = 0L)).also { it.installHost(host) }

        impl.execSendVoice(mapOf("text" to "探测不出时长"))

        val success = host.results.single() as MediaGenResult.Success
        assertEquals("时长未知也要照常发出", ARCHIVED_URI, success.voiceUri)
        assertEquals(0L, success.voiceDurationMs)
    }

    @Test
    fun `协程取消时投递 Cancelled 并删掉临时文件`() = runBlocking {
        val synth = FakeSynthesizer(error = CancellationException("用户停止生成"))
        val host = RecordingHost()
        val impl = tool(synth, FakeArchive()).also { it.installHost(host) }

        val thrown = runCatching { impl.execSendVoice(mapOf("text" to "说到一半被取消")) }.exceptionOrNull()

        assertTrue("取消要原样抛出: $thrown", thrown is CancellationException)
        assertTrue("取消要投递 Cancelled", host.results.single() is MediaGenResult.Cancelled)
        assertFalse("取消路径也要删临时文件", tempFileOf(synth).exists())
    }

    @Test
    fun `同一 text 与 emotion 重复发送只落一份音频`() = runBlocking {
        val synth = FakeSynthesizer()
        val store = realStore()
        val host = RecordingHost()
        val impl = tool(synth, store).also { it.installHost(host) }

        impl.execSendVoice(mapOf("text" to "同一句话", "emotion" to "calm"))
        impl.execSendVoice(mapOf("text" to "同一句话", "emotion" to "calm"))

        assertEquals("同文本同情绪应命中同一存档文件", 1, storedFiles(store).size)
        val uris = host.results.filterIsInstance<MediaGenResult.Success>().map { it.voiceUri }.distinct()
        assertEquals("两次投递应引用同一份音频", 1, uris.size)
    }

    @Test
    fun `不同 emotion 落成不同存档`() = runBlocking {
        val store = realStore()
        val impl = tool(FakeSynthesizer(), store).also { it.installHost(RecordingHost()) }

        impl.execSendVoice(mapOf("text" to "同一句话", "emotion" to "calm"))
        impl.execSendVoice(mapOf("text" to "同一句话", "emotion" to "sad"))

        assertEquals("情绪进 key：不同情绪不能互相覆盖", 2, storedFiles(store).size)
    }

    @Test
    fun `代际失效时不往任何消息写语音并删掉存档`() = runBlocking {
        val archive = FakeArchive()
        val host = RecordingHost(active = false)
        val impl = tool(FakeSynthesizer(), archive).also { it.installHost(host) }

        val reply = impl.execSendVoice(mapOf("text" to "上一轮已经作废"))

        assertTrue("失效轮次不得投递结果", host.results.isEmpty())
        assertEquals("没投递出去的存档不该留着", listOf(ARCHIVED_URI), archive.deleted)
        assertTrue(reply, reply.contains("未发出"))
    }

    @Test
    fun `缺 text 时直接报参数错误`() = runBlocking {
        val synth = FakeSynthesizer()
        val host = RecordingHost()
        val impl = tool(synth, FakeArchive()).also { it.installHost(host) }

        val reply = impl.execSendVoice(emptyMap())

        assertTrue(reply, reply.contains("text"))
        assertEquals("参数缺失不该触发合成", 0, synth.calls)
        assertTrue(host.results.isEmpty())
    }

    // ── 逐段朗读：中文闸门 / 独立槽位 / 每回合条数上限 ─────────────────

    @Test
    fun `正文没有汉字时不调合成并说明原因`() = runBlocking {
        val synth = FakeSynthesizer()
        val host = RecordingHost()
        val impl = tool(synth, FakeArchive()).also { it.installHost(host) }

        val reply = impl.execSendVoice(mapOf("text" to "Good night, sleep well. See you tomorrow."), TURN_KEY)

        assertEquals("没有中文就不该花 TTS 额度", 0, synth.calls)
        assertTrue("也不该开投递槽位", host.kinds.isEmpty())
        assertTrue("工具结果要说清没生成: $reply", reply.contains("没有中文"))
    }

    @Test
    fun `中英混排按含中文合成`() = runBlocking {
        val synth = FakeSynthesizer()
        val host = RecordingHost()
        val impl = tool(synth, FakeArchive()).also { it.installHost(host) }

        impl.execSendVoice(mapOf("text" to "晚安，sleep well."), TURN_KEY)

        assertEquals("只要混了一个汉字就照常合成", 1, synth.calls)
        assertTrue("混排文本要投递成功", host.results.single() is MediaGenResult.Success)
    }

    @Test
    fun `同一回合连发三条语音各自投递不互相覆盖`() = runBlocking {
        val synth = FakeSynthesizer()
        val host = RecordingHost()
        // 存档端口每次给不同引用：若多条语音共享槽位/同一个结果载体，三条会变成同一条
        val archive = FakeArchive(uriFactory = { n -> "file:///data/voice_messages/v$n.wav" })
        val impl = tool(synth, archive).also { it.installHost(host) }

        repeat(VOICE_SEGMENTS) { index ->
            impl.execSendVoice(mapOf("text" to "第${index + 1}段台词"), TURN_KEY)
        }

        assertEquals("三段都要走合成", VOICE_SEGMENTS, synth.calls)
        assertEquals("每条语音自己开一次槽位", List(VOICE_SEGMENTS) { MediaGenKind.VOICE }, host.kinds)
        val uris = host.results.filterIsInstance<MediaGenResult.Success>().map { it.voiceUri }
        assertEquals("一条语音一次投递，不能少", VOICE_SEGMENTS, uris.size)
        assertEquals("每条引用的音频都必须不同，不能互相覆盖", VOICE_SEGMENTS, uris.distinct().size)
    }

    @Test
    fun `单回合第 6 条语音被上限挡住且不调合成`() = runBlocking {
        val synth = FakeSynthesizer()
        val host = RecordingHost()
        val impl = tool(synth, FakeArchive(uriFactory = { n -> "file:///data/voice_messages/v$n.wav" }))
            .also { it.installHost(host) }

        val replies = (1..TURN_LIMIT).map { index ->
            impl.execSendVoice(mapOf("text" to "第${index}段台词"), TURN_KEY)
        }
        val over = impl.execSendVoice(mapOf("text" to "第${TURN_LIMIT + 1}段台词"), TURN_KEY)

        assertTrue("前 $TURN_LIMIT 条都要发出去: $replies", replies.all { it.contains("已发出语音") })
        assertTrue("超限那条要说明原因: $over", over.contains("上限"))
        assertEquals("合成调用停在 $TURN_LIMIT 次", TURN_LIMIT, synth.calls)
        assertEquals("超限那条不投递", TURN_LIMIT, host.results.size)

        // 额度挂在回合上：换了回合标识就重新计数
        impl.execSendVoice(mapOf("text" to "下一轮的第一段台词"), "turn-2")
        assertEquals("上限只限当前回合", TURN_LIMIT + 1, synth.calls)
    }

    @Test
    fun `find_tools 能用发语音这类关键词搜到本工具`() {
        // 可发现性：工具发出去了还得能被模型找到。用真实注册台账跑检索，
        // 不手造 ToolDef —— 否则描述被改掉也不会报错。
        val registry = ToolRegistry(context)
        MediaGenToolsRegistrar(registry, tool(FakeSynthesizer(), FakeArchive()))
        val defs = registry.listTools()

        listOf("发语音", "语音", "voice", "朗读").forEach { query ->
            val names = FindToolsTool.search(query, defs).map { it.name }
            assertTrue("查询「$query」应能搜到 send_voice，实际: $names", "send_voice" in names)
        }
    }

    // ── 夹具 ────────────────────────────────────────────────────────────

    private fun tempFileOf(synth: FakeSynthesizer): File =
        requireNotNull(synth.lastFile) { "合成端口未被调用，拿不到临时文件路径" }

    private fun realStore(): VoiceMessageStore =
        VoiceMessageStore(File(context.cacheDir, "voice-store-${System.nanoTime()}"), VoiceDurationProbe { 800L })

    private fun storedFiles(store: VoiceMessageStore): List<File> =
        store.storageDir.listFiles()?.filter { it.isFile }.orEmpty()

    /** 合成端口假实现：可选结果/抛异常，成功时写点字节让 persist 有东西可搬。 */
    private class FakeSynthesizer(
        private val outcome: TtsSynthResult = TtsSynthResult.Success,
        private val error: Throwable? = null,
    ) : VoiceSynthesizer {
        var lastFile: File? = null
        var lastEmotion: String? = null
        var calls = 0

        override suspend fun synthesizeTo(text: String, outputFile: File, emotionOverride: String?): TtsSynthResult {
            calls++
            lastFile = outputFile
            lastEmotion = emotionOverride
            error?.let { throw it }
            // 只有彻底失败才无音频；回落系统音色是有声音的
            if (outcome !is TtsSynthResult.Failure) outputFile.writeBytes(ByteArray(BYTES))
            return outcome
        }
    }

    /** 存档端口假实现：记录 persist 调用次数与删除的引用。 */
    private class FakeArchive(
        private val uri: String? = ARCHIVED_URI,
        private val durationMs: Long = ARCHIVE_DURATION_MS,
        /** 每次调用给一个不同引用的发生器（验证多条语音互不覆盖用）。 */
        private val uriFactory: ((Int) -> String?)? = null,
    ) : VoiceMessageArchiver {
        var persistCalls = 0
        val deleted = mutableListOf<String>()

        override suspend fun persist(tempFile: File, key: String): String? {
            persistCalls++
            return uriFactory?.invoke(persistCalls) ?: uri
        }

        override fun probeDurationMs(file: File): Long = durationMs

        override suspend fun delete(uri: String) {
            deleted += uri
        }
    }

    /** 投递宿主假实现：只收结果，不做 UI。 */
    private class RecordingHost(private val active: Boolean = true) : MediaGenHost {
        val results = mutableListOf<MediaGenResult>()
        val kinds = mutableListOf<MediaGenKind>()

        override suspend fun begin(kind: MediaGenKind): MediaGenSlot {
            kinds += kind
            return object : MediaGenSlot {
                override fun isActive(): Boolean = active

                override suspend fun onProgress(elapsedSeconds: Int) = Unit

                override suspend fun report(result: MediaGenResult) {
                    results += result
                }
            }
        }

        override suspend fun finish(kind: MediaGenKind) = Unit
    }

    private companion object {
        const val ARCHIVED_URI = "file:///data/voice_messages/abc.wav"
        const val ARCHIVE_DURATION_MS = 1_500L
        const val BYTES = 128

        /** 假宿主注入的回合标识（真实链路里是助手消息 id）。 */
        const val TURN_KEY = "turn-message-1"

        /** 逐段朗读场景的段落数（小于等于 [TURN_LIMIT]，不应触发上限）。 */
        const val VOICE_SEGMENTS = 3

        /** 与 [MediaGenToolsImpl] 的单回合语音上限保持一致的断言基线。 */
        const val TURN_LIMIT = 5
    }
}
