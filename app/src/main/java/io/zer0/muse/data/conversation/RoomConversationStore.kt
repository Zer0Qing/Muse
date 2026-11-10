package io.zer0.muse.data.conversation

import androidx.room.withTransaction
import io.zer0.muse.data.session.MuseDb

sealed interface CommandStoreResult {
    data class Accepted(val receipt: ConversationCommandReceiptEntity) : CommandStoreResult

    data class Replay(val receipt: ConversationCommandReceiptEntity) : CommandStoreResult

    data class Conflict(val receipt: ConversationCommandReceiptEntity) : CommandStoreResult
}

data class ConversationWindowSlot(
    val slotId: String,
    val kind: String,
    val messageId: String,
)

class RoomConversationStore(
    private val database: MuseDb,
) {
    suspend fun commitCommand(receipt: ConversationCommandReceiptEntity): CommandStoreResult = database.withTransaction {
        val existing = database.conversationCommandReceiptDao().find(receipt.commandId)
        if (existing != null) {
            return@withTransaction if (existing.fingerprint == receipt.fingerprint) {
                CommandStoreResult.Replay(existing)
            } else {
                CommandStoreResult.Conflict(existing)
            }
        }
        database.conversationCommandReceiptDao().insert(receipt)
        CommandStoreResult.Accepted(receipt)
    }

    suspend fun releaseOutbox(commandId: String) {
        database.messageOutboxDao().deleteById(commandId)
    }

    suspend fun findReceipt(commandId: String): ConversationCommandReceiptEntity? = database.conversationCommandReceiptDao().find(commandId)

    suspend fun findLatestSelection(sessionId: String): ConversationSelectionEntity? =
        database.conversationSelectionDao().findBySession(sessionId)

    suspend fun saveSelection(
        selectionSetId: String,
        sessionId: String,
        selection: Map<String, String>,
        assistantSelection: Map<String, String> = emptyMap(),
    ) {
        database.conversationSelectionDao().insert(
            ConversationSelectionEntity(
                selectionSetId = selectionSetId,
                sessionId = sessionId,
                revision = System.currentTimeMillis(),
                userSelectionJson = ConversationSelectionCodec.encode(selection),
                assistantSelectionJson = ConversationSelectionCodec.encode(assistantSelection),
            ),
        )
    }

    suspend fun importLegacySlots(sessionId: String, selectionSetId: String, slots: List<ImportedSlot>) = database.withTransaction {
        require(slots.map { it.logicalPosition }.distinct().size == slots.size) {
            "imported slot positions must be unique"
        }
        val userSelection = slots.filter { it.role == "USER" }.associate { it.slotId to it.messageId }
        val assistantSelection =
            slots.filter { it.role == "ASSISTANT" }.associate { it.slotId to it.messageId }
        database.conversationSelectionDao().insert(
            ConversationSelectionEntity(
                selectionSetId = selectionSetId,
                sessionId = sessionId,
                revision = System.currentTimeMillis(),
                userSelectionJson = ConversationSelectionCodec.encode(userSelection),
                assistantSelectionJson = ConversationSelectionCodec.encode(assistantSelection),
            ),
        )
        slots.forEach { slot ->
            database.conversationDisplaySlotDao().upsert(
                ConversationDisplaySlotEntity(
                    slotId = slot.slotId,
                    sessionId = sessionId,
                    kind = slot.role,
                    parentSlotId = slot.parentSlotId,
                    logicalPosition = slot.logicalPosition,
                    messageId = slot.messageId,
                ),
            )
        }
    }

    suspend fun upsertSlot(
        sessionId: String,
        selectionSetId: String,
        slotId: String,
        kind: String,
        parentSlotId: String?,
        logicalPosition: Long,
        messageId: String,
    ) {
        require(database.conversationSelectionDao().find(selectionSetId) != null) {
            "selection set must exist before slots are registered"
        }
        database.conversationDisplaySlotDao().upsert(
            ConversationDisplaySlotEntity(
                slotId = slotId,
                sessionId = sessionId,
                kind = kind,
                parentSlotId = parentSlotId,
                logicalPosition = logicalPosition,
                messageId = messageId,
            ),
        )
    }

    suspend fun appendSlot(
        sessionId: String,
        selectionSetId: String,
        slotId: String,
        kind: String,
        parentSlotId: String?,
        messageId: String,
    ): ConversationDisplaySlotEntity {
        val selection = database.conversationSelectionDao().find(selectionSetId)
            ?: error("selection set must exist before slots are registered")
        require(selection.sessionId == sessionId) { "selection set belongs to another session" }
        val logicalPosition =
            database.conversationDisplaySlotDao().maxLogicalPosition(sessionId) + 1
        val slot =
            ConversationDisplaySlotEntity(
                slotId = slotId,
                sessionId = sessionId,
                kind = kind,
                parentSlotId = parentSlotId,
                logicalPosition = logicalPosition,
                messageId = messageId,
            )
        database.conversationDisplaySlotDao().upsert(slot)
        return slot
    }

    suspend fun readWindow(sessionId: String, selectionSetId: String, limit: Int): List<ConversationWindowSlot> {
        val selection = database.conversationSelectionDao().find(selectionSetId)
            ?: error("selection set not found: $selectionSetId")
        val selected =
            ConversationSelectionCodec.decode(selection.userSelectionJson) +
                ConversationSelectionCodec.decode(selection.assistantSelectionJson)
        return database.conversationDisplaySlotDao().readWindow(sessionId, limit).map { slot ->
            ConversationWindowSlot(
                slotId = slot.slotId,
                kind = slot.kind,
                messageId = selected[slot.slotId] ?: slot.messageId,
            )
        }
    }

    suspend fun readAround(sessionId: String, selectionSetId: String, slotId: String, limit: Int): List<ConversationWindowSlot> {
        val anchor = database.conversationDisplaySlotDao().find(slotId)
            ?: error("display slot not found: $slotId")
        require(anchor.sessionId == sessionId) { "display slot belongs to another session" }
        val beforeLimit = (limit - 1) / 2
        val afterLimit = limit - 1 - beforeLimit
        val before = database.conversationDisplaySlotDao()
            .readThrough(sessionId, anchor.logicalPosition, beforeLimit + 1)
            .asReversed()
        val after = database.conversationDisplaySlotDao()
            .readAfter(sessionId, anchor.logicalPosition, afterLimit)
        val selection = database.conversationSelectionDao().find(selectionSetId)
            ?: error("selection set not found: $selectionSetId")
        require(selection.sessionId == sessionId) { "selection set belongs to another session" }
        val selected =
            ConversationSelectionCodec.decode(selection.userSelectionJson) +
                ConversationSelectionCodec.decode(selection.assistantSelectionJson)
        return (before + anchor + after).distinctBy { it.slotId }.map { slot ->
            ConversationWindowSlot(
                slotId = slot.slotId,
                kind = slot.kind,
                messageId = selected[slot.slotId] ?: slot.messageId,
            )
        }
    }
}
