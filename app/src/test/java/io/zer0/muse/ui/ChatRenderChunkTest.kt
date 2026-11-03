package io.zer0.muse.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRenderChunkTest {
    @Test
    fun `long assistant body splits into bounded markdown chunks`() {
        val firstParagraph = "first paragraph ".repeat(520)
        val secondParagraph = "second paragraph ".repeat(520)
        val content = "$firstParagraph\n\n$secondParagraph"

        val chunks =
            ChatRenderChunks.project(
                messageId = "assistant-1",
                content = content,
                role = ChatRenderRole.Assistant,
                isStreaming = false,
                hasRichContent = false,
            )

        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.text.length <= ChatRenderChunks.maxChunkCharacters })
        assertEquals(content, chunks.joinToString("") { it.text })
        assertEquals(chunks.size, chunks.map { it.key }.distinct().size)
        assertEquals("assistant-1:body:0", chunks.first().key)
        assertEquals(chunks.indices.toList(), chunks.map { it.index })
        assertEquals(List(chunks.size) { chunks.size }, chunks.map { it.count })
        assertEquals(content.length, chunks.last().sourceEnd)
        assertEquals(
            chunks.zipWithNext().all { (left, right) -> left.sourceEnd == right.sourceStart },
            true,
        )
    }

    @Test
    fun `short interactive message stays one unit`() {
        val chunks =
            ChatRenderChunks.project(
                messageId = "assistant-2",
                content = "short body",
                role = ChatRenderRole.Assistant,
                isStreaming = false,
                hasRichContent = true,
            )

        assertEquals(listOf("short body"), chunks.map { it.text })
    }

    @Test
    fun `streaming assistant body stays in the streaming message unit`() {
        val chunks =
            ChatRenderChunks.project(
                messageId = "assistant-3",
                content = "streaming ".repeat(500),
                role = ChatRenderRole.Assistant,
                isStreaming = true,
                hasRichContent = false,
            )

        assertEquals(1, chunks.size)
    }

    @Test
    fun `long plain prose is the only content eligible for chunking`() {
        val plain =
            ChatRenderChunks.project(
                messageId = "plain",
                content = "paragraph ".repeat(2_000),
                role = ChatRenderRole.Assistant,
                isStreaming = false,
                hasRichContent = false,
            )
        val fenced =
            ChatRenderChunks.project(
                messageId = "fenced",
                content = "intro\n\n```kotlin\n" + "val answer = 42\n".repeat(1_000) + "```",
                role = ChatRenderRole.Assistant,
                isStreaming = false,
                hasRichContent = false,
            )
        val inlineEffect =
            ChatRenderChunks.project(
                messageId = "effect",
                content = "prefix " + "[glow]bright[/glow] ".repeat(1_000),
                role = ChatRenderRole.Assistant,
                isStreaming = false,
                hasRichContent = false,
            )

        assertTrue("plain prose may be split to bound measurement", plain.size > 1)
        assertEquals("a fenced block must stay in one semantic render unit", 1, fenced.size)
        assertEquals("inline effects must stay in the full bubble pipeline", 1, inlineEffect.size)
    }

    @Test
    fun `structured rich text stays in one semantic render unit`() {
        val longBody = "x".repeat(ChatRenderChunks.maxChunkCharacters * 2)
        val richContents =
            listOf(
                "<svg>$longBody</svg>",
                "<table><tr><td>$longBody</td></tr></table>",
                "$$\n$longBody\n$$",
                "| column |\n|---|\n| $longBody |",
                "[[sticker:cat]]\n$longBody",
            )

        richContents.forEach { content ->
            val chunks =
                ChatRenderChunks.project(
                    messageId = "rich",
                    content = content,
                    role = ChatRenderRole.Assistant,
                    isStreaming = false,
                    hasRichContent = false,
                )

            assertEquals("rich content must keep one render context: ${content.take(20)}", 1, chunks.size)
        }
    }
}
