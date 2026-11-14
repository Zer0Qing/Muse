package io.zer0.memory.dream

/**
 * Dream 状态机。
 *
 * 纯内存状态；持久化待 Task 11/12 接入 Room。运行中禁止并发 start；
 * 发布必须经过 [validateReplayFingerprint] 检查。
 */
class MemoryDreamState {
    private var running = false
    private var runId: String? = null
    private var lastRevisionId: String? = null
    private var activeRevisionId: String? = null
    private var lastError: String? = null
    private var revisions = mutableMapOf<String, MemoryDreamRevision>()

    fun start(runId: String): DreamStartResult = if (running) {
        DreamStartResult.Rejected("dream already running")
    } else {
        this.running = true
        this.runId = runId
        DreamStartResult.Started(runId)
    }

    fun snapshot(): DreamStatus = DreamStatus(running, runId, lastRevisionId, lastError)

    fun activeRevisionId(): String? = activeRevisionId

    fun hydrate(running: Boolean, runId: String?, lastRevisionId: String?, activeRevisionId: String?, lastError: String?) {
        this.running = running
        this.runId = runId
        this.lastRevisionId = lastRevisionId
        this.activeRevisionId = activeRevisionId
        this.lastError = lastError
    }

    fun publish(revision: MemoryDreamRevision): Boolean {
        if (revisions[revision.revisionId] != null) return false
        if (!validateReplayFingerprint(revision.beforeFingerprint, revision.before, revision.nextSections)) return false
        revisions[revision.revisionId] = revision
        lastRevisionId = revision.revisionId
        activeRevisionId = revision.revisionId
        lastError = null
        return true
    }

    fun restore(revisionId: String): DreamRestoreResult {
        val revision = revisions[revisionId] ?: return DreamRestoreResult.Rejected("revision not found: $revisionId")
        return DreamRestoreResult.Restored(revisionId)
    }

    fun markRunning(runId: String): DreamStartResult = start(runId)

    fun markStopped() {
        running = false
        runId = null
    }

    fun markPublished(revisionId: String) {
        lastRevisionId = revisionId
        activeRevisionId = revisionId
        lastError = null
        markStopped()
    }

    fun restoreActive(revisionId: String) {
        activeRevisionId = revisionId
        lastRevisionId = revisionId
        lastError = null
    }

    fun markFailure(error: String) {
        running = false
        runId = null
        lastError = error
    }

    fun revisionOf(revisionId: String): MemoryDreamRevision? = revisions[revisionId]

    fun clearRevisions() {
        revisions.clear()
        lastRevisionId = null
    }

    private fun validateReplayFingerprint(expected: String, currentBefore: Map<String, String>, next: Map<String, String>): Boolean {
        if (fingerprintCompiled(currentBefore) != expected) return false
        if (next.isEmpty()) return false
        return true
    }
}
