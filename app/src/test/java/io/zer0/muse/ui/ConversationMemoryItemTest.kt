package io.zer0.muse.ui

import io.zer0.memory.summary.SessionSummaryManager
import io.zer0.muse.data.session.SessionEntity
import io.zer0.muse.ui.memory.MemoryItem
import io.zer0.muse.ui.memory.buildMemoryStreamItems
import io.zer0.muse.ui.memory.matchesConversationMemoryFilter
import io.zer0.muse.ui.memory.projectConversationMemoryItems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationMemoryItemTest {

    @Test
    fun projectionUsesSessionTitleAndPreservesMemoryOwnership() {
        val summaries = listOf(
            SessionSummaryManager.SummaryData(
                sessionId = "session-1",
                createdAt = "2026-10-01T00:00:00Z",
                updatedAt = "2026-10-02T00:00:00Z",
                summary = "A conversation summary",
                messageCount = 14,
                assistantId = "default",
                spaceId = "default",
            ),
            SessionSummaryManager.SummaryData(
                sessionId = "session-2",
                createdAt = "2026-10-01T00:00:00Z",
                updatedAt = "2026-10-02T01:00:00Z",
                summary = "A summary with no session row",
                messageCount = 4,
                assistantId = "assistant-2",
                spaceId = "work",
            ),
        )
        val sessionsById = mapOf(
            "session-1" to SessionEntity(
                id = "session-1",
                title = "Project decisions",
                createdAt = 1L,
                updatedAt = 2L,
            ),
        )

        val projected = projectConversationMemoryItems(
            summaries = summaries,
            sessionsById = sessionsById,
            fallbackTitle = { id -> "Chat ${id.take(8)}" },
        )

        assertEquals("Project decisions", projected[0].title)
        assertEquals("Chat session-", projected[1].title)
        assertEquals("session-1", projected[0].sessionId)
        assertEquals("assistant-2", projected[1].assistantId)
        assertEquals("work", projected[1].spaceId)
        assertEquals(14, projected[0].messageCount)
        assertEquals("main", projected[0].toMemoryItem().scope)
        assertEquals("assistant-2", projected[1].toMemoryItem().scope)
    }

    @Test
    fun summaryScopeAndSpaceFilteringKeepsAssistantMemoriesSeparate() {
        val main = summary(sessionId = "main", assistantId = "default", spaceId = "default")
        val child = summary(sessionId = "child", assistantId = "assistant-2", spaceId = "work")

        assertTrue(main.matchesConversationMemoryFilter(scope = "main", spaceId = "default"))
        assertFalse(child.matchesConversationMemoryFilter(scope = "main", spaceId = "work"))
        assertTrue(child.matchesConversationMemoryFilter(scope = "assistant-2", spaceId = "work"))
        assertTrue(child.matchesConversationMemoryFilter(scope = null, spaceId = "work"))
        assertFalse(child.matchesConversationMemoryFilter(scope = null, spaceId = "default"))
    }

    @Test
    fun memoryStreamContainsFactsAndConversationSummariesInUpdatedOrder() {
        val fact = MemoryItem(
            id = "42",
            title = "User fact",
            content = "User fact",
            source = "Fact",
            createdAt = "2026-10-01T00:00:00Z",
        )
        val summary = MemoryItem(
            id = "session-1",
            title = "Chat title",
            content = "Conversation summary",
            source = "Summary",
            time = "2026-10-02T00:00:00Z",
        )

        val items = buildMemoryStreamItems(listOf(fact), listOf(summary))

        assertEquals(listOf("Summary", "Fact"), items.map { it.source })
        assertEquals(listOf("session-1", "42"), items.map { it.id })
    }

    private fun summary(sessionId: String, assistantId: String, spaceId: String) = SessionSummaryManager.SummaryData(
        sessionId = sessionId,
        createdAt = "2026-10-01T00:00:00Z",
        updatedAt = "2026-10-02T00:00:00Z",
        summary = "Summary",
        messageCount = 2,
        assistantId = assistantId,
        spaceId = spaceId,
    )
}
