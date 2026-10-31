package io.zer0.muse.ui

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.R
import io.zer0.muse.ui.common.feedback.MUSE_DIALOG_SCRIM_TAG
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 多选合并预览弹窗的交互约束。
 *
 * 合并是不可撤销的落库操作，草稿又是用户可能刚改过的正文，
 * 所以这里钉的是「什么情况下不许把草稿丢掉」而不是排版。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MemoryMergeDialogTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val draft = MemoryMergeDraft(
        sources = listOf("User likes green tea", "User drinks green tea every morning"),
        mergedText = "Model draft",
        sourceItems = emptyList(),
    )

    @Test
    fun outsideTapKeepsMergeDraftOpen() {
        val cancelled = AtomicBoolean(false)
        composeTestRule.setContent {
            MaterialTheme {
                MemoryMergeDialog(
                    draft = draft,
                    running = false,
                    onCancel = { cancelled.set(true) },
                    onConfirm = {},
                )
            }
        }

        composeTestRule.onNodeWithTag(MUSE_DIALOG_SCRIM_TAG).performTouchInput {
            click(Offset.Zero)
        }

        assertFalse("an outside tap must not discard a merge draft", cancelled.get())
        composeTestRule.onNodeWithText("Model draft").assertExists()
    }

    @Test
    fun runningHidesCancelAndBlocksConfirm() {
        val cancelled = AtomicBoolean(false)
        val confirmed = AtomicBoolean(false)
        composeTestRule.setContent {
            MaterialTheme {
                MemoryMergeDialog(
                    draft = draft,
                    running = true,
                    onCancel = { cancelled.set(true) },
                    onConfirm = { confirmed.set(true) },
                )
            }
        }

        composeTestRule.onNodeWithText(context.getString(R.string.memory_screen_cancel)).assertDoesNotExist()
        composeTestRule.onNodeWithText(context.getString(R.string.memory_center_merge_running)).assertExists()
        val confirm = composeTestRule.onNodeWithText(context.getString(R.string.memory_center_merge_confirm))
        confirm.assertExists()
        confirm.assertIsNotEnabled()
        confirm.performClick()

        composeTestRule.onNodeWithTag(MUSE_DIALOG_SCRIM_TAG).performTouchInput {
            click(Offset.Zero)
        }

        assertFalse("a running merge must not be confirmed twice", confirmed.get())
        assertFalse("a running merge must not be dismissed", cancelled.get())
        composeTestRule.onNodeWithText("Model draft").assertExists()
    }

    @Test
    fun backPressOnlyLeavesWhileIdle() {
        assertTrue(
            "back must close the preview when nothing is running",
            mergeDialogProperties(running = false).dismissOnBackPress,
        )
        assertFalse(
            "back must be ignored while the merge is running",
            mergeDialogProperties(running = true).dismissOnBackPress,
        )
        assertFalse(
            "an outside tap must never close the preview",
            mergeDialogProperties(running = false).dismissOnClickOutside,
        )
    }

    @Test
    fun confirmSubmitsEditedTextAndKeepsSourcesVisible() {
        var submitted: String? = null
        composeTestRule.setContent {
            MaterialTheme {
                MemoryMergeDialog(
                    draft = draft,
                    running = false,
                    onCancel = {},
                    onConfirm = { submitted = it },
                )
            }
        }

        // 上半是被合并的原文：用户要能核对「合了哪几条」
        composeTestRule.onNodeWithText(context.getString(R.string.memory_center_merge_sources_title, 2))
            .assertExists()
        composeTestRule.onNodeWithText("User likes green tea").assertExists()

        composeTestRule.onNodeWithText("Model draft").performTextReplacement("Hand edited draft")
        composeTestRule.onNodeWithText(context.getString(R.string.memory_center_merge_confirm)).performClick()

        assertEquals("the edited text is what gets written back", "Hand edited draft", submitted)
    }
}
