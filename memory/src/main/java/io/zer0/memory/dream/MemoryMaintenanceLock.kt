package io.zer0.memory.dream

import kotlinx.coroutines.sync.Mutex

/**
 * 记忆维护的进程级串行闸。
 *
 * Dream 的手动整理、夜间整理和助手/会话删除后的记忆清理都会改写同一批
 * `compiled_sections_scoped` 行与事实表：三者并发时，Dream 的 revision 快照会与清理/合并
 * 交错，回滚就会把已经删掉的内容写回来，或者把刚合并的新文本当成「发布前状态」。
 *
 * 放在 memory 模块里而不是各自的 service/worker 里，是因为调用方分属 UI、WorkManager
 * 和删除流程，只有共享同一个实例才能真正串行。
 */
object MemoryMaintenanceLock {
    val mutex = Mutex()
}
