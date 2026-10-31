package io.zer0.muse.data.miniphone

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.moment.MiniContextBuilder
import io.zer0.muse.miniphone.api.MpChatTurn
import io.zer0.muse.miniphone.api.MpReplyEvent
import io.zer0.muse.miniphone.data.db.MpActorType
import io.zer0.muse.ui.speech.VoiceMessageArchiver
import io.zer0.muse.ui.speech.TtsSynthResult
import io.zer0.muse.ui.speech.VoiceSynthesizer
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 语音标记的切分与降级:哪几段合成语音、哪几段只能当文本、顺序与条数上限。
 *
 * 模型输出、合成端口、语音存档端口全部是假的 —— 这里只验宿主的切分与降级,
 * 不碰网络与 TTS 引擎。
 */
class MiniPhoneVoiceScriptTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    @Test
    fun `标记内合成语音 标记外按原顺序发文本`() = runBlocking {
        val host = voiceHost(answer = "你好[语音]这句话要念出来[/语音]再见")

        val events = host.respond("a1", USER_TURN).toList()

        assertEquals(
            listOf(
                MpReplyEvent.Text("你好"),
                MpReplyEvent.Voice(VOICE_URI, DURATION_MS),
                MpReplyEvent.Text("再见"),
            ),
            events,
        )
    }

    @Test
    fun `双语里没有中文的那一段不发语音`() = runBlocking {
        val synthesizer = mockk<VoiceSynthesizer>()
        val host = voiceHost(answer = "[语音]Okay let's go[/语音]", synthesizer = synthesizer)

        val events = host.respond("a1", USER_TURN).toList()

        assertEquals(listOf(MpReplyEvent.Text("Okay let's go")), events)
        coVerify(exactly = 0) { synthesizer.synthesizeTo(any(), any(), any()) }
    }

    @Test
    fun `超过五段时多出来的段降级成文本`() = runBlocking {
        val script = (1..7).joinToString(separator = "") { "[语音]第 $it 句[/语音]" }
        val host = voiceHost(answer = script)

        val events = host.respond("a1", USER_TURN).toList()

        assertEquals("只合成前五段", 5, events.count { it is MpReplyEvent.Voice })
        assertEquals(
            "多出来的两段降级成文本",
            listOf(MpReplyEvent.Text("第 6 句"), MpReplyEvent.Text("第 7 句")),
            events.drop(5),
        )
    }

    @Test
    fun `合成失败时这一段降级成文本`() = runBlocking {
        val synthesizer = mockk<VoiceSynthesizer>()
        coEvery { synthesizer.synthesizeTo(any(), any(), any()) } returns TtsSynthResult.Failure("合成失败")
        val host = voiceHost(answer = "[语音]这句话要念出来[/语音]", synthesizer = synthesizer)

        val events = host.respond("a1", USER_TURN).toList()

        assertEquals(listOf(MpReplyEvent.Text("这句话要念出来")), events)
    }

    @Test
    fun `存档落不下时这一段也降级成文本`() = runBlocking {
        val store = archiver(archivedUri = null)
        val host = voiceHost(answer = "[语音]这句话要念出来[/语音]", store = store)

        val events = host.respond("a1", USER_TURN).toList()

        assertEquals(listOf(MpReplyEvent.Text("这句话要念出来")), events)
    }

    @Test
    fun `合成时不带情绪`() = runBlocking {
        val synthesizer = mockk<VoiceSynthesizer>()
        coEvery { synthesizer.synthesizeTo(any(), any(), any()) } answers {
            secondArg<File>().writeBytes(ByteArray(TEMP_BYTES))
            TtsSynthResult.Success
        }
        val host = voiceHost(answer = "[语音]这句话要念出来[/语音]", synthesizer = synthesizer)

        host.respond("a1", USER_TURN).toList()

        coVerify(exactly = 1) { synthesizer.synthesizeTo("这句话要念出来", any(), null) }
    }

    @Test
    fun `落单与没闭合的标记都不进正文`() = runBlocking {
        val host = voiceHost(answer = "[语音]念这句[/语音][/语音] 结束[语音]半截")

        val events = host.respond("a1", USER_TURN).toList()

        assertEquals(
            listOf(
                MpReplyEvent.Voice(VOICE_URI, DURATION_MS),
                // 落单的标记只被抹掉,前后文字拼回一段
                MpReplyEvent.Text("结束半截"),
            ),
            events,
        )
    }

    @Test
    fun `纯标记没有正文时按失败处理`() = runBlocking {
        val host = voiceHost(answer = "[语音][/语音]")

        val events = host.respond("a1", USER_TURN).toList()

        assertEquals(listOf(MpReplyEvent.Failed), events)
    }

    @Test
    fun `中文判定只看汉字 不看标点与拉丁字母`() {
        assertTrue(hasChineseCharacters("晚安"))
        assertTrue(hasChineseCharacters("Okay，晚安"))
        assertFalse(hasChineseCharacters("Okay let's go"))
        assertFalse(hasChineseCharacters("「」《》。"))
    }

    // ── 夹具 ────────────────────────────────────────────────────────────

    /** 一轮生成的答案由 [answer] 决定,语音走假的合成与存档端口。 */
    private fun voiceHost(
        answer: String,
        synthesizer: VoiceSynthesizer = okSynthesizer(),
        store: VoiceMessageArchiver = archiver(archivedUri = VOICE_URI),
    ): AppMiniPhoneHost {
        val runner = mockk<MiniPhoneToolTurnRunner>()
        every { runner.reply(any(), any(), any()) } returns flowOf(MpReplyEvent.Text(answer))
        val contextBuilder = mockk<MiniContextBuilder>(relaxed = true)
        coEvery {
            contextBuilder.buildMessages(assistant = any(), userContent = any(), sessionId = any(), voiceMessageEnabled = any())
        } answers {
            listOf(
                UIMessage(role = MessageRole.SYSTEM, content = "你是阿绫"),
                UIMessage(role = MessageRole.USER, content = secondArg()),
            )
        }
        return AppMiniPhoneHost(
            assistantRepository = defaultRepo(),
            settingsRepository = mockk<SettingsRepository>(relaxed = true),
            textGenerator = mockk<MiniPhoneTextGenerator>(relaxed = true),
            contextBuilder = contextBuilder,
            toolTurnRunner = runner,
            voiceSynthesizer = synthesizer,
            voiceStore = store,
            cacheDir = tempDir.newFolder("cache"),
        )
    }

    private fun okSynthesizer(): VoiceSynthesizer = mockk {
        coEvery { synthesizeTo(any(), any(), any()) } answers {
            secondArg<File>().writeBytes(ByteArray(TEMP_BYTES))
            TtsSynthResult.Success
        }
    }

    private fun archiver(archivedUri: String?): VoiceMessageArchiver = mockk(relaxed = true) {
        coEvery { persist(any(), any()) } returns archivedUri
        every { probeDurationMs(any()) } returns DURATION_MS
    }

    private fun defaultRepo(): AssistantRepository {
        val repo = mockk<AssistantRepository>(relaxed = true)
        val assistant = AssistantEntity(id = "a1", name = "阿绫")
        every { repo.observeAll } returns flowOf(listOf(assistant))
        coEvery { repo.getById("a1") } returns assistant
        return repo
    }

    private companion object {
        val USER_TURN = listOf(MpChatTurn(MpActorType.USER, "在吗"))
        const val TEMP_BYTES: Int = 64
        const val VOICE_URI: String = "file:///data/voice_messages/abc.wav"
        const val DURATION_MS: Long = 1_500L
    }
}
