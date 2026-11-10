package io.zer0.muse.miniphone.ui.innervoice

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.miniphone.R
import io.zer0.muse.miniphone.data.chat.MpInnerVoiceStore
import io.zer0.muse.miniphone.data.db.MiniPhoneDb
import io.zer0.muse.miniphone.data.db.entity.MpInnerVoiceEntity
import io.zer0.muse.miniphone.newInMemoryMiniPhoneDb
import io.zer0.muse.miniphone.ui.theme.MiniPhoneTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 心声历史页真的能打开：空态、有内容时逐条列出、名字对不上时退回 id、返回。
 *
 * 这个页面此前**没有任何测试** —— 只有 store 的读写测试，页面本身从没被组合过。
 * 三条最容易在真机上才发现的问题各钉一条：空库时是不是一片空白、角色名缺失时会不会显示成空白行、
 * 返回键点了有没有反应（它原先连 testTag 都没有）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MiniPhoneInnerVoiceHistoryScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var db: MiniPhoneDb
    private lateinit var store: MpInnerVoiceStore
    private var backs = 0

    @Before
    fun setUp() {
        db = newInMemoryMiniPhoneDb()
        store = MpInnerVoiceStore(db.innerVoiceDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun text(id: Int): String = ApplicationProvider.getApplicationContext<android.content.Context>().getString(id)

    private fun render(names: Map<String, String> = mapOf("c1" to "阿离")) {
        compose.setContent {
            MiniPhoneTheme(darkTheme = false) {
                MiniPhoneInnerVoiceHistoryScreen(store = store, namesById = names, onBack = { backs += 1 })
            }
        }
    }

    private fun seed(id: String, characterId: String = "c1", text: String = "其实我有点难过") = runBlocking {
        db.innerVoiceDao().insert(
            MpInnerVoiceEntity(
                id = id,
                threadId = "t1",
                characterId = characterId,
                messageId = null,
                text = text,
                createdAt = 1_000L,
            ),
        )
    }

    @Test
    fun `没有心声时显示空态而不是一片空白`() {
        render()
        compose.onNodeWithTag("mp-inner-voice-history-page").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.mp_inner_voice_history_empty)).assertIsDisplayed()
    }

    @Test
    fun `有心声时逐条列出`() {
        seed("iv-1")
        seed("iv-2", text = "不想让他看出来")
        render()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("mp-inner-voice-history-item-iv-1").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("mp-inner-voice-history-item-iv-1").assertIsDisplayed()
        compose.onNodeWithTag("mp-inner-voice-history-item-iv-2").assertIsDisplayed()
        compose.onNodeWithText("其实我有点难过").assertIsDisplayed()
    }

    @Test
    fun `名字表里查不到角色时退回显示 id 而不是空白`() {
        seed("iv-1", characterId = "c-unknown")
        render(names = emptyMap())
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("mp-inner-voice-history-item-iv-1").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("c-unknown").assertIsDisplayed()
    }

    @Test
    fun `返回键交回上层`() {
        render()
        compose.onNodeWithTag("mp-inner-voice-history-back").performClick()
        assertEquals(1, backs)
    }
}
