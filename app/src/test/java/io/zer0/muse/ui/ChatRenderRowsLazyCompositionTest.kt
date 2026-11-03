package io.zer0.muse.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.muse.data.ChatPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ChatRenderRowsLazyCompositionTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `projected chunks compose lazily inside the message list`() {
        val content =
            (0 until 80).joinToString("\n\n") { index ->
                "lazy projected paragraph $index\n\n" + "padding ".repeat(150)
            }
        val message = UIMessage(role = MessageRole.ASSISTANT, content = content)
        val rows =
            ChatMessageRenderRows.project(
                message = message,
                isStreaming = false,
                hasRichContent = false,
            )
        assertTrue(rows.size > 2)

        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(360.dp).height(400.dp)) {
                    ProjectedRows(rows)
                }
            }
        }

        val composed =
            compose.onAllNodes(
                hasText("lazy projected paragraph ", substring = true),
                useUnmergedTree = true,
            ).fetchSemanticsNodes().size
        assertEquals(true, composed > 0)
        assertEquals(true, composed < rows.size)
        compose.onNodeWithText("lazy projected paragraph 79", useUnmergedTree = true)
            .assertIsNotDisplayed()
    }

    @Test
    fun `continuation component renders its markdown chunk`() {
        val message =
            UIMessage(
                role = MessageRole.ASSISTANT,
                content = "prefix " + "body ".repeat(10_000),
            )
        val continuedRow =
            ChatMessageRenderRows.project(
                message = message,
                isStreaming = false,
                hasRichContent = false,
            ).first { it.kind == ChatMessageRenderRowKind.Body }
        val expected = continuedRow.chunk!!.text.take(24)

        compose.setContent {
            MaterialTheme {
                ChatRenderContinuationRow(
                    row = continuedRow,
                    chatPreferences = ChatPreferences(),
                    highlightText = null,
                    onHtmlPreview = {},
                    onCardAction = null,
                )
            }
        }

        compose.onNodeWithText(expected, substring = true, useUnmergedTree = true).assertExists()
    }

    @Composable
    private fun ProjectedRows(rows: List<ChatMessageRenderRow>) {
        LazyColumn {
            items(rows, key = { it.key }) { row ->
                val text = row.chunk?.text.orEmpty()
                Text(text)
            }
        }
    }
}
