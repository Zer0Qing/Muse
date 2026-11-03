package io.zer0.muse.data.conversation

import androidx.room.withTransaction
import io.zer0.muse.conversation.core.CommandResult
import io.zer0.muse.conversation.core.ConversationCommand
import io.zer0.muse.data.session.ConversationTurnEntity
import io.zer0.muse.data.session.MessageEntity
import io.zer0.muse.data.session.MessageOutboxEntity
import io.zer0.muse.data.session.MuseDb
import java.security.MessageDigest
import kotlin.uuid.Uuid

data class SubmitIdentity(
    val userMessageId: String,
    val assistantMessageId: String,
    val turnId: String,
) {
    init {
        require(userMessageId.isNotBlank()) { "user message id must not be blank" }
        require(assistantMessageId.isNotBlank()) { "assistant message id must not be blank" }
        require(turnId.isNotBlank()) { "turn id must not be blank" }
        require(userMessageId != assistantMessageId) { "user and assistant ids must differ" }
    }
}

/**
 * Atomically accepts a main-chat submit command. The command receipt and all
 * durable work records are written in one transaction so a crash cannot leave
 * an accepted command that is safe to replay with new identities.
 */
class RoomSubmitCommandPort(
    private val database: MuseDb,
    private val store: RoomConversationStore,
    private val identityFactory: (ConversationCommand.Submit) -> SubmitIdentity = ::newSubmitIdentity,
) {
    suspend fun accept(
        command: ConversationCommand.Submit,
    ): CommandResult.Accepted = database.withTransaction {
        val fingerprint = fingerprint(command)
        val existing = store.findReceipt(command.commandId)
        if (existing != null) {
            require(existing.fingerprint == fingerprint) {
                "command id was reused with different content"
            }
            return@withTransaction CommandResult.Accepted(existing.messageId, existing.turnId)
        }
        require(database.sessionDao().getById(command.sessionId) != null) {
            "session does not exist: ${command.sessionId}"
        }
        if (database.conversationSelectionDao().find(command.selectionSetId) == null) {
            store.saveSelection(command.selectionSetId, command.sessionId, emptyMap())
        }
        val identity = identityFactory(command)
        val now = System.currentTimeMillis()
        database.messageDao().upsert(
            MessageEntity(
                id = identity.userMessageId,
                sessionId = command.sessionId,
                role = "USER",
                content = command.text,
                createdAt = now,
            ),
        )
        database.messageOutboxDao().upsert(
            MessageOutboxEntity(
                id = command.commandId,
                sessionId = command.sessionId,
                text = command.text,
                userMessageId = identity.userMessageId,
                assistantMessageId = identity.assistantMessageId,
                createdAt = now,
            ),
        )
        database.conversationTurnDao().upsert(
            ConversationTurnEntity(
                turnId = identity.turnId,
                sessionId = command.sessionId,
                inputUserMessageId = identity.userMessageId,
                assistantMessageId = identity.assistantMessageId,
                phase = "STREAMING",
                startedAt = now,
                updatedAt = now,
            ),
        )
        store.appendSlot(
            sessionId = command.sessionId,
            selectionSetId = command.selectionSetId,
            slotId = "slot-${identity.userMessageId}",
            kind = "USER",
            parentSlotId = null,
            messageId = identity.userMessageId,
        )
        database.conversationCommandReceiptDao().insert(
            ConversationCommandReceiptEntity(
                commandId = command.commandId,
                fingerprint = fingerprint,
                payloadJson = "{}",
                sessionId = command.sessionId,
                messageId = identity.userMessageId,
                turnId = identity.turnId,
                acceptedAt = now,
                resultJson = "{}",
            ),
        )
        CommandResult.Accepted(identity.userMessageId, identity.turnId)
    }

    private fun fingerprint(command: ConversationCommand.Submit): String =
        MessageDigest.getInstance("SHA-256")
            .digest("${command.sessionId}\u001f${command.selectionSetId}\u001f${command.text}".toByteArray())
            .joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        fun newSubmitIdentity(command: ConversationCommand.Submit): SubmitIdentity =
            SubmitIdentity(
                userMessageId = Uuid.random().toString(),
                assistantMessageId = Uuid.random().toString(),
                turnId = Uuid.random().toString(),
            )
    }
}
