package io.zer0.muse.data.conversation

import io.zer0.muse.data.session.MuseDb
import kotlinx.coroutines.flow.first

/**
 * Creates the first logical timeline for an existing conversation. Re-running
 * with the same selection identity is idempotent and leaves message rows
 * untouched.
 */
class LegacyConversationMigrator(
    private val database: MuseDb,
    private val store: RoomConversationStore,
) {
    suspend fun ensureImported(sessionId: String, selectionSetId: String): String {
        require(database.sessionDao().getById(sessionId) != null) { "session not found: $sessionId" }
        val existing = database.conversationSelectionDao().find(selectionSetId)
        if (existing != null) return existing.selectionSetId
        val messages = database.messageDao().observeBySession(sessionId).first()
        val slots = LegacySlotImporter.import(sessionId, messages)
        store.importLegacySlots(sessionId, selectionSetId, slots)
        return selectionSetId
    }
}
