package io.zer0.muse.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.zer0.muse.R
import io.zer0.muse.tools.BrowserManager
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BrowserCapsuleTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun displayState_exposesNotStartedLoadingAndReadyStates() {
        assertEquals(
            BrowserDisplayState.NOT_STARTED,
            browserDisplayState(isActive = false, isLoading = false, url = ""),
        )
        assertEquals(
            BrowserDisplayState.LOADING,
            browserDisplayState(isActive = true, isLoading = true, url = ""),
        )
        assertEquals(
            BrowserDisplayState.BLANK_PAGE,
            browserDisplayState(isActive = true, isLoading = false, url = "about:blank"),
        )
        assertEquals(
            BrowserDisplayState.READY,
            browserDisplayState(isActive = true, isLoading = false, url = "https://example.com"),
        )
        assertEquals(false, shouldShowBrowserCapsule(BrowserDisplayState.NOT_STARTED))
        assertEquals(true, shouldShowBrowserCapsule(BrowserDisplayState.LOADING))
        assertEquals(true, shouldShowBrowserCapsule(BrowserDisplayState.BLANK_PAGE))
        assertEquals(true, shouldShowBrowserCapsule(BrowserDisplayState.READY))
    }

    @Test
    fun pageLabel_prefersTitleThenUsesHostAndHidesBlankUrl() {
        assertEquals("Article", browserPageLabel(" Article ", "https://example.com/article"))
        assertEquals("example.com", browserPageLabel("", "https://www.example.com/article"))
        assertEquals("", browserPageLabel("", "about:blank"))
    }

    @Test
    fun capsule_isHiddenBeforeAiUsesBrowser() {
        val manager = mockk<BrowserManager>()
        every { manager.isActive } returns MutableStateFlow(false)
        every { manager.isLoading } returns MutableStateFlow(false)
        every { manager.currentUrl } returns MutableStateFlow("")
        every { manager.currentTitle } returns MutableStateFlow("")

        composeTestRule.setContent {
            MaterialTheme {
                BrowserStatusCapsule(manager = manager)
            }
        }

        composeTestRule
            .onAllNodesWithText(
                ApplicationProvider.getApplicationContext<android.content.Context>()
                    .getString(R.string.browser_status_not_started),
            )
            .assertCountEquals(0)
    }
}
