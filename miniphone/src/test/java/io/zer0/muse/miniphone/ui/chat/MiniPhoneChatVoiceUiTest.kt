package io.zer0.muse.miniphone.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import io.zer0.muse.miniphone.data.db.MpActorType
import io.zer0.muse.miniphone.data.db.MpMessageType
import io.zer0.muse.miniphone.ui.theme.MiniPhoneTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 语音条:渲染分支、时长未知与文件缺失的退路、点按播放与暂停、播完复位。
 *
 * 播放器换成假实现,断言的是气泡自己的契约 —— 装载了几次、暂停没有、播完有没有释放 ——
 * 不碰系统音频栈。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MiniPhoneChatVoiceUiTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val tempDir = TemporaryFolder()

    private val player = FakeVoicePlayer()
    private lateinit var controller: MiniPhoneVoicePlaybackController
    private var voiceUri: String = ""

    @Before
    fun setUp() {
        controller = MiniPhoneVoicePlaybackController { player }
        // 聊天页里的语音条自己从 Koin 取播放器;这里注册同一份假播放器背后的控制器
        startKoin { modules(module { single { controller } }) }
        val audioFile = tempDir.newFile("voice.wav").apply { writeBytes(ByteArray(AUDIO_BYTES)) }
        voiceUri = "file://" + audioFile.absolutePath
    }

    @After
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun voiceMessageRendersTheVoiceBubbleInsteadOfATextBubble() {
        renderScreen(
            chatState(voiceMessage(id = "m1", uri = voiceUri, durationMs = SEVEN_SECONDS_MS)),
        )

        compose.onNodeWithTag(MiniPhoneChatTags.voice("m1")).assertExists()
        compose.onNodeWithTag(MiniPhoneChatTags.bubble("m1")).assertDoesNotExist()
        compose.onNodeWithText(VOICE_PREVIEW_TEXT).assertDoesNotExist()
        compose.onNodeWithText("0:07").assertExists()
    }

    @Test
    fun widthGrowsInThreeTiersWithTheDuration() {
        assertEquals(140.dp, voiceBubbleWidth(SEVEN_SECONDS_MS))
        assertEquals(140.dp, voiceBubbleWidth(15_000L))
        assertEquals(184.dp, voiceBubbleWidth(30_000L))
        assertEquals(184.dp, voiceBubbleWidth(60_000L))
        assertEquals(228.dp, voiceBubbleWidth(90_000L))
    }

    @Test
    fun unknownDurationShowsPlaceholderInsteadOfZero() {
        renderBubble(voiceMessage(id = "m1", uri = voiceUri, durationMs = 0L))

        compose.onNodeWithText(UNKNOWN_DURATION).assertExists()
        compose.onNodeWithText("0:00").assertDoesNotExist()
    }

    @Test
    fun missingAudioFileFallsBackToAStaticCapsule() {
        renderBubble(voiceMessage(id = "m1", uri = "file:///no/such/dir/gone.wav", durationMs = SEVEN_SECONDS_MS))

        compose.onNodeWithTag(MiniPhoneChatTags.voiceUnavailable("m1")).assertExists()
        compose.onNodeWithTag(MiniPhoneChatTags.voice("m1")).assertDoesNotExist()
        compose.onNodeWithText(VOICE_UNAVAILABLE_TEXT).assertExists()
        // 文件不在就不该碰播放器
        compose.onNodeWithTag(MiniPhoneChatTags.voiceUnavailable("m1")).performClick()
        assertEquals(0, player.loadCount)
        assertNull(controller.state.uri)
    }

    @Test
    fun tappingSwitchesToPlayingAndBackToPaused() {
        renderBubble(voiceMessage(id = "m1", uri = voiceUri, durationMs = SEVEN_SECONDS_MS))
        compose.onNodeWithTag(MiniPhoneChatTags.VOICE_PLAY_ICON, useUnmergedTree = true).assertExists()

        compose.onNodeWithTag(MiniPhoneChatTags.voice("m1")).performClick()

        compose.onNodeWithTag(MiniPhoneChatTags.VOICE_PAUSE_ICON, useUnmergedTree = true).assertExists()
        assertEquals("点一次只装载一次", 1, player.loadCount)
        assertEquals("装载即起播,不再额外调 start", 0, player.startCount)
        assertTrue(controller.state.isPlaying)

        compose.onNodeWithTag(MiniPhoneChatTags.voice("m1")).performClick()

        compose.onNodeWithTag(MiniPhoneChatTags.VOICE_PLAY_ICON, useUnmergedTree = true).assertExists()
        assertEquals("再点是暂停而不是重新装载", 1, player.pauseCount)
        assertEquals(1, player.loadCount)
        assertFalse(controller.state.isPlaying)
    }

    @Test
    fun playbackCompletionResetsToIdle() {
        renderBubble(voiceMessage(id = "m1", uri = voiceUri, durationMs = SEVEN_SECONDS_MS))
        compose.onNodeWithTag(MiniPhoneChatTags.voice("m1")).performClick()

        player.finishPlayback()
        compose.waitForIdle()

        compose.onNodeWithTag(MiniPhoneChatTags.VOICE_PLAY_ICON, useUnmergedTree = true).assertExists()
        assertNull("播完应复位为空闲", controller.state.uri)
        assertFalse(controller.state.isPlaying)
        assertTrue("播完应释放播放器", player.releaseCount >= 1)
    }

    private fun renderBubble(message: ChatMessageUi) {
        compose.setContent {
            MiniPhoneTheme(darkTheme = false) {
                Column {
                    ChatVoiceBubble(message = message, controller = controller)
                }
            }
        }
    }

    private fun renderScreen(state: ChatUiState) {
        compose.setContent {
            MiniPhoneTheme(darkTheme = false) {
                MiniPhoneChatScreen(
                    state = state,
                    onBack = {},
                    onLoadOlder = {},
                    input = ChatInputActions(),
                )
            }
        }
    }

    private fun chatState(vararg messages: ChatMessageUi): ChatUiState = ChatUiState(
        threadId = "t1",
        characterId = "c1",
        peerName = "阿离",
        messages = messages.toList(),
    )

    private fun voiceMessage(id: String, uri: String, durationMs: Long): ChatMessageUi = ChatMessageUi(
        id = id,
        sender = MpActorType.CHARACTER,
        type = MpMessageType.VOICE,
        content = VOICE_PREVIEW_TEXT,
        mediaUri = uri,
        voiceDurationMs = durationMs,
        createdAt = 60_000L,
    )

    private companion object {
        const val AUDIO_BYTES: Int = 128
        const val SEVEN_SECONDS_MS: Long = 7_000L
        const val VOICE_PREVIEW_TEXT: String = "[语音]"
        const val UNKNOWN_DURATION: String = "--:--"
    }
}

/** 假播放器:只记账,不出声。 */
private class FakeVoicePlayer : MpVoiceAudioPlayer {
    var loadCount: Int = 0
        private set
    var startCount: Int = 0
        private set
    var pauseCount: Int = 0
        private set
    var releaseCount: Int = 0
        private set

    private var onCompletion: (() -> Unit)? = null

    override fun load(file: File, onCompletion: () -> Unit, onError: (Throwable) -> Unit): Boolean {
        loadCount += 1
        this.onCompletion = onCompletion
        return true
    }

    override fun start() {
        startCount += 1
    }

    override fun pause() {
        pauseCount += 1
    }

    override fun positionMs(): Long = 0L

    override fun release() {
        releaseCount += 1
    }

    fun finishPlayback() {
        onCompletion?.invoke()
    }
}
