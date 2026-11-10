package io.zer0.muse.miniphone.ui.call

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.zer0.muse.miniphone.R
import io.zer0.muse.miniphone.data.db.entity.MpCallSessionEntity
import io.zer0.muse.miniphone.ui.theme.MiniPhoneTheme
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 通话记录页：只渲染传入的时间线，不碰 Room（避免 ':memory:' 跨用例串库）。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MiniPhoneCallHistoryScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private var backClicks = 0

    private fun res(id: Int): String = androidx.test.core.app.ApplicationProvider
        .getApplicationContext<android.content.Context>().getString(id)

    private fun call(id: String, kind: String, startedAt: Long, durationMs: Long) = MpCallSessionEntity(
        id = id,
        threadId = "t1",
        characterId = "char-1",
        kind = kind,
        startedAt = startedAt,
        durationMs = durationMs,
    )

    private fun render(rows: List<MpCallSessionEntity>) {
        compose.setContent {
            MiniPhoneTheme(darkTheme = false) {
                MiniPhoneCallHistoryScreen(
                    history = flowOf(rows),
                    namesById = mapOf("char-1" to "阿离"),
                    onBack = { backClicks += 1 },
                    timeLabel = { "t=$it" },
                )
            }
        }
    }

    @Test
    fun `空态提示可见`() {
        render(emptyList())
        compose.onNodeWithTag(MiniPhoneCallTags.EMPTY).assertIsDisplayed()
        compose.onNodeWithTag(MiniPhoneCallTags.PAGE).assertIsDisplayed()
    }

    @Test
    fun `视频聊天与语音通话按文案展示`() {
        render(listOf(call("c1", "video", 100, 5000), call("c2", "voice", 200, 3000)))
        compose.onNodeWithTag(MiniPhoneCallTags.row("c1")).assertIsDisplayed()
        compose.onNodeWithText(res(R.string.mp_calls_video)).assertIsDisplayed()
        compose.onNodeWithText(res(R.string.mp_calls_voice)).assertIsDisplayed()
        assertEquals(2, compose.onAllNodesWithText("阿离").fetchSemanticsNodes().size)
    }

    @Test
    fun `时长为 0 显示未接通而不是通话零秒`() {
        render(listOf(call("c1", "voice", 100, 0)))
        compose.onNodeWithText(res(R.string.mp_calls_missed)).assertIsDisplayed()
    }

    @Test
    fun `点返回触发回调`() {
        render(emptyList())
        compose.onNodeWithTag(MiniPhoneCallTags.BACK).performClick()
        assertEquals(1, backClicks)
    }
}
