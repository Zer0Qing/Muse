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

    /**
     * 读取该 scope 当前 READY 的 compiled sections。
     *
     * 由 service 提供而不是让 UI 自己读 DAO：beforeFingerprint 必须与 service 内部
     * 校验用的是同一份数据，两个读法一旦有差异，Dream 会因为「内容变了」被莫名拒绝。
     */
    suspend fun currentSections(scope: MemoryScopeKey): Map<String, String>
}
