package io.zer0.muse.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.muse.data.ChatPreferences
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ChatRenderContinuationRowTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `continuation reports a search hit with its own semantic tag`() {
        val content = "prefix needle " + "body ".repeat(10_000)
        val message = UIMessage(role = MessageRole.ASSISTANT, content = content)
        val row =
            ChatMessageRenderRows.project(
                message = message,
                isStreaming = false,
                hasRichContent = false,
            ).first { it.chunk != null }

        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(360.dp)) {
                    ChatRenderContinuationRow(
                        row = row,
                        chatPreferences = ChatPreferences(),
                        highlightText = "needle",
                        onHtmlPreview = {},
                        onCardAction = null,
                    )
                }
            }
        }

        val highlighted =
            compose.onAllNodes(hasStateDescription("search-highlighted"), useUnmergedTree = true)
                .fetchSemanticsNodes()
        assertEquals(1, highlighted.size)
        compose.onNodeWithText("needle", substring = true, useUnmergedTree = true)
            .assertExists()
    }

    @Test
    fun `continuation without a query has no highlight semantic`() {
        val content = "prefix needle " + "body ".repeat(10_000)
        val message = UIMessage(role = MessageRole.ASSISTANT, content = content)
        val row =
            ChatMessageRenderRows.project(
                message = message,
                isStreaming = false,
                hasRichContent = false,
            ).first { it.chunk != null }

        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(360.dp)) {
                    ChatRenderContinuationRow(
                        row = row,
                        chatPreferences = ChatPreferences(),
                        highlightText = null,
                        onHtmlPreview = {},
                        onCardAction = null,
                    )
                }
            }
        }

        val highlighted =
            compose.onAllNodes(hasStateDescription("search-highlighted"), useUnmergedTree = true)
                .fetchSemanticsNodes()
        assertEquals(0, highlighted.size)
    }
}
