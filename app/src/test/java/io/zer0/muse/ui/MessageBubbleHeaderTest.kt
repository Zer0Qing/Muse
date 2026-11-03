package io.zer0.muse.ui

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.muse.data.ChatPreferences
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MessageBubbleHeaderTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `date separator is above the assistant identity row`() {
        val message = UIMessage(role = MessageRole.ASSISTANT, content = "body")

        compose.setContent {
            MaterialTheme {
                MessageBubbleHeader(
                    message = message,
                    showAvatar = true,
                    showTimestamp = true,
                    chatPreferences = ChatPreferences(),
                    assistant = null,
                    showDateSeparator = true,
                    showContextDivider = false,
                    contextCheckpointTotalCovered = 0,
                    contextDividerExpanded = false,
                    onToggleContextDivider = {},
                    modifier = Modifier.width(360.dp).height(160.dp),
                )
            }
        }

        val dateBounds = compose.onNodeWithTag(DateSeparator.TEST_TAG).getBoundsInRoot()
        val avatarBounds = compose.onNodeWithTag(MessageBubbleHeader.IDENTITY_TEST_TAG).getBoundsInRoot()

        assertTrue(
            "date separator and identity must be separate vertical rows",
            dateBounds.bottom <= avatarBounds.top,
        )
    }
}
