package io.zer0.muse.miniphone.ui.call

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import io.zer0.muse.miniphone.R
import io.zer0.muse.miniphone.data.call.MpCallKind
import io.zer0.muse.miniphone.data.call.MpCallPhase
import io.zer0.muse.miniphone.data.call.MpCallRejectReason
import io.zer0.muse.miniphone.data.call.MpCallSessionUi
import io.zer0.muse.miniphone.data.call.MpCallTurnUi
import io.zer0.muse.miniphone.data.db.MpActorType
import io.zer0.muse.miniphone.ui.theme.MiniPhoneTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 通话进行中的页面：阶段、计时、说话是否可用、挂断与音频。
 *
 * 断言全部绑资源而不是中文串（测试进程的默认 locale 可能是 en）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MiniPhoneCallScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val session = MutableStateFlow<MpCallSessionUi?>(null)
    private val elapsed = MutableStateFlow(0L)
    private val said = mutableListOf<String>()
    private val played = mutableListOf<String>()
    private var hangUps = 0
    private var backs = 0

    private fun res(id: Int, vararg args: Any): String = androidx.test.core.app.ApplicationProvider
        .getApplicationContext<android.content.Context>().getString(id, *args)

    private fun render() {
        compose.setContent {
            MiniPhoneTheme(darkTheme = false) {
                MiniPhoneCallScreen(
                    session = session,
                    elapsedMs = elapsed,
                    onSay = { said += it },
                    onHangUp = { hangUps += 1 },
                    onPlayVoice = { played += it },
                    onBack = { backs += 1 },
                )
            }
        }
    }

    private fun call(
        phase: MpCallPhase = MpCallPhase.CONNECTED,
        turns: List<MpCallTurnUi> = emptyList(),
        noAnswer: Boolean = false,
        reason: MpCallRejectReason = MpCallRejectReason.NONE,
        kind: MpCallKind = MpCallKind.VOICE,
    ) = MpCallSessionUi(
        callId = "call-1",
        threadId = "t1",
        characterId = "c1",
        kind = kind,
        phase = phase,
        startedAtMs = 1_000L,
        connectedAtMs = if (phase == MpCallPhase.CONNECTED) 2_000L else 0L,
        turns = turns,
        noAnswer = noAnswer,
        rejectReason = reason,
    )

    @Test
    fun `没有通话时说明现状并能返回`() {
        session.value = null
        render()
        compose.onNodeWithTag(MiniPhoneCallTags.LIVE_PAGE).assertIsDisplayed()
        compose.onNodeWithText(res(R.string.mp_call_none)).assertIsDisplayed()
        compose.onNodeWithTag(MiniPhoneCallTags.BACK).performClick()
        assertEquals(1, backs)
    }

    @Test
    fun `接通后显示阶段与计时`() {
        session.value = call()
        elapsed.value = 65_000L
        render()
        compose.onNodeWithText(res(R.string.mp_call_connected)).assertIsDisplayed()
        compose.onNodeWithText(res(R.string.mp_call_duration, "01:05")).assertIsDisplayed()
    }

    @Test
    fun `没接通时输入置灰`() {
        session.value = call(phase = MpCallPhase.RINGING)
        render()
        compose.onNodeWithTag(MiniPhoneCallTags.LIVE_SAY_INPUT).assertIsNotEnabled()
    }

    @Test
    fun `接通后可以说话并交给回调`() {
        session.value = call()
        render()
        compose.onNodeWithTag(MiniPhoneCallTags.LIVE_SAY_INPUT).assertIsEnabled()
        compose.onNodeWithTag(MiniPhoneCallTags.LIVE_SAY_INPUT).performTextInput("  听到吗  ")
        compose.onNodeWithTag(MiniPhoneCallTags.LIVE_SAY_SEND).performClick()
        assertEquals(listOf("听到吗"), said)
    }

    @Test
    fun `未接通的会话显示未接通而不是通话零秒`() {
        session.value = call(phase = MpCallPhase.ENDED, noAnswer = true)
        render()
        compose.onNodeWithText(res(R.string.mp_call_no_answer)).assertIsDisplayed()
    }

    @Test
    fun `相机被拒时说明已改用语音`() {
        session.value = call(
            phase = MpCallPhase.CONNECTED,
            reason = MpCallRejectReason.CAMERA_DENIED,
            kind = MpCallKind.VOICE,
        )
        render()
        compose.onNodeWithTag(MiniPhoneCallTags.LIVE_NOTE).assertIsDisplayed()
        compose.onNodeWithText(res(R.string.mp_call_camera_denied)).assertIsDisplayed()
    }

    @Test
    fun `每一句话都列出，语音段可播`() {
        session.value = call(
            turns = listOf(
                MpCallTurnUi(speaker = MpActorType.USER, text = "喂", atMs = 1),
                MpCallTurnUi(speaker = MpActorType.CHARACTER, text = "", voiceUri = "file:///a.mp3", atMs = 2),
            ),
        )
        render()
        compose.onNodeWithTag(MiniPhoneCallTags.turn(0)).assertIsDisplayed()
        compose.onNodeWithText(res(R.string.mp_call_mine)).assertIsDisplayed()
        compose.onNodeWithText(res(R.string.mp_call_play_voice)).performClick()
        assertEquals(listOf("file:///a.mp3"), played)
    }

    @Test
    fun `挂断交给回调`() {
        session.value = call()
        render()
        compose.onNodeWithTag(MiniPhoneCallTags.LIVE_HANGUP).performClick()
        assertEquals(1, hangUps)
    }
}
