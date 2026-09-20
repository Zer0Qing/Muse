package io.zer0.muse.ui.common.feedback

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MuseDialogTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun dialogRendersFullScreenScrimAndCardContent() {
        composeTestRule.setContent {
            MaterialTheme {
                MuseDialog(
                    onDismissRequest = {},
                    title = "Modal title",
                    content = { androidx.compose.material3.Text("Modal body") },
                    onConfirm = null,
                    dismissText = null,
                )
            }
        }

        composeTestRule.onNodeWithTag(MUSE_DIALOG_SCRIM_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithText("Modal title").assertIsDisplayed()
        composeTestRule.onNodeWithText("Modal body").assertIsDisplayed()
    }
}
