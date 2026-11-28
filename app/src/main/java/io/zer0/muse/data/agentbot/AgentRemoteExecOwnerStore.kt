package io.zer0.muse.data.agentbot

import java.util.UUID

private const val REMOTE_EXEC_OWNER_RETENTION_MS = 90L * 24 * 60 * 60 * 1000

/** Keeps compute execution ownership records separate from task state transitions. */
internal class AgentRemoteExecOwnerStore(
    private val eventDao: BotEventDao,
    private val now: () -> Long,
) {

    suspend fun record(nodeId: String, remoteRunId: String, botId: String, originSessionId: String): Boolean {
        if (nodeId.isBlank() || remoteRunId.isBlank() || botId.isBlank() || originSessionId.isBlank()) return false
        eventDao.deleteConsumedBefore(BotEventKind.COMPUTE_EXEC_OWNER, now() - REMOTE_EXEC_OWNER_RETENTION_MS)
        val dedupeKey = key(nodeId, remoteRunId)
        val timestamp = now()
        eventDao.insertIgnore(
            BotEventEntity(
                eventId = UUID.randomUUID().toString(),
                botId = botId,
                originSessionId = originSessionId,
                runId = remoteRunId,
                kind = BotEventKind.COMPUTE_EXEC_OWNER,
                dedupeKey = dedupeKey,
                payloadRef = nodeId,
                createdAt = timestamp,
                consumedAt = timestamp,
            ),
        )
        val stored = eventDao.findByDedupeKey(dedupeKey) ?: return false
        return stored.kind == BotEventKind.COMPUTE_EXEC_OWNER && stored.payloadRef == nodeId &&
            stored.botId == botId && stored.originSessionId == originSessionId
    }

    suspend fun find(nodeId: String, remoteRunId: String): BotEventEntity? {
        if (nodeId.isBlank() || remoteRunId.isBlank()) return null
        return eventDao.findByDedupeKey(key(nodeId, remoteRunId))?.takeIf {
            it.kind == BotEventKind.COMPUTE_EXEC_OWNER && it.payloadRef == nodeId &&
                it.runId == remoteRunId && it.botId.isNotBlank() && it.originSessionId.isNotBlank() &&
                it.createdAt >= now() - REMOTE_EXEC_OWNER_RETENTION_MS
        }
    }

    suspend fun clear(nodeId: String, remoteRunId: String) {
        find(nodeId, remoteRunId)?.let { eventDao.deleteById(it.eventId) }
    }

    private fun key(nodeId: String, remoteRunId: String): String =
        "compute-exec-owner:" + io.zer0.muse.computenode.RemoteExecOwnerKey.digest(nodeId, remoteRunId)
}
