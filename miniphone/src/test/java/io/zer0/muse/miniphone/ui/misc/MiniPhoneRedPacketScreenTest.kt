package io.zer0.muse.miniphone.ui.misc

import android.content.Context
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.miniphone.R
import io.zer0.muse.miniphone.data.db.entity.MpRedPacketEntity
import io.zer0.muse.miniphone.data.misc.MpRedPacketStore
import io.zer0.muse.miniphone.ui.theme.MiniPhoneTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 群红包页：状态由可替换的 Flow 与回调驱动，不碰 Room。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(ExperimentalTestApi::class)
class MiniPhoneRedPacketScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val rows = MutableStateFlow(emptyList<MpRedPacketEntity>())
    private val sent = mutableListOf<Pair<String, String>>()
    private val claimed = mutableListOf<String>()
    private var backClicks = 0
    private var nextId = 0

    private fun res(id: Int): String = ApplicationProvider.getApplicationContext<Context>().getString(id)

    private fun packet(id: String, amount: String, state: String, note: String = "") = MpRedPacketEntity(
        id = id,
        threadId = "t1",
        senderId = "me",
        amount = amount,
        note = note,
        state = state,
        claimedByJson = "[]",
        createdAt = 1,
    )

    private fun render() {
        compose.setContent {
            MiniPhoneTheme(darkTheme = false) {
                MiniPhoneRedPacketScreen(
                    packets = rows,
                    onSend = { amount, note ->
                        sent += amount to note
                        rows.value = rows.value + packet("p-${nextId++}", amount, MpRedPacketStore.STATE_OPEN, note)
                    },
                    onClaim = { claimed += it },
                    onBack = { backClicks += 1 },
                )
            }
        }
    }

    @Test
    fun `空态与玩法提示可见`() {
        render()
        compose.onNodeWithTag(MiniPhoneMiscTags.RedPacket.EMPTY).assertIsDisplayed()
        compose.onNodeWithTag(MiniPhoneMiscTags.RedPacket.PAGE).assertIsDisplayed()
        compose.onNodeWithText(res(R.string.mp_redpacket_rp_hint)).assertIsDisplayed()
    }

    @Test
    fun `输入金额发红包会带上参数`() {
        render()
        compose.onNodeWithTag(MiniPhoneMiscTags.RedPacket.AMOUNT_INPUT).performTextInput("6.66")
        compose.onNodeWithTag(MiniPhoneMiscTags.RedPacket.SEND).performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(MiniPhoneMiscTags.RedPacket.packet("p-0")).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(listOf("6.66" to ""), sent)
    }

    @Test
    fun `空白金额不发`() {
        render()
        compose.onNodeWithTag(MiniPhoneMiscTags.RedPacket.SEND).performClick()
        compose.waitForIdle()
        assertEquals(0, sent.size)
        compose.onNodeWithTag(MiniPhoneMiscTags.RedPacket.EMPTY).assertIsDisplayed()
    }

    @Test
    fun `可领红包能触发领取回调`() {
        rows.value = listOf(packet("p1", "8.88", MpRedPacketStore.STATE_OPEN))
        render()
        compose.onNodeWithTag(MiniPhoneMiscTags.RedPacket.claim("p1")).performClick()
        assertEquals(listOf("p1"), claimed)
    }

    @Test
    fun `已领完状态按文案展示`() {
        rows.value = listOf(packet("p1", "8.88", MpRedPacketStore.STATE_CLAIMED))
        render()
        compose.onNodeWithText(res(R.string.mp_redpacket_state_claimed)).assertIsDisplayed()
    }

    @Test
    fun `点返回触发回调`() {
        render()
        compose.onNodeWithTag(MiniPhoneMiscTags.RedPacket.BACK).performClick()
        assertEquals(1, backClicks)
    }
}
