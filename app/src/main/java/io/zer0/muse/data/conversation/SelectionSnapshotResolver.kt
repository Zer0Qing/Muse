package io.zer0.muse.data.conversation

/**
 * Resolves the selection snapshot that a display window must read. A caller
 * that has an explicit snapshot keeps it; legacy callers get one stable empty
 * snapshot per session until a migration writes a richer selection.
 */
class SelectionSnapshotResolver(
    private val store: RoomConversationStore,
    private val legacyMigrator: LegacyConversationMigrator,
) {
    suspend fun resolve(sessionId: String, explicitSelectionSetId: String?): String {
        require(sessionId.isNotBlank()) { "session id must not be blank" }
        if (!explicitSelectionSetId.isNullOrBlank()) {
            return legacyMigrator.ensureImported(sessionId, explicitSelectionSetId)
        }
        val existing = store.findLatestSelection(sessionId)
        if (existing != null) return legacyMigrator.ensureImported(sessionId, existing.selectionSetId)
        val stableId = "legacy:$sessionId"
        return legacyMigrator.ensureImported(sessionId, stableId)
    }
}
