package io.zer0.muse.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 回归锁定设置页唯一滚动容器的有限高度策略。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsSubPagesTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `uses parent finite height when available`() {
        assertEquals(640.dp, boundedSettingsScrollHeight(640.dp, 800.dp))
    }

    @Test
    fun `falls back to window height when parent is unbounded`() {
        assertEquals(800.dp, boundedSettingsScrollHeight(Dp.Infinity, 800.dp))
    }

    @Test
    fun `falls back to one dp when both constraints are unusable`() {
        assertEquals(1.dp, boundedSettingsScrollHeight(Dp.Infinity, Dp.Unspecified))
    }

    @Test
    fun `scaffold composes and scrolls to the last setting item`() {
        composeTestRule.setContent {
            MaterialTheme {
                SettingsSubPageScaffold(
                    title = "Assistant basic",
                    onBack = {},
                ) {
                    repeat(40) { index ->
                        item {
                            androidx.compose.material3.Text(
                                text = "setting-$index",
                                modifier = Modifier,
                            )
                        }
                    }
                }
            }
        }

        // A long list must remain composable and scrollable. The target starts off-screen,
        // so scroll the tagged LazyColumn itself; this exercises lazy composition,
        // measurement and recomposition at the bottom of the list.
        composeTestRule
            .onNodeWithTag(SETTINGS_SCROLL_CONTAINER_TAG)
            .performScrollToNode(hasText("setting-39"))
        composeTestRule.onNodeWithText("setting-39").assertIsDisplayed()
    }
}
