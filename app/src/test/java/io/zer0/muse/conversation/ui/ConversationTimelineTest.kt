package io.zer0.muse.conversation.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import io.zer0.muse.conversation.core.RenderBlock
import io.zer0.muse.conversation.core.SlotKind
import io.zer0.muse.conversation.render.LegacyConversationProjector
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.ai.core.ToolCallInfo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ConversationTimelineTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `long message blocks are lazily composed`() {
        val blocks = List(120) { index ->
            RenderBlock.Paragraph("block-$index", index, index + 1, "timeline-paragraph-$index")
        }
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(360.dp).height(400.dp)) {
                    ConversationTimeline(rows = blocks)
                }
            }
        }

        val composed = compose.onAllNodes(hasText("timeline-paragraph-", substring = true), useUnmergedTree = true)
            .fetchSemanticsNodes().size
        assertEquals(true, composed > 0)
        assertEquals(true, composed < 20)
        assertEquals(120, blocks.size)
        compose.onNodeWithText("timeline-paragraph-119", useUnmergedTree = true)
            .assertIsNotDisplayed()
    }

    @Test
    fun `rich legacy slots remain independently rendered rows`() {
        val projection =
            LegacyConversationProjector.project(
                rowId = "slot-rich",
                message =
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    content = "body",
                    reasoning = "reasoning",
                    mood = "mood",
                    attachments = listOf(io.zer0.ai.core.AttachmentRef("a1", "report.pdf")),
                    toolCallInfo = ToolCallInfo("show_card", "{}", "ok", true),
                    artifactIds = listOf("artifact-1"),
                ),
                complete = true,
            )
        val rows =
            ConversationTimelineRows.project(
                slotId = projection.slotId,
                kind = SlotKind.Assistant,
                projection = projection,
            )
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(360.dp).height(400.dp)) {
                    ConversationRichTimeline(rows = rows)
                }
            }
        }

        assertEquals(
            listOf("reasoning", "mood", "report.pdf", "show_card", "artifact-1"),
            rows.filterIsInstance<ConversationTimelineRow.Rich>().map { it.text },
        )
        assertEquals(
            listOf("body"),
            rows.filterIsInstance<ConversationTimelineRow.Markdown>().map { it.block.text },
        )
        compose.onNodeWithText("reasoning", useUnmergedTree = true).assertExists()
        compose.onAllNodesWithText("body", useUnmergedTree = true).assertCountEquals(1)
        compose.onNodeWithText("report.pdf", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("show_card", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("artifact-1", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `rich rows follow semantic order and do not duplicate body text`() {
        val projection =
            LegacyConversationProjector.project(
                rowId = "slot-order",
                message =
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    content = "body paragraph",
                    reasoning = "reasoning",
                    mood = "mood",
                    reflection = "reflection",
                    imageUrls = listOf("file:///image.png"),
                    voiceUri = "file:///voice.m4a",
                    attachments = listOf(io.zer0.ai.core.AttachmentRef("a1", "report.pdf")),
                    toolCallInfo = ToolCallInfo("show_card", "{}", "ok", true),
                    artifactIds = listOf("artifact-1"),
                    translations = mapOf("English" to "translated body"),
                ),
                complete = true,
            )

        val rows =
            ConversationTimelineRows.project(
                slotId = projection.slotId,
                kind = SlotKind.Assistant,
                projection = projection,
            )

        assertEquals(
            listOf("reasoning", "mood", "reflection", "report.pdf", "show_card", "artifact-1", "translated body"),
            rows.filterIsInstance<ConversationTimelineRow.Rich>().map { it.text },
        )
        assertEquals(
            listOf("body paragraph"),
            rows.filterIsInstance<ConversationTimelineRow.Markdown>().map { it.block.text },
        )
        assertEquals(
            listOf(
                ConversationTimelineRow.Rich::class,
                ConversationTimelineRow.Rich::class,
                ConversationTimelineRow.Markdown::class,
                ConversationTimelineRow.Rich::class,
                ConversationTimelineRow.Rich::class,
                ConversationTimelineRow.Rich::class,
                ConversationTimelineRow.Rich::class,
                ConversationTimelineRow.Rich::class,
            ),
            rows.map { it::class },
        )
    }
}
