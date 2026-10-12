package io.zer0.muse.ui

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.R
import io.zer0.muse.ui.common.feedback.MUSE_DIALOG_SCRIM_TAG
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MemoryDialogsDashboardTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun factEditDialogOutsideTapKeepsDraftOpen() {
        val dismissed = AtomicBoolean(false)
        composeTestRule.setContent {
            MaterialTheme {
                FactEditDialog(
                    title = "Edit memory",
                    initialContent = "Draft memory",
                    onDismiss = { dismissed.set(true) },
                    onConfirm = { _, onSaved -> onSaved(true) },
                )
            }
        }

        composeTestRule.onNodeWithTag(MUSE_DIALOG_SCRIM_TAG).performTouchInput {
            click(Offset.Zero)
        }

        assertFalse("an outside tap must not discard an edit draft", dismissed.get())
        composeTestRule.onNodeWithText("Edit memory").assertExists()
        composeTestRule.onNodeWithText("Draft memory").assertExists()
    }

    @Test
    fun failedSaveKeepsEditorAndDraftVisible() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dismissed = AtomicBoolean(false)
        composeTestRule.setContent {
            MaterialTheme {
                FactEditDialog(
                    title = "Edit memory",
                    initialContent = "Draft memory",
                    onDismiss = { dismissed.set(true) },
                    onConfirm = { _, onSaved -> onSaved(false) },
                )
            }
        }

        composeTestRule.onNodeWithText(context.getString(R.string.memory_screen_save)).performClick()

        assertFalse("a failed save must keep the editor open", dismissed.get())
        composeTestRule.onNodeWithText("Edit memory").assertExists()
        composeTestRule.onNodeWithText("Draft memory").assertExists()
    }
}
