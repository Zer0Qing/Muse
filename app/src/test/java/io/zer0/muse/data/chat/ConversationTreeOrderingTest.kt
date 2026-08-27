package io.zer0.muse.data.chat

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationTreeOrderingTest {
    @Test
    fun `snapshot placeholder is never merged into real messages`() {
        val realUser = UIMessage(
            id = Uuid.random(),
            role = MessageRole.USER,
            content = "真实问题",
            createdAt = 100L,
            seq = 1L,
        )
        val realAssistant = UIMessage(
            id = Uuid.random(),
            role = MessageRole.ASSISTANT,
            content = "真实回答",
            createdAt = 101L,
            seq = 2L,
            parentGroupId = realUser.id.toString(),
            variantGroupId = "assistant-group",
        )
        val snapshotPlaceholder = UIMessage(
            id = Uuid.random(),
            role = MessageRole.USER,
            content = "",
            createdAt = 0L,
            variantGroupId = "old-group",
        )

        val merged = mergeRebuildMessages(
            ConversationTree.build(listOf(snapshotPlaceholder)),
            listOf(realAssistant, realUser),
        )

        assertEquals(listOf(realUser.id, realAssistant.id), merged.map { it.id })
    }

    @Test
    fun `message ordering uses stable sequence before createdAt`() {
        val first = UIMessage(
            id = Uuid.random(), role = MessageRole.USER, content = "第一轮",
            createdAt = 9_999L, seq = 1L,
        )
        val second = UIMessage(
            id = Uuid.random(), role = MessageRole.ASSISTANT, content = "第二条",
            createdAt = 1L, seq = 2L,
        )

        assertEquals(
            listOf(first.id, second.id),
            orderConversationMessages(listOf(second, first)).map { it.id },
        )
    }
}
