package io.zer0.muse.conversation.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class CheckpointSnapshot(
    val identity: RunIdentity,
    val sequence: Long,
    val text: String,
    val parts: List<MessagePart>,
) {
    init {
        require(sequence >= 0) { "checkpoint sequence must not be negative" }
        require(parts.map { it.partId }.distinct().size == parts.size) { "part ids must be unique" }
    }
}

interface CheckpointPort {
    suspend fun writeCheckpoint(snapshot: CheckpointSnapshot)

    suspend fun commitTerminal(snapshot: CheckpointSnapshot)
}

/**
 * Bounds checkpoint persistence per turn while keeping terminal durability
 * independent of any UI subscriber.
 */
class CheckpointWriter(
    private val port: CheckpointPort,
    dispatcher: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutex = Mutex()
    private var active: Job? = null
    private var pending: CheckpointSnapshot? = null
    private var terminalSnapshot: CheckpointSnapshot? = null
    private var terminalRequested = false
    private var terminalJob: Job? = null

    fun schedule(snapshot: CheckpointSnapshot) {
        synchronized(this) {
            if (terminalRequested) return
            if (active != null) {
                pending = snapshot
                return
            }
            active = launchWrite(snapshot)
        }
    }

    fun terminal(snapshot: CheckpointSnapshot): Job = synchronized(this) {
        terminalJob?.let { return it }
        terminalRequested = true
        pending = null
        terminalSnapshot = snapshot
        val job = scope.launch { drainTerminal() }
        terminalJob = job
        job
    }

    private fun launchWrite(snapshot: CheckpointSnapshot): Job = scope.launch {
        try {
            port.writeCheckpoint(snapshot)
        } finally {
            completeWrite()
        }
    }

    private suspend fun completeWrite() {
        mutex.withLock {
            active = null
        }
        val next =
            synchronized(this) {
                if (terminalRequested) return@synchronized null
                pending.also { pending = null }
            } ?: return
        synchronized(this) {
            if (!terminalRequested) active = launchWrite(next)
        }
    }

    private suspend fun drainTerminal() {
        while (true) {
            val current = synchronized(this) { active }
            if (current == null) break
            current.join()
        }
        mutex.withLock {
            val snapshot = synchronized(this) { terminalSnapshot }
                ?: error("terminal snapshot missing")
            port.commitTerminal(snapshot)
        }
    }
}
