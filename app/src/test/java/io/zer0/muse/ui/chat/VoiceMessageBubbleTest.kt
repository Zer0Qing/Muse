package io.zer0.muse.ui.chat

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.R
import io.zer0.muse.ui.speech.VoiceAudioPlayer
import io.zer0.muse.ui.speech.VoiceDurationProbe
import io.zer0.muse.ui.speech.VoiceMessageStore
import io.zer0.muse.ui.speech.VoicePlaybackController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 语音消息条渲染与播放行为测试。
 *
 * 播放器通过 [VoiceAudioPlayer] 注入假实现，因此不碰系统音频栈：断言的是
 * "播放器被调了几次 / 图标切没切 / 换条时上一条停没停"这些气泡自身的契约。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VoiceMessageBubbleTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val storageDir: File by lazy { File(tmp.root, "voice_messages").apply { mkdirs() } }

    private val store: VoiceMessageStore by lazy {
        VoiceMessageStore(storageDir = storageDir, durationProbe = VoiceDurationProbe { 7000L })
    }

    /** 存档目录里的真实文件（`resolve` 只认目录内的文件）。 */
    private fun storedVoice(name: String): String {
        val file = File(storageDir, name).apply { writeText("RIFF-fake-wav") }
        return "file://" + file.absolutePath
    }

    private fun render(
        voiceUri: String,
        durationMs: Long,
        controller: VoicePlaybackController = VoicePlaybackController { FakeVoiceAudioPlayer() },
        isFromUser: Boolean = false,
    ) {
        composeTestRule.setContent {
            MaterialTheme {
                VoiceMessageBubble(
                    voiceUri = voiceUri,
                    durationMs = durationMs,
                    isFromUser = isFromUser,
                    controller = controller,
                    store = store,
                )
            }
        }
    }

    @Test
    fun durationOfSevenSecondsShowsMinuteSecondText() {
        render(voiceUri = storedVoice("seven.wav"), durationMs = 7_000L)

        composeTestRule.onNodeWithText("0:07").assertExists()
        composeTestRule.onNodeWithText("--:--").assertDoesNotExist()
    }

    @Test
    fun unknownDurationShowsPlaceholderInsteadOfZero() {
        render(voiceUri = storedVoice("unknown.wav"), durationMs = 0L)

        composeTestRule.onNodeWithText("--:--").assertExists()
        composeTestRule.onNodeWithText("0:00", substring = true).assertDoesNotExist()
    }

    @Test
    fun tapStartsPlaybackOnceAndSwitchesIconToPause() {
        val player = FakeVoiceAudioPlayer()
        val controller = VoicePlaybackController { player }
        render(voiceUri = storedVoice("toggle.wav"), durationMs = 7_000L, controller = controller)

        composeTestRule.onNodeWithTag(VOICE_MESSAGE_PLAY_ICON_TAG, useUnmergedTree = true).assertExists()

        composeTestRule.onNodeWithTag(VOICE_MESSAGE_BUBBLE_TAG).performClick()

        composeTestRule.onNodeWithTag(VOICE_MESSAGE_PAUSE_ICON_TAG, useUnmergedTree = true).assertExists()
        assertEquals("点一次只应起播一次", 1, player.loadCount)
        assertEquals("起播后不应再额外调 start", 0, player.startCount)
        assertTrue("控制器应处于播放态", controller.state.isPlaying)

        composeTestRule.onNodeWithTag(VOICE_MESSAGE_BUBBLE_TAG).performClick()

        composeTestRule.onNodeWithTag(VOICE_MESSAGE_PLAY_ICON_TAG, useUnmergedTree = true).assertExists()
        assertEquals("再点应是暂停而不是重新装载", 1, player.pauseCount)
        assertEquals("整段交互只装载一次", 1, player.loadCount)
        assertFalse("控制器应处于暂停态", controller.state.isPlaying)
    }

    @Test
    fun completionCallbackResetsToIdle() {
        val player = FakeVoiceAudioPlayer()
        val controller = VoicePlaybackController { player }
        render(voiceUri = storedVoice("finish.wav"), durationMs = 7_000L, controller = controller)
        composeTestRule.onNodeWithTag(VOICE_MESSAGE_BUBBLE_TAG).performClick()

        player.finishPlayback()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(VOICE_MESSAGE_PLAY_ICON_TAG, useUnmergedTree = true).assertExists()
        assertEquals("播完应复位为空闲", null, controller.state.uri)
        assertFalse("播完不应还在播放", controller.state.isPlaying)
        assertTrue("播完应释放播放器", player.releaseCount >= 1)
    }

    @Test
    fun unresolvableUriShowsExpiredStateWithoutThrowing() {
        val player = FakeVoiceAudioPlayer()
        val controller = VoicePlaybackController { player }
        val missing = "file://" + File(storageDir, "gone.wav").absolutePath
        render(voiceUri = missing, durationMs = 7_000L, controller = controller)

        composeTestRule.onNodeWithTag(VOICE_MESSAGE_EXPIRED_TAG).assertExists()
        composeTestRule.onNodeWithText(context.getString(R.string.chat_voice_expired)).assertExists()
        composeTestRule.onNodeWithTag(VOICE_MESSAGE_BUBBLE_TAG).assertDoesNotExist()
        // 失效态不建播放器，点也点不出声音
        composeTestRule.onNodeWithTag(VOICE_MESSAGE_EXPIRED_TAG).performClick()
        assertEquals("失效语音不应装载播放器", 0, player.loadCount)
        assertEquals(null, controller.state.uri)
    }

    @Test
    fun playingSecondVoiceStopsFirstOne() {
        val firstPlayer = FakeVoiceAudioPlayer()
        val secondPlayer = FakeVoiceAudioPlayer()
        val players = ArrayDeque(listOf(firstPlayer, secondPlayer))
        val controller = VoicePlaybackController { players.removeFirst() }
        val firstUri = storedVoice("first.wav")
        val secondUri = storedVoice("second.wav")

        composeTestRule.setContent {
            MaterialTheme {
                Column {
                    VoiceMessageBubble(
                        voiceUri = firstUri,
                        durationMs = 7_000L,
                        isFromUser = false,
                        controller = controller,
                        store = store,
                    )
                    VoiceMessageBubble(
                        voiceUri = secondUri,
                        durationMs = 7_000L,
                        isFromUser = true,
                        controller = controller,
                        store = store,
                    )
                }
            }
        }

        composeTestRule.onAllNodesWithTag(VOICE_MESSAGE_BUBBLE_TAG)[0].performClick()
        assertTrue(firstPlayer.playing)

        composeTestRule.onAllNodesWithTag(VOICE_MESSAGE_BUBBLE_TAG)[1].performClick()

        assertFalse("第一条应被停掉", firstPlayer.playing)
        assertTrue("第一条的播放器应被释放", firstPlayer.releaseCount >= 1)
        assertTrue("第二条应在播", secondPlayer.playing)
        assertEquals("播放权应转给第二条", secondUri, controller.state.uri)
    }
}

/** 假播放器：只记录调用，不出声。 */
private class FakeVoiceAudioPlayer : VoiceAudioPlayer {
    var loadCount = 0
    var startCount = 0
    var pauseCount = 0
    var releaseCount = 0
    var playing = false
    private var onCompletion: (() -> Unit)? = null

    override fun load(file: File, onCompletion: () -> Unit, onError: (Throwable) -> Unit): Boolean {
        loadCount++
        playing = true
        this.onCompletion = onCompletion
        return true
    }

    override fun start() {
        startCount++
        playing = true
    }

    override fun pause() {
        pauseCount++
        playing = false
    }

    override fun positionMs(): Long = 0L

    override fun release() {
        releaseCount++
        playing = false
    }

    fun finishPlayback() {
        playing = false
        onCompletion?.invoke()
    }
}
