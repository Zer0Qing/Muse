package io.zer0.memory.runtime

import java.util.concurrent.atomic.AtomicBoolean

/**
 * 备份/恢复期间暂停后台记忆写入的门闩。
 *
 * DeepMemoryProcessor、DreamService 和 staged projection publish 在恢复期间
 * 必须先查 [isBlocked]；普通用户显式删除/查看不受影响。
 */
interface MemoryWriteGate {
    fun isBlocked(scope: MemoryScopeKey): Boolean
    suspend fun <T> withWritesPaused(scope: MemoryScopeKey, block: suspend () -> T): T
}

/**
 * 进程级阻塞实现。
 *
 * 恢复开始时调 [open]（开始阻塞）或 [close]（恢复完成）。仅用一个全局 AtomicBoolean，
 * 后续按 scope 细分时可替换为 ConcurrentHashMap。
 */
class DefaultMemoryWriteGate : MemoryWriteGate {
    private val blocked = AtomicBoolean(false)

    fun open() {
        blocked.set(true)
    }

    fun close() {
        blocked.set(false)
    }

    override fun isBlocked(scope: MemoryScopeKey): Boolean = blocked.get()

    override suspend fun <T> withWritesPaused(scope: MemoryScopeKey, block: suspend () -> T): T = block()

    companion object {
        /** 备份恢复期间使用的共享实例，避免 DeepMemoryProcessor 和 BackupService 各自持不同状态。 */
        val shared: DefaultMemoryWriteGate by lazy { DefaultMemoryWriteGate() }
    }
}
