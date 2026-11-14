package io.zer0.memory.dream

import androidx.room.withTransaction
import io.zer0.memory.compile.MemoryCompiler
import io.zer0.memory.runtime.MemoryScopeKey
import io.zer0.memory.runtime.MemoryWriteGate
import io.zer0.memory.summary.MemoryDb
import io.zer0.memory.summary.ScopedCompiledSectionEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.UUID

class DefaultMemoryDreamService(
    private val db: MemoryDb? = null,
    private val scopeStates: Map<String, MemoryDreamState> = emptyMap(),
    private val snapshotReader: (suspend (MemoryScopeKey) -> Map<String, String>)? = null,
    private val writeGate: MemoryWriteGate? = null,
) : MemoryDreamService {
    private val states = scopeStates.toMutableMap()
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    private fun key(scope: MemoryScopeKey) = "${scope.assistantId}|${scope.scope}|${scope.spaceId}"
    private fun stateFor(scope: MemoryScopeKey) = states.getOrPut(key(scope)) { MemoryDreamState() }

    private suspend fun hydrate(scope: MemoryScopeKey, state: MemoryDreamState) {
        val row = db?.memoryDreamDao()?.state(scope.assistantId, scope.scope, scope.spaceId) ?: return
        state.hydrate(row.running, row.runId, row.lastRevisionId, row.activeRevisionId, row.lastError)
    }

    private suspend fun persist(scope: MemoryScopeKey, state: MemoryDreamState) {
        val snapshot = state.snapshot()
        db?.memoryDreamDao()?.upsertState(
            MemoryDreamStateEntity(
                scope.assistantId,
                scope.scope,
                scope.spaceId,
                snapshot.running,
                snapshot.runId,
                snapshot.lastRevisionId,
                state.activeRevisionId(),
                snapshot.lastError,
            ),
        )
    }

    override suspend fun start(scope: MemoryScopeKey, trigger: DreamTrigger, currentSections: Map<String, String>): DreamStartResult =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                if (writeGate?.isBlocked(scope) == true) {
                    return@withContext DreamStartResult.Rejected("memory writes are paused")
                }
                val state = stateFor(scope)
                hydrate(scope, state)
                if (state.snapshot().running) return@withContext DreamStartResult.Rejected("dream already running")
                val runId = UUID.randomUUID().toString()
                state.start(runId)
                persist(scope, state)
                val revision = MemoryDreamRevision(
                    runId,
                    trigger,
                    fingerprintCompiled(currentSections),
                    currentSections,
                    preserveAndTrimSections(currentSections),
                    Instant.now().toString(),
                )
                try {
                    val latest = snapshotReader?.invoke(scope)
                    if (latest != null && fingerprintCompiled(latest) != revision.beforeFingerprint) {
                        state.markFailure("memory changed during dream")
                        persist(scope, state)
                        return@withContext DreamStartResult.Rejected("memory changed during dream")
                    }
                    if (db != null) {
                        db.withTransaction {
                            db.memoryDreamDao().insertRevision(revision.toEntity(scope))
                            state.markPublished(runId)
                            db.memoryDreamDao().upsertState(state.toEntity(scope))
                        }
                    } else if (!state.publish(revision)) {
                        state.markFailure("dream publish rejected")
                        persist(scope, state)
                        return@withContext DreamStartResult.Rejected("dream publish rejected")
                    } else {
                        state.markPublished(runId)
                        persist(scope, state)
                    }
                    DreamStartResult.Started(runId)
                } catch (cancelled: CancellationException) {
                    state.markFailure("cancelled")
                    persist(scope, state)
                    throw cancelled
                } catch (error: Exception) {
                    state.markFailure(error.message ?: "dream publish failed")
                    persist(scope, state)
                    DreamStartResult.Rejected("dream publish failed")
                }
            }
        }

    override suspend fun stop(scope: MemoryScopeKey): DreamStopResult = mutex.withLock {
        val state = stateFor(scope)
        hydrate(scope, state)
        state.markStopped()
        persist(scope, state)
        DreamStopResult.Stopped
    }

    override suspend fun status(scope: MemoryScopeKey): DreamStatus = mutex.withLock {
        val state = stateFor(scope)
        hydrate(scope, state)
        state.snapshot()
    }

    override suspend fun restore(scope: MemoryScopeKey, revisionId: String): DreamRestoreResult = mutex.withLock {
        if (writeGate?.isBlocked(scope) == true) return@withLock DreamRestoreResult.Rejected("memory writes are paused")
        val state = stateFor(scope)
        hydrate(scope, state)
        val revision = db?.memoryDreamDao()?.findRevision(revisionId, scope.assistantId, scope.scope, scope.spaceId)?.toRevision()
            ?: state.revisionOf(revisionId)
            ?: return@withLock DreamRestoreResult.Rejected("revision not found: $revisionId")
        if (revision.nextSections.isEmpty()) return@withLock DreamRestoreResult.Rejected("revision has no sections")
        if (db != null) {
            db.withTransaction {
                db.scopedCompiledSectionDao().deleteForScope(scope.scope, scope.spaceId)
                revision.nextSections.forEach { (key, content) ->
                    db.scopedCompiledSectionDao().upsert(
                        ScopedCompiledSectionEntity(
                            sectionKey = key,
                            scope = scope.scope,
                            spaceId = scope.spaceId,
                            content = content,
                            fingerprint = fingerprintCompiled(mapOf(key to content)),
                            updatedAt = Instant.now().toString(),
                            sourceFingerprint = revision.beforeFingerprint,
                            status = "READY",
                        ),
                    )
                }
                state.restoreActive(revision.revisionId)
                db.memoryDreamDao().upsertState(state.toEntity(scope))
            }
        } else {
            state.restoreActive(revision.revisionId)
            persist(scope, state)
        }
        DreamRestoreResult.Restored(revisionId)
    }

    private fun MemoryDreamRevision.toEntity(scope: MemoryScopeKey) = MemoryDreamRevisionEntity(
        revisionId, scope.assistantId, scope.scope, scope.spaceId, trigger.name, beforeFingerprint,
        json.encodeToString(before), json.encodeToString(nextSections), publishedAt,
    )

    private fun MemoryDreamRevisionEntity.toRevision() = MemoryDreamRevision(
        revisionId,
        DreamTrigger.valueOf(trigger),
        beforeFingerprint,
        json.decodeFromString(beforeJson),
        json.decodeFromString(nextJson),
        publishedAt,
    )

    private fun MemoryDreamState.toEntity(scope: MemoryScopeKey): MemoryDreamStateEntity {
        val value = snapshot()
        return MemoryDreamStateEntity(
            scope.assistantId,
            scope.scope,
            scope.spaceId,
            value.running,
            value.runId,
            value.lastRevisionId,
            activeRevisionId(),
            value.lastError,
        )
    }

    private fun preserveAndTrimSections(current: Map<String, String>) = buildMap {
        val facts = current[MemoryCompiler.Section.FACTS.key].orEmpty()
        val today = current[MemoryCompiler.Section.TODAY.key].orEmpty()
        val week = current[MemoryCompiler.Section.WEEK.key].orEmpty()
        val longterm = current[MemoryCompiler.Section.LONGTERM.key].orEmpty()
        put(MemoryCompiler.Section.FACTS.key, facts)
        put(MemoryCompiler.Section.TODAY.key, today)
        put(MemoryCompiler.Section.WEEK.key, week)
        val joined = listOf(longterm, facts, today, week).filter { it.isNotBlank() }.joinToString("\n")
        put(MemoryCompiler.Section.LONGTERM.key, if (joined.length <= 830) joined else joined.take(730) + "\n[…]")
    }
}
