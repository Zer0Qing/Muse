package io.zer0.muse.ui.memory

import io.zer0.memory.summary.SessionSummaryManager
import io.zer0.muse.data.session.SessionEntity

internal data class ConversationMemoryItem(
    val sessionId: String,
    val title: String,
    val assistantId: String,
    val spaceId: String,
    val updatedAt: String,
    val messageCount: Int,
    val summary: String,
) {
    fun toMemoryItem(): MemoryItem = MemoryItem(
        id = sessionId,
        title = title,
        content = summary,
        time = updatedAt,
        source = "Summary",
        sessionId = sessionId,
        createdAt = updatedAt,
        scope = if (assistantId == "default") "main" else assistantId,
        spaceId = spaceId,
    )
}

internal fun projectConversationMemoryItems(
    summaries: List<SessionSummaryManager.SummaryData>,
    sessionsById: Map<String, SessionEntity>,
    fallbackTitle: (String) -> String,
): List<ConversationMemoryItem> = summaries.map { summary ->
    val sessionId = summary.sessionId
    val assistantId = summary.assistantId.ifBlank { "default" }
    ConversationMemoryItem(
        sessionId = sessionId,
        title = sessionsById[sessionId]?.title?.takeIf { it.isNotBlank() } ?: fallbackTitle(sessionId),
        assistantId = assistantId,
        spaceId = summary.spaceId.ifBlank { "default" },
        updatedAt = summary.updatedAt,
        messageCount = summary.messageCount,
        summary = summary.summary,
    )
}

internal fun SessionSummaryManager.SummaryData.matchesConversationMemoryFilter(scope: String?, spaceId: String): Boolean {
    val ownerId = assistantId.ifBlank { "default" }
    val matchesScope = when (scope) {
        null -> true
        "main" -> ownerId == "default"
        else -> ownerId == scope
    }
    return matchesScope && this.spaceId.ifBlank { "default" } == spaceId
}

internal fun buildMemoryStreamItems(facts: List<MemoryItem>, conversationMemory: List<MemoryItem>): List<MemoryItem> =
    (facts + conversationMemory).sortedByDescending { it.createdAt ?: it.time.orEmpty() }
