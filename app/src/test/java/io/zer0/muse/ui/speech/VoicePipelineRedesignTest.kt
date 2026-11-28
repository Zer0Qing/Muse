package io.zer0.muse.ui.speech

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.zer0.ai.core.ToolDefinition
import io.zer0.muse.data.assistant.VoiceReplyPolicyStore
import io.zer0.muse.tools.ToolExposurePolicy
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class VoicePipelineRedesignTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        VoiceReplyPolicyStore.resetForTests()
        File(context.filesDir, VoiceReplyPolicyStore.FILE_NAME).delete()
    }

    @Test
    fun `voice store detects magic bytes for mp3 wav ogg and isolates transcripts`() = runBlocking {
        val storageDir = File(tmp.root, "voice_messages").apply { mkdirs() }
        val store = VoiceMessageStore(storageDir = storageDir, durationProbe = VoiceDurationProbe { 3_000L })

        val mp3Tmp = File(tmp.root, "wrong.wav").apply {
            writeBytes(byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 0x04, 0x00, 0x00))
        }
        val mp3Uri = store.persist(mp3Tmp, "k_mp3")!!
        assertTrue("ID3 头应纠偏为 .mp3: $mp3Uri", mp3Uri.endsWith(".mp3"))

        val wavTmp = File(tmp.root, "wrong.mp3").apply {
            writeBytes("RIFF1234WAVEfmt ".toByteArray())
        }
        val wavUri = store.persist(wavTmp, "k_wav")!!
        assertTrue("RIFF 头应纠偏为 .wav: $wavUri", wavUri.endsWith(".wav"))

        val oggTmp = File(tmp.root, "wrong2.wav").apply {
            writeBytes("OggS\u0000\u0002".toByteArray())
        }
        val oggUri = store.persist(oggTmp, "k_ogg")!!
        assertTrue("OggS 头应纠偏为 .ogg: $oggUri", oggUri.endsWith(".ogg"))

        store.saveTranscript(mp3Uri, "  你好，这是一段测试语音  ")
        store.saveTranscript(wavUri, "WAV 文稿")
        assertEquals("你好，这是一段测试语音", store.readTranscript(mp3Uri))
        assertEquals("WAV 文稿", store.readTranscript(wavUri))

        // 文字稿必须存放在旁路目录，不能污染 storageDir.listFiles()
        assertEquals(3, storageDir.listFiles()?.size)

        store.delete(mp3Uri)
        assertNull(store.readTranscript(mp3Uri))
        assertEquals("WAV 文稿", store.readTranscript(wavUri))

        val removed = store.cleanupOrphans(listOf(wavUri))
        assertEquals(1, removed) // ogg 被清理，wav 保留
        assertEquals(1, storageDir.listFiles()?.size)
        assertEquals("WAV 文稿", store.readTranscript(wavUri))
    }

    @Test
    fun `playback controller tracks playedUris cycles speed fires onBeforePlay and completion only naturally`() {
        var beforePlayCalls = 0
        var completionCalls = 0
        val player = RecordingVoiceAudioPlayer()
        val controller = VoicePlaybackController(
            onBeforePlay = { beforePlayCalls++ },
            playerFactory = { player },
        )
        val file = File(tmp.root, "clip.wav").apply { writeBytes("RIFF1234WAVE".toByteArray()) }
        val uri = "file://${file.absolutePath}"

        controller.toggle(uri, file) { completionCalls++ }
        assertTrue(controller.state.isPlaying)
        assertTrue(uri in controller.state.playedUris)
        assertEquals(1, beforePlayCalls)

        // 倍速循环 1.0 -> 1.5 -> 2.0 -> 1.0
        assertEquals(1.0f, controller.state.speed, 0.001f)
        controller.cycleSpeed()
        assertEquals(1.5f, controller.state.speed, 0.001f)
        assertEquals(1.5f, player.lastSpeed, 0.001f)
        controller.cycleSpeed()
        assertEquals(2.0f, controller.state.speed, 0.001f)
        controller.cycleSpeed()
        assertEquals(1.0f, controller.state.speed, 0.001f)

        // 手动暂停再恢复应再次触发互斥钩子
        controller.toggle(uri, file) { completionCalls++ }
        assertFalse(controller.state.isPlaying)
        controller.toggle(uri, file) { completionCalls++ }
        assertTrue(controller.state.isPlaying)
        assertEquals(2, beforePlayCalls)

        // 自然播完触发 completionCalls
        player.finish()
        assertEquals(1, completionCalls)
        assertFalse(controller.state.isPlaying)

        // 重播后手动 stop() 不触发 completionCalls
        controller.toggle(uri, file) { completionCalls++ }
        controller.stop()
        assertEquals(1, completionCalls)
    }

    @Test
    fun `tool exposure policy force-includes send_voice and list_cloned_voices when requested`() {
        val pool = (1..25).map { idx -> tool("dummy_tool_$idx") } +
            listOf(tool("send_voice"), tool("list_cloned_voices"))

        val withoutAlways = ToolExposurePolicy.filterToolsForRequest(
            userText = "今天天气真好",
            tools = pool,
        ).map { it.name }.toSet()
        assertFalse("闲聊默认不应强塞 send_voice", "send_voice" in withoutAlways)

        val withAlways = ToolExposurePolicy.filterToolsForRequest(
            userText = "今天天气真好",
            tools = pool,
            alwaysIncludeToolNames = setOf("send_voice", "list_cloned_voices"),
        ).map { it.name }.toSet()
        assertTrue("高语音频率应强制暴露 send_voice", "send_voice" in withAlways)
        assertTrue("高语音频率应保持暴露 list_cloned_voices", "list_cloned_voices" in withAlways)
    }

    @Test
    fun `voice reply policy store persists autoVoiceBar global and per-agent overrides`() = runBlocking {
        VoiceReplyPolicyStore.resetForTests()
        val store = VoiceReplyPolicyStore.of(context)

        assertFalse(store.autoVoiceBarFor("agent_a"))
        store.setGlobalAutoVoiceBar(true).getOrThrow()
        assertTrue(store.autoVoiceBarFor("agent_a"))

        store.setAutoVoiceBar("agent_a", false).getOrThrow()
        assertFalse(store.autoVoiceBarFor("agent_a"))

        store.clearAutoVoiceBarOverride("agent_a").getOrThrow()
        assertTrue(store.autoVoiceBarFor("agent_a"))
    }

    private fun tool(name: String) = ToolDefinition(
        name = name,
        description = name,
        parametersJsonSchema = """{"type":"object","properties":{}}""",
    )
}

private class RecordingVoiceAudioPlayer : VoiceAudioPlayer {
    var lastSpeed = 1.0f
    private var completion: (() -> Unit)? = null

    override fun load(file: File, onCompletion: () -> Unit, onError: (Throwable) -> Unit): Boolean {
        completion = onCompletion
        return true
    }

    override fun start() = Unit

    override fun pause() = Unit

    override fun positionMs(): Long = 0L

    override fun setSpeed(speed: Float) {
        lastSpeed = speed
    }

    override fun release() {
        completion = null
    }

    fun finish() {
        completion?.invoke()
    }
}
