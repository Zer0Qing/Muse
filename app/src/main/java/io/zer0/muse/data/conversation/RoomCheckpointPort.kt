package io.zer0.muse.data.conversation

import io.zer0.ai.core.UIMessage
import io.zer0.muse.conversation.core.CheckpointPort
import io.zer0.muse.conversation.core.CheckpointSnapshot
import io.zer0.muse.data.chat.rewrite.MessageCommit
import io.zer0.muse.data.chat.rewrite.MessageCommitRequest
import io.zer0.muse.data.chat.rewrite.MessageCommitResult
import io.zer0.muse.data.session.MessagePartEntity
import io.zer0.muse.data.session.SessionRepository
import java.security.MessageDigest
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlin.uuid.Uuid

/**
 * Bridges core checkpoint snapshots to durable storage. Ordinary checkpoints
 * remain bounded; terminal snapshots use the existing idempotent message commit
 * transaction and preserve structured parts.
 */
class RoomCheckpointPort(
    private val sessionRepository: SessionRepository,
    private val messageCommit: MessageCommit,
    private val assistantFactory: (CheckpointSnapshot) -> UIMessage = ::durableAssistant,
) : CheckpointPort {
    override suspend fun writeCheckpoint(snapshot: CheckpointSnapshot) {
        val assistant = assistantFactory(snapshot)
        sessionRepository.upsertGenerationCheckpoint(
            sessionId = snapshot.identity.sessionId,
            userMessageId = "",
            assistantMessageId = assistant.id.toString(),
            content = snapshot.text,
            createdAt = System.currentTimeMillis(),
        )
    }

    override suspend fun commitTerminal(snapshot: CheckpointSnapshot) {
        commitTerminalResult(snapshot)
    }

    suspend fun commitTerminalResult(snapshot: CheckpointSnapshot): MessageCommitResult {
        val assistant = assistantFactory(snapshot)
        val result = messageCommit.commit(
            MessageCommitRequest(
                turnId = snapshot.identity.turnId,
                sessionId = snapshot.identity.sessionId,
                userMessageId = "",
                assistantMessageId = assistant.id.toString(),
                message = assistant,
                parts = snapshot.parts.mapIndexed { index, part ->
                    MessagePartEntity(
                        messageId = assistant.id.toString(),
                        partIndex = index,
                        kind = part.kind.name.lowercase(),
                        text = part.text,
                        metadataJson =
                        io.zer0.common.AppJson.encodeToString(
                            MapSerializer(String.serializer(), String.serializer()),
                            part.metadata,
                        ),
                        createdAt = assistant.createdAt,
                    )
                },
            ),
        )
        return result
    }

    private companion object {
        fun durableAssistant(snapshot: CheckpointSnapshot): UIMessage {
            val bytes = MessageDigest.getInstance("SHA-256")
                .digest(
                    "${snapshot.identity.sessionId}\u001f${snapshot.identity.turnId}"
                        .toByteArray(Charsets.UTF_8),
                )
            bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x50).toByte()
            bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()
            return UIMessage(
                id = Uuid.fromByteArray(bytes.copyOfRange(0, 16)),
                role = io.zer0.ai.core.MessageRole.ASSISTANT,
                content = snapshot.text,
                createdAt = snapshot.sequence,
            )
        }
    }
}
