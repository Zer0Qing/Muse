package io.zer0.muse.miniphone.ui.memo

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import io.zer0.muse.miniphone.data.db.entity.MpMemoEntity
import io.zer0.muse.miniphone.ui.theme.MiniPhoneTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 备忘录页：状态由可替换的 Flow 与回调驱动，不碰 Room。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MiniPhoneMemoScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val rows = MutableStateFlow(emptyList<MpMemoEntity>())
    private val saved = mutableListOf<String>()
    private val deleted = mutableListOf<String>()
    private var nextId = 0
    private var backClicks = 0

    private fun memo(id: String, content: String, updatedAt: Long) =
        MpMemoEntity(id = id, characterId = "", content = content, createdAt = 1, updatedAt = updatedAt)

    private fun render() {
        compose.setContent {
            MiniPhoneTheme(darkTheme = false) {
                MiniPhoneMemoScreen(
                    memoFlow = rows,
                    onSave = { text ->
                        saved += text.trim()
                        rows.value = rows.value + memo("memo-${nextId++}", text.trim(), 9)
                    },
                    onDelete = { id ->
                        deleted += id
                        rows.value = rows.value.filterNot { it.id == id }
                    },
                    onBack = { backClicks += 1 },
                )
            }
        }
    }

    @Test
    fun `空态提示可见`() {
        render()
        compose.onNodeWithTag(MiniPhoneMemoTags.EMPTY).assertIsDisplayed()
        compose.onNodeWithTag(MiniPhoneMemoTags.PAGE).assertIsDisplayed()
    }

    @Test
    fun `输入一条后出现并可删除`() {
        render()
        compose.onNodeWithTag(MiniPhoneMemoTags.INPUT).performTextInput("  记得买伞  ")
        compose.onNodeWithTag(MiniPhoneMemoTags.ADD).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(MiniPhoneMemoTags.row("memo-0")).assertIsDisplayed()
        assertEquals(listOf("记得买伞"), saved)
        compose.onNodeWithTag(MiniPhoneMemoTags.delete("memo-0")).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(MiniPhoneMemoTags.EMPTY).assertIsDisplayed()
        assertEquals(listOf("memo-0"), deleted)
    }

    @Test
    fun `空白输入不写入`() {
        render()
        compose.onNodeWithTag(MiniPhoneMemoTags.INPUT).performTextInput("   ")
        compose.onNodeWithTag(MiniPhoneMemoTags.ADD).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(MiniPhoneMemoTags.EMPTY).assertIsDisplayed()
        assertEquals(0, saved.size)
    }

    @Test
    fun `点返回触发回调`() {
        render()
        compose.onNodeWithTag(MiniPhoneMemoTags.BACK).performClick()
        assertEquals(1, backClicks)
    }
}
