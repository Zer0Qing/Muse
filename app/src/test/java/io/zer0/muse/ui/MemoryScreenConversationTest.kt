package io.zer0.muse.ui

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MemoryScreenConversationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun conversationMemoryRowOpensItsSourceSession() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val openedSessionId = AtomicReference<String?>(null)
        val item = MemoryItem(
            id = "session-42",
            title = "Planning",
            content = "The assistant promised to continue.",
            source = "Summary",
            sessionId = "session-42",
        )
        composeTestRule.setContent {
            MaterialTheme {
                MemoryFactRow(
                    item = item,
                    onOpenSession = { openedSessionId.set(it) },
                )
            }
        }

        composeTestRule
            .onNodeWithContentDescription(context.getString(R.string.memory_open_conversation))
            .performClick()

        assertEquals("session-42", openedSessionId.get())
    }
}
