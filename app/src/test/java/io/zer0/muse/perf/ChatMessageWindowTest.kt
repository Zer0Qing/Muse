package io.zer0.muse.perf

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMessageWindowTest {
    @Test
    fun `bounded window keeps newest renderable messages in order`() {
        val messages = (1..10).map { index ->
            UIMessage(
                role = if (index % 2 == 0) MessageRole.ASSISTANT else MessageRole.USER,
                content = "message-$index",
            )
        }

        val window = ChatMessageWindow.project(
            messages = messages,
            pageSize = 4,
            isRenderable = { true },
        )

        assertEquals(listOf("message-7", "message-8", "message-9", "message-10"), window.visible.map { it.content })
        assertEquals(10, window.allRenderableCount)
        assertEquals(10, window.projectedRenderableCount)
        assertTrue(window.hasHiddenRenderableMessages)
    }

    @Test
    fun `context boundary trims older visible history before paging`() {
        val messages = (1..6).map { index ->
            UIMessage(role = MessageRole.USER, content = "message-$index")
        }

        val window = ChatMessageWindow.project(
            messages = messages,
            pageSize = 4,
            isRenderable = { true },
            boundaryMessageId = messages[2].id.toString(),
            includeBeforeBoundary = false,
        )

        assertEquals(listOf("message-3", "message-4", "message-5", "message-6"), window.visible.map { it.content })
        assertEquals(6, window.allRenderableCount)
        assertEquals(4, window.projectedRenderableCount)
    }

    @Test
    fun `id index is built once from the same source as visible messages`() {
        val messages = (1..3).map { index ->
            UIMessage(role = MessageRole.USER, content = "message-$index")
        }

        val window = ChatMessageWindow.project(
            messages = messages,
            pageSize = 10,
            isRenderable = { true },
        )

        assertEquals(messages.map { it.id.toString() }.toSet(), window.byId.keys)
        assertEquals(messages[1], window.byId[messages[1].id.toString()])
    }

    @Test
    fun `translation source outside the visible window remains indexed`() {
        val source = UIMessage(role = MessageRole.ASSISTANT, content = "source")
        val messages = listOf(
            source,
            UIMessage(role = MessageRole.USER, content = "message-1"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                content = "translation",
                translationSourceId = source.id.toString(),
            ),
        )

        val window = ChatMessageWindow.project(
            messages = messages,
            pageSize = 1,
            isRenderable = { true },
        )

        assertEquals(listOf("translation"), window.visible.map { it.content })
        assertEquals(source, window.byId[source.id.toString()])
    }

}
