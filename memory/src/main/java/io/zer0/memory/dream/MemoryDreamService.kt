package io.zer0.memory.dream

import io.zer0.memory.runtime.MemoryScopeKey

/**
 * Dream 服务接口。
 *
 * start 接收当前 compiled sections 快照；stop/status/restore 使用 scope key。
 * 产品接线（把 sections 读进来、按 LLM 输出构造 nextSections、持久化 revision）
 * 后续由 Task 12 接入。
 */
interface MemoryDreamService {
    suspend fun start(scope: MemoryScopeKey, trigger: DreamTrigger, currentSections: Map<String, String>): DreamStartResult
    suspend fun stop(scope: MemoryScopeKey): DreamStopResult
    suspend fun status(scope: MemoryScopeKey): DreamStatus
    suspend fun restore(scope: MemoryScopeKey, revisionId: String): DreamRestoreResult
}
