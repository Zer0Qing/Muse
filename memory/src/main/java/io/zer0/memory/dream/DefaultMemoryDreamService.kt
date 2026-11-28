package io.zer0.memory.dream

import androidx.room.withTransaction
import io.zer0.memory.compile.MemoryCompiler
import io.zer0.memory.runtime.MemoryScopeKey
import io.zer0.memory.runtime.MemoryWriteFence
import io.zer0.memory.runtime.MemoryWriteGate
import io.zer0.memory.summary.CompiledSectionDao
import io.zer0.memory.summary.MemoryDb
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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
    /** 计划生成器。为 null 时退化为「只做长度收敛」，不产生内容变化（保持既有测试语义）。 */
    private val planner: MemoryDreamPlanner? = null,
) : MemoryDreamService {
    private val states = scopeStates.toMutableMap()
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    private fun key(scope: MemoryScopeKey) = "${scope.assistantId}|${scope.scope}|${scope.spaceId}"
    private fun stateFor(scope: MemoryScopeKey) = states.getOrPut(key(scope)) { MemoryDreamState() }

    private suspend fun hydrate(scope: MemoryScopeKey, state: MemoryDreamState) {
        val dao = db?.memoryDreamDao() ?: return
        val row = dao.state(scope.assistantId, scope.scope, scope.spaceId)
        if (row == null) {
            // 持久化行被清掉（重置记忆/删助手）时，内存里的 lastRevisionId 不能继续冒充可回滚。
            state.hydrate(false, null, null, null, null)
            return
        }
        if (row.running) {
            // 水合持有运行锁，仍标记为运行中的行来自一次被中断的运行。
            state.hydrate(false, null, row.lastRevisionId, row.activeRevisionId, row.lastError ?: STALE_RUN_ERROR)
            persist(scope, state)
            return
        }
        state.hydrate(row.running, row.runId, row.lastRevisionId, row.activeRevisionId, row.lastError)
    }

    private suspend fun persist(scope: MemoryScopeKey, state: MemoryDreamState, fence: MemoryWriteFence? = null) {
        if (writeGate?.blocksWrite(scope, fence) == true) return
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

    override suspend fun start(
        scope: MemoryScopeKey,
        trigger: DreamTrigger,
        currentSections: Map<String, String>,
    ): DreamStartResult =
        mutex.withLock {
            MemoryMaintenanceLock.mutex.withLock {
                withContext(Dispatchers.IO) {
                    runStart(scope, trigger, currentSections)
                }
            }
        }

    private suspend fun runStart(scope: MemoryScopeKey, trigger: DreamTrigger, currentSections: Map<String, String>): DreamStartResult {
        val fence = writeGate?.fence()
        if (writeGate?.blocksWrite(scope, fence) == true) return DreamStartResult.Rejected(GATE_PAUSED_REASON)
        val state = stateFor(scope)
        hydrate(scope, state)
        if (state.snapshot().running) return DreamStartResult.Rejected("dream already running")
        val runId = UUID.randomUUID().toString()
        state.start(runId)
        try {
            persist(scope, state, fence)
        } catch (cancelled: CancellationException) {
            abortCancelled(scope, state, cancelled, fence)
        }
        // 先让 planner 产出候选，再校验；模型输出永远不直接落库。
        // planner 为 null 时走纯长度收敛，保持「Dream 只整理不生成」的旧行为。
        // 取消必须在这里就收尾：planner 跑在下面的发布 try 之外，漏掉这一步会让
        // 持久化行永远停在 running=true，用户之后每次启动都被「已在运行」挡住。
        val plan: DreamPlanResult = try {
            resolveDreamPlan(
                planner = planner,
                currentSections = currentSections,
                fallback = { preserveAndTrimSections(currentSections) },
                runPlanner = ::runPlanner,
            )
        } catch (cancelled: CancellationException) {
            abortCancelled(scope, state, cancelled, fence)
        }
        return when (plan) {
            is DreamPlanResult.Planned -> publishDream(
                scope,
                trigger,
                currentSections,
                currentSections + plan.sections,
                state,
                runId,
                fence,
            )
            DreamPlanResult.Empty -> {
                state.markStopped()
                persist(scope, state, fence)
                DreamStartResult.Rejected("dream plan is empty")
            }
            is DreamPlanResult.Rejected -> {
                state.markFailure(plan.reason)
                persist(scope, state, fence)
                DreamStartResult.Rejected(plan.reason)
            }
            is DreamPlanResult.Failed -> {
                state.markFailure(plan.reason)
                persist(scope, state, fence)
                DreamStartResult.Rejected(plan.reason)
            }
        }
    }

    private suspend fun publishDream(
        scope: MemoryScopeKey,
        trigger: DreamTrigger,
        currentSections: Map<String, String>,
        nextSections: Map<String, String>,
        state: MemoryDreamState,
        runId: String,
        fence: MemoryWriteFence?,
    ): DreamStartResult {
        if (writeGate?.blocksWrite(scope, fence) == true) {
            state.markFailure(GATE_PAUSED_REASON)
            return DreamStartResult.Rejected(GATE_PAUSED_REASON)
        }
        val changed = diffSections(currentSections, nextSections)
        if (changed.isEmpty()) {
            state.markStopped()
            persist(scope, state, fence)
            return DreamStartResult.Rejected("dream made no changes")
        }
        val revision = MemoryDreamRevision(
            runId,
            trigger,
            fingerprintCompiled(currentSections),
            currentSections,
            nextSections,
            Instant.now().toString(),
        )
        return try {
            val latest = snapshotReader?.invoke(scope)
            if (latest != null && fingerprintCompiled(latest) != revision.beforeFingerprint) {
                state.markFailure("memory changed during dream")
                persist(scope, state, fence)
                DreamStartResult.Rejected("memory changed during dream")
            } else if (db != null) {
                var blockedInTransaction = false
                db.withTransaction {
                    if (writeGate?.blocksWrite(scope, fence) == true) {
                        blockedInTransaction = true
                        return@withTransaction
                    }
                    publishSections(scope, changed, revision.beforeFingerprint, revision.publishedAt)
                    db.memoryDreamDao().insertRevision(revision.toEntity(scope, json))
                    state.markPublished(runId)
                    db.memoryDreamDao().upsertState(state.toEntity(scope))
                }
                if (blockedInTransaction) {
                    state.markFailure(GATE_PAUSED_REASON)
                    DreamStartResult.Rejected(GATE_PAUSED_REASON)
                } else {
                    DreamStartResult.Started(runId)
                }
            } else if (!state.publish(revision)) {
                state.markFailure("dream publish rejected")
                persist(scope, state, fence)
                DreamStartResult.Rejected("dream publish rejected")
            } else {
                state.markPublished(runId)
                persist(scope, state, fence)
                DreamStartResult.Started(runId)
            }
        } catch (cancelled: CancellationException) {
            abortCancelled(scope, state, cancelled, fence)
        } catch (error: Exception) {
            state.markFailure(error.message ?: "dream publish failed")
            persist(scope, state, fence)
            DreamStartResult.Rejected("dream publish failed")
        }
    }

    override suspend fun currentSections(scope: MemoryScopeKey): Map<String, String> = withContext(Dispatchers.IO) {
        snapshotReader?.invoke(scope)
            ?: db?.scopedCompiledSectionDao()
                ?.getForScope(scope.scope, scope.spaceId)
                ?.filter { row -> row.status == "READY" }
                ?.associate { row -> row.sectionKey to row.content }
            ?: emptyMap()
    }

    override suspend fun stop(scope: MemoryScopeKey): DreamStopResult = mutex.withLock {
        MemoryMaintenanceLock.mutex.withLock {
            val state = stateFor(scope)
            hydrate(scope, state)
            state.markStopped()
            persist(scope, state)
            DreamStopResult.Stopped
        }
    }

    override suspend fun status(scope: MemoryScopeKey): DreamStatus = mutex.withLock {
        MemoryMaintenanceLock.mutex.withLock {
            val state = stateFor(scope)
            hydrate(scope, state)
            state.snapshot()
        }
    }

    /**
     * 回滚到某次 Dream 发布前的快照。
     *
     * 语义要点：
     *  - 写回的是 revision 的 **before**，不是 next —— next 是 Dream 已经发布过的内容；
     *  - 只处理这次 Dream 真正改过的 key（before/next 的差集），不动其它段落，
     *    失败的投影（FAILED/STALE）留在原处；
     *  - Dream 新增的 key（before 里没有）直接删行，而不是写空串；
     *  - 回滚前必须确认当前 READY 内容仍等于本次 Dream 的 next。否则说明之后又有编译/编辑落过盘，
     *    此时回滚会用旧快照覆盖新成果。
     */
    override suspend fun restore(scope: MemoryScopeKey, revisionId: String): DreamRestoreResult = mutex.withLock {
        MemoryMaintenanceLock.mutex.withLock {
            withContext(Dispatchers.IO) {
                if (writeGate?.isBlocked(scope) == true) {
                    return@withContext DreamRestoreResult.Rejected(GATE_PAUSED_REASON)
                }
                val state = stateFor(scope)
                hydrate(scope, state)
                val revision = db?.memoryDreamDao()
                    ?.findRevision(revisionId, scope.assistantId, scope.scope, scope.spaceId)
                    ?.toRevision(json)
                    ?: state.revisionOf(revisionId)
                    ?: return@withContext DreamRestoreResult.Rejected("revision not found: $revisionId")
                if (revision.nextSections.isEmpty()) {
                    return@withContext DreamRestoreResult.Rejected("revision has no sections")
                }
                val rollbackKeys = changedKeys(revision.before, revision.nextSections)
                if (rollbackKeys.isEmpty()) {
                    return@withContext DreamRestoreResult.Rejected("revision did not change any section")
                }
                if (db == null) {
                    state.restoreActive(revision.revisionId)
                    persist(scope, state)
                    return@withContext DreamRestoreResult.Restored(revisionId)
                }
                var rejectReason: String? = null
                var restored = false
                db.withTransaction {
                    if (writeGate?.isBlocked(scope) == true) {
                        rejectReason = GATE_PAUSED_REASON
                        return@withTransaction
                    }
                    val currentReady = db.scopedCompiledSectionDao()
                        .getForScope(scope.scope, scope.spaceId)
                        .filter { row -> row.status == "READY" }
                        .associate { row -> row.sectionKey to row.content }
                    for (key in rollbackKeys) {
                        val expected = revision.nextSections[key]?.trim().orEmpty()
                        val actual = currentReady[key]?.trim().orEmpty()
                        if (actual != expected) {
                            rejectReason = "memory changed since dream"
                            return@withTransaction
                        }
                    }
                    val now = Instant.now().toString()
                    for (key in rollbackKeys) {
                        val before = revision.before[key]
                        if (before == null) {
                            // Dream 新增的段落：回滚时直接删掉，避免空行继续被注入。
                            db.scopedCompiledSectionDao().delete(key, scope.scope, scope.spaceId)
                            clearLegacySection(scope, key, now)
                        } else {
                            db.scopedCompiledSectionDao().updateContent(
                                key = key,
                                scope = scope.scope,
                                spaceId = scope.spaceId,
                                content = before,
                                fingerprint = fingerprintCompiled(mapOf(key to before)),
                                now = now,
                                sourceFingerprint = revision.beforeFingerprint,
                                status = "READY",
                            )
                            mirrorLegacySection(scope, key, before, now)
                        }
                    }
                    state.restoreActive(revision.revisionId)
                    db.memoryDreamDao().upsertState(state.toEntity(scope))
                    restored = true
                }
                if (!restored) {
                    val reason = rejectReason ?: "dream restore rejected"
                    state.markFailure(reason)
                    persist(scope, state)
                    return@withContext DreamRestoreResult.Rejected(reason)
                }
                DreamRestoreResult.Restored(revisionId)
            }
        }
    }

    /**
     * 取消收尾：running 必须落回磁盘，否则下次启动会被「已在运行」永久挡住。
     *
     * 用 [NonCancellable] 包住持久化：调用方的 Job 已经取消，普通挂起写会立即失败。
     */
    private suspend fun abortCancelled(
        scope: MemoryScopeKey,
        state: MemoryDreamState,
        cancelled: CancellationException,
        fence: MemoryWriteFence?,
    ): Nothing {
        state.markFailure("cancelled")
        withContext(NonCancellable) { persist(scope, state, fence) }
        throw cancelled
    }

    /**
     * 发布只动这次 Dream 真正改过的 section。
     *
     * 旧实现整段 `deleteForScope + 重写`，会把同时存在的 FAILED/STALE 投影和新编译成果一起抹掉；
     * 逐 key UPSERT 才能既让 Dream 生效，又保留无关行。
     */
    private suspend fun publishSections(scope: MemoryScopeKey, changed: Map<String, String>, sourceFingerprint: String, now: String) {
        val scoped = db?.scopedCompiledSectionDao() ?: return
        for ((key, content) in changed) {
            scoped.updateContent(
                key = key,
                scope = scope.scope,
                spaceId = scope.spaceId,
                content = content,
                fingerprint = fingerprintCompiled(mapOf(key to content)),
                now = now,
                sourceFingerprint = sourceFingerprint,
                status = "READY",
            )
            mirrorLegacySection(scope, key, content, now)
        }
    }

    /**
     * 主助手默认槽位同时维护旧表 `compiled_sections`。
     *
     * 读取路径在 scoped 行缺失时会回退到旧表（兼容升级前的数据）。若只写 scoped 表，
     * 回滚删除新增 key 后旧表里的过期内容会从兜底路径复活。写回/清空时同步旧表即可。
     */
    private suspend fun mirrorLegacySection(scope: MemoryScopeKey, key: String, content: String, now: String) {
        if (scope.scope != MAIN_SCOPE || scope.spaceId != DEFAULT_SPACE) return
        legacySectionDao().updateContent(key, content, null, now)
    }

    private suspend fun clearLegacySection(scope: MemoryScopeKey, key: String, now: String) {
        if (scope.scope != MAIN_SCOPE || scope.spaceId != DEFAULT_SPACE) return
        legacySectionDao().clearByKey(key, now)
    }

    private fun legacySectionDao(): CompiledSectionDao = requireNotNull(db).compiledSectionDao()

    private companion object {
        const val MAIN_SCOPE = "main"
        const val DEFAULT_SPACE = "default"
        const val GATE_PAUSED_REASON = "memory writes are paused"
        const val STALE_RUN_ERROR = "previous dream run interrupted"
    }
}

private fun MemoryDreamRevision.toEntity(scope: MemoryScopeKey, json: Json) = MemoryDreamRevisionEntity(
    revisionId,
    scope.assistantId,
    scope.scope,
    scope.spaceId,
    trigger.name,
    beforeFingerprint,
    json.encodeToString(before),
    json.encodeToString(nextSections),
    publishedAt,
)

private fun MemoryDreamRevisionEntity.toRevision(json: Json) = MemoryDreamRevision(
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

private suspend fun resolveDreamPlan(
    planner: MemoryDreamPlanner?,
    currentSections: Map<String, String>,
    fallback: () -> Map<String, String>,
    runPlanner: suspend (MemoryDreamPlanner, Map<String, String>) -> DreamPlanResult,
): DreamPlanResult = planner?.let { runPlanner(it, currentSections) }
    ?: DreamPlanResult.Planned(fallback())

private suspend fun runPlanner(planner: MemoryDreamPlanner, current: Map<String, String>): DreamPlanResult = try {
    MemoryDreamPlanValidator.validate(planner.plan(current), current)
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: java.io.IOException) {
    DreamPlanResult.Failed(error.message ?: "dream planner failed")
} catch (error: IllegalStateException) {
    DreamPlanResult.Failed(error.message ?: "dream planner failed")
} catch (error: IllegalArgumentException) {
    DreamPlanResult.Failed(error.message ?: "dream planner failed")
}

private fun preserveAndTrimSections(current: Map<String, String>): Map<String, String> {
    val facts = current[MemoryCompiler.Section.FACTS.key].orEmpty()
    val today = current[MemoryCompiler.Section.TODAY.key].orEmpty()
    val week = current[MemoryCompiler.Section.WEEK.key].orEmpty()
    val longterm = current[MemoryCompiler.Section.LONGTERM.key].orEmpty()
    val joined = listOf(longterm, facts, today, week).filter { it.isNotBlank() }.joinToString("\n")
    val trimmed = if (joined.length <= 830) joined else joined.take(730) + "\n[…]"
    return current + (MemoryCompiler.Section.LONGTERM.key to trimmed)
}

private fun diffSections(before: Map<String, String>, after: Map<String, String>): Map<String, String> {
    val keys = before.keys + after.keys
    return keys.mapNotNull { key ->
        val old = before[key]?.trim().orEmpty()
        val new = after[key] ?: return@mapNotNull null
        if (old == new.trim()) null else key to new
    }.toMap()
}

private fun changedKeys(before: Map<String, String>, after: Map<String, String>): List<String> = (before.keys + after.keys).filter { key ->
        before[key]?.trim().orEmpty() != after[key]?.trim().orEmpty()
    }
