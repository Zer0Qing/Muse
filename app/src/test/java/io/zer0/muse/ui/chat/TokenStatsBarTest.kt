package io.zer0.muse.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
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
class TokenStatsBarTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun tokenStatsBar_rendersMessageAndUsage() {
        composeTestRule.setContent {
            MaterialTheme {
                TokenStatsBar(
                    messageText = "hello",
                    historyTokens = 100,
                    contextWindow = 1000,
                )
            }
        }
        composeTestRule.onNodeWithText("10%", substring = true).assertExists()
    }
}
