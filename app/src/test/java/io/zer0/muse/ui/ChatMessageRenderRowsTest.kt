package io.zer0.muse.ui

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.muse.data.ChatPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMessageRenderRowsTest {
    @Test
    fun `completed long body becomes a header body chunks and footer`() {
        val content = "long response ".repeat(2_000)
        val message = UIMessage(role = MessageRole.ASSISTANT, content = content)

        val rows =
            ChatMessageRenderRows.project(
                message = message,
                isStreaming = false,
                hasRichContent = false,
            )

        assertTrue(rows.size > 1)
        assertEquals("${message.id}:footer", rows.last().key)
        assertEquals(null, rows.last().chunk)
        assertEquals(ChatMessageRenderRowKind.Footer, rows.last().kind)
        assertFalse("footer must not render the full body", rows.last().rendersMessageBody)
        assertTrue(rows.filter { it.chunk != null }.size > 1)
        assertEquals(content, rows.filter { it.chunk != null }.joinToString("") { it.chunk!!.text })
        assertEquals(content, rows.last().message?.content)
        assertEquals(rows.map { it.key }.distinct().size, rows.size)
        assertTrue(rows.all { it.message != null })
        assertEquals(rows.size, rows.last().messageRowCount)
        assertEquals(ChatMessageRenderRowKind.Header, rows.first().kind)
        assertTrue(rows.drop(1).dropLast(1).all { it.kind == ChatMessageRenderRowKind.Body })
    }

    @Test
    fun `interactive completed message remains one row`() {
        val message =
            UIMessage(
                role = MessageRole.ASSISTANT,
                content = "long response ".repeat(2_000),
                toolCallInfo = io.zer0.ai.core.ToolCallInfo("show_card", "{}", "ok", true),
            )

        val rows =
            ChatMessageRenderRows.project(
                message = message,
                isStreaming = false,
                hasRichContent = true,
            )

        assertEquals(listOf("${message.id}:message"), rows.map { it.key })
    }

    @Test
    fun `quoted attachments and translations keep the original interactive bubble`() {
        val message =
            UIMessage(
                role = MessageRole.ASSISTANT,
                content = "long response ".repeat(2_000),
                quotedContent = "quoted",
                attachments = listOf(io.zer0.ai.core.AttachmentRef("a1", "report.pdf")),
                translations = mapOf("English" to "translated"),
            )

        val rows =
            ChatMessageRenderRows.project(
                message = message,
                isStreaming = false,
                hasRichContent = true,
            )

        assertEquals(listOf("${message.id}:message"), rows.map { it.key })
    }

    @Test
    fun `streaming message remains one row while its content changes`() {
        val message = UIMessage(role = MessageRole.ASSISTANT, content = "long response ".repeat(2_000))

        val rows =
            ChatMessageRenderRows.project(
                message = message,
                isStreaming = true,
                hasRichContent = false,
            )

        assertEquals(listOf("${message.id}:message"), rows.map { it.key })
    }

    @Test
    fun `all body chunks belong to lazy continuation rows`() {
        val message = UIMessage(role = MessageRole.ASSISTANT, content = "long response ".repeat(2_000))

        val rows =
            ChatMessageRenderRows.project(
                message = message,
                isStreaming = false,
                hasRichContent = false,
            )

        assertTrue(rows.last().ownsInteraction)
        assertTrue(rows.last().chunk == null)
        assertFalse(rows.last().rendersMessageBody)
        assertTrue(rows.first().chunk == null)
        assertTrue(rows.drop(1).dropLast(1).all { it.chunk != null })
        assertTrue(rows.drop(1).dropLast(1).first().isFirstChunk)
        assertTrue(rows.drop(1).dropLast(1).none { it.ownsInteraction })
        val chunks = rows.filter { it.chunk != null }.map { it.chunk!! }
        assertEquals(chunks.indices.toList(), chunks.map { it.index })
        assertEquals(chunks.zipWithNext().all { (left, right) -> left.sourceEnd == right.sourceStart }, true)
    }

    @Test
    fun `bodyless shell retains the full source for copy quote and regenerate`() {
        val content = "long response ".repeat(2_000)
        val message = UIMessage(role = MessageRole.ASSISTANT, content = content)

        val shell =
            ChatMessageRenderRows.project(
                message = message,
                isStreaming = false,
                hasRichContent = false,
            ).last()

        assertEquals(message, shell.message)
        assertEquals(content, shell.message?.content)
        assertFalse(shell.rendersMessageBody)
    }

    @Test
    fun `projected rows retain their source message index`() {
        val message = UIMessage(role = MessageRole.ASSISTANT, content = "long response ".repeat(2_000))

        val rows =
            ChatMessageRenderRows.project(
                message = message,
                isStreaming = false,
                hasRichContent = false,
                messageIndex = 7,
            )

        assertTrue(rows.size > 1)
        assertEquals(List(rows.size) { 7 }, rows.map { it.messageIndex })
    }

    @Test
    fun `streaming split requires both a streaming message and last position`() {
        val message = UIMessage(role = MessageRole.ASSISTANT, content = "long response ".repeat(2_000))

        val notLast =
            ChatMessageRenderRows.project(
                message = message,
                isStreaming = true,
                hasRichContent = false,
                isLastMessage = false,
            )
        val last =
            ChatMessageRenderRows.project(
                message = message,
                isStreaming = true,
                hasRichContent = false,
                isLastMessage = true,
            )

        assertEquals(listOf("${message.id}:message"), notLast.map { it.key })
        assertEquals(listOf("${message.id}:message"), last.map { it.key })
    }

    @Test
    fun `citation messages retain the complete rendering pipeline`() {
        val content = "long response ".repeat(2_000)
        val cited =
            UIMessage(
                role = MessageRole.ASSISTANT,
                content = content,
                citationUrls = listOf("https://example.com/source"),
            )

        val citedRows =
            ChatMessageRenderRows.project(
                message = cited,
                isStreaming = false,
                hasRichContent = false,
            )

        assertEquals(listOf("${cited.id}:message"), citedRows.map { it.key })
        assertTrue(citedRows.single().rendersMessageBody)
    }

    @Test
    fun `plain completed assistant message can use the bodyless interaction shell`() {
        val message =
            UIMessage(
                role = MessageRole.ASSISTANT,
                content = "plain response ".repeat(2_000),
            )

        val rows =
            ChatMessageRenderRows.project(
                message = message,
                isStreaming = false,
                hasRichContent = false,
            )

        assertTrue(rows.size > 1)
        assertFalse(rows.last().rendersMessageBody)
    }

    @Test
    fun `header precedes the body chunks and footer follows the chunks it controls`() {
        val message =
            UIMessage(
                role = MessageRole.ASSISTANT,
                content = "plain response ".repeat(2_000),
            )

        val rows =
            ChatMessageRenderRows.project(
                message = message,
                isStreaming = false,
                hasRichContent = false,
            )

        assertEquals(ChatMessageRenderRowKind.Header, rows.first().kind)
        assertTrue(rows.first().rendersMessageBody.not())
        assertTrue(rows[1].chunk != null)
        assertTrue(rows.last().ownsInteraction)
        assertEquals(ChatMessageRenderRowKind.Footer, rows.last().kind)
        assertEquals("${message.id}:footer", rows.last().key)
    }

    @Test
    fun `only header and footer rows run message shell logic`() {
        val message =
            UIMessage(
                role = MessageRole.ASSISTANT,
                content = "plain response ".repeat(2_000),
            )

        val rows =
            ChatMessageRenderRows.project(
                message = message,
                isStreaming = false,
                hasRichContent = false,
            )

        assertEquals(1, rows.count { it.kind == ChatMessageRenderRowKind.Header })
        assertEquals(1, rows.count { it.kind == ChatMessageRenderRowKind.Footer })
        assertTrue(rows.filter { it.kind == ChatMessageRenderRowKind.Body }.isNotEmpty())
        assertTrue(
            rows.filter { it.kind != ChatMessageRenderRowKind.Body }
                .all { it.kind == ChatMessageRenderRowKind.Header || it.kind == ChatMessageRenderRowKind.Footer },
        )
    }

    @Test
    fun `single row message is a complete header body footer row`() {
        val message = UIMessage(role = MessageRole.ASSISTANT, content = "short")

        val row =
            ChatMessageRenderRows.project(
                message = message,
                isStreaming = false,
                hasRichContent = false,
            ).single()

        assertEquals(ChatMessageRenderRowKind.Complete, row.kind)
    }

    @Test
    fun `selection mode keeps the whole message in one render row`() {
        val message =
            UIMessage(
                role = MessageRole.ASSISTANT,
                content = "plain response ".repeat(2_000),
            )

        val rows =
            ChatMessageRenderRows.project(
                message = message,
                isStreaming = false,
                hasRichContent = false,
                allowSplitting = false,
            )

        assertEquals(listOf("${message.id}:message"), rows.map { it.key })
        assertEquals(ChatMessageRenderRowKind.Complete, rows.single().kind)
        assertTrue(rows.single().rendersMessageBody)
    }

    @Test
    fun `footer decision rejects a shell with no renderable footer content`() {
        val message =
            UIMessage(
                role = MessageRole.ASSISTANT,
                content = "plain response ".repeat(2_000),
            )

        val hasFooter =
            ChatMessageRenderRows.hasRenderableFooter(
                ChatMessageRenderRows.FooterFacts(
                message = message,
                isStreaming = false,
                isTranslating = false,
                isLastAssistant = false,
                chatPreferences =
                    ChatPreferences(
                        quickActionOrder = emptyList(),
                        quickActionCopy = false,
                        quickActionReasoning = false,
                        quickActionTranslate = false,
                        quickActionShare = false,
                        quickActionRegenerate = false,
                        quickActionDelete = false,
                    ),
                showsModelName = false,
                showsTokenStats = false,
                branchCount = 1,
                canContinue = false,
                )
            )

        assertFalse(hasFooter)
    }

    @Test
    fun `footer decision is only needed for messages that can split`() {
        val short = UIMessage(role = MessageRole.ASSISTANT, content = "short")
        val rich =
            UIMessage(
                role = MessageRole.ASSISTANT,
                content = "long response ".repeat(2_000),
                reasoning = "reasoning",
            )

        assertFalse(ChatMessageRenderRows.needsFooterDecision(short, isStreaming = false, hasRichContent = false))
        assertFalse(ChatMessageRenderRows.needsFooterDecision(rich, isStreaming = false, hasRichContent = true))
        assertTrue(
            ChatMessageRenderRows.needsFooterDecision(
                UIMessage(role = MessageRole.ASSISTANT, content = "long response ".repeat(2_000)),
                isStreaming = false,
                hasRichContent = false,
            ),
        )
    }

    @Test
    fun `projection can omit an optional empty footer`() {
        val message =
            UIMessage(
                role = MessageRole.ASSISTANT,
                content = "plain response ".repeat(2_000),
            )

        val rows =
            ChatMessageRenderRows.project(
                message = message,
                isStreaming = false,
                hasRichContent = false,
                hasRenderableFooter = false,
            )

        assertEquals(listOf("${message.id}:message"), rows.map { it.key })
        assertEquals(ChatMessageRenderRowKind.Complete, rows.single().kind)
    }

}
