package io.zer0.memory.runtime

import io.zer0.common.ProcessWriteGate
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 长任务开始时取的恢复栅栏快照。
 *
 * 只有「此刻是否被阻塞」不够:一次模型调用可能跨过一整个恢复窗口,
 * 窗口开合都已结束,返回时 `restoring` 已经复位,但这份结果是基于恢复前的库算出来的。
 * 入口取一份栅栏,写前再用 [MemoryWriteGate.blocksWrite] 比一次,才能把这类过期结果挡在库外。
 */
@JvmInline
value class MemoryWriteFence(val generation: Long) {
    companion object {
        /** 不参与栅栏判定(默认实现/测试替身用)。 */
        val NONE: MemoryWriteFence = MemoryWriteFence(Long.MIN_VALUE)
    }
}

/**
 * 备份/恢复期间暂停后台记忆写入的门闩。
 *
 * DeepMemoryProcessor、DreamService、摘要/编译写入与通知梳理在恢复期间
 * 必须先查 [isBlocked]；普通用户显式删除/查看不受影响。
 *
 * 判定分两层:
 *  - 入口 [isBlocked]：恢复窗口内直接跳过,不白跑一次模型调用;
 *  - 写前 [blocksWrite]：等模型返回后、真正落库前再判一次,并带上入口的 [MemoryWriteFence]。
 */
interface MemoryWriteGate {
    fun isBlocked(scope: MemoryScopeKey): Boolean

    suspend fun <T> withWritesPaused(scope: MemoryScopeKey, block: suspend () -> T): T

    /** 取当前栅栏快照。默认实现表示「无栅栏语义」,与旧实现兼容。 */
    fun fence(): MemoryWriteFence = MemoryWriteFence.NONE

    /** 该栅栏是否已因「任务期间发生过恢复」而失效。默认实现永不失效。 */
    fun isStale(fence: MemoryWriteFence): Boolean = false

    /** 写前判定：当前被阻塞,或入口取的栅栏已失效。 */
    fun blocksWrite(scope: MemoryScopeKey, fence: MemoryWriteFence? = null): Boolean =
        isBlocked(scope) || (fence != null && isStale(fence))
}

/**
 * 进程级阻塞实现。
 *
 * 恢复开始时调 [open]（开始阻塞）或 [close]（恢复完成）。阻塞状态用全局 AtomicBoolean,
 * 恢复代数由 [ProcessWriteGate.generation] 提供 —— 它同时覆盖 JSON 导入(两个闸都开)
 * 与自动恢复(只开进程闸)两条路径,所以本闸不需要自己的计数器。
 */
class DefaultMemoryWriteGate : MemoryWriteGate {
    private val blocked = AtomicBoolean(false)

    fun open() {
        blocked.set(true)
    }

    fun close() {
        blocked.set(false)
    }

    override fun isBlocked(scope: MemoryScopeKey): Boolean = blocked.get() || ProcessWriteGate.restoring

    override fun fence(): MemoryWriteFence = MemoryWriteFence(ProcessWriteGate.generation)

    override fun isStale(fence: MemoryWriteFence): Boolean =
        fence != MemoryWriteFence.NONE && fence.generation != ProcessWriteGate.generation

    override suspend fun <T> withWritesPaused(scope: MemoryScopeKey, block: suspend () -> T): T = block()

    companion object {
        /** 备份恢复期间使用的共享实例,避免 DeepMemoryProcessor 和 BackupService 各自持不同状态。 */
        val shared: DefaultMemoryWriteGate by lazy { DefaultMemoryWriteGate() }
    }
}