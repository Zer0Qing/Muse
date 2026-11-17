package io.zer0.memory.runtime

enum class MemoryProjectionStatus {
    READY,
    STALE,
    FAILED,
    ORPHANED,
}

/** 编译产物快照，供 prompt 渲染和 UI 共同使用。 */
data class CompiledMemorySnapshot(
    val sections: Map<String, String>,
    val sourceRevisions: List<MemorySourceRevision>,
    /**
     * 每段的来源信息（含 status / fingerprint / updatedAt）。
     *
     * 与 [sections] 并列而不是嵌在内容里：prompt 只用内容，UI 与备份需要来源，
     * 两者混在一起会让「渲染文本」不得不携带元数据。
     */
    val provenance: Map<String, SectionProvenance> = emptyMap(),
)

data class MemoryReadRequest(
    val scope: MemoryScopeKey,
    val query: String? = null,
    val dateRange: io.zer0.memory.fact.FactStore.DateRange? = null,
    val tokenBudget: Int,
    val includeCompiled: Boolean = true,
    val includeFacts: Boolean = true,
    val includePinned: Boolean = true,
    val includeGlobalPinned: Boolean = false,
    val includeSessionSummary: Boolean = false,
    val sessionId: String? = null,
    val includeSuppressed: Boolean = false,
    /**
     * 是否让失败/过期的编译段落也进入结果。
     *
     * 只给 UI 诊断用；prompt 与工具读取必须保持 false。
     */
    val includeNonReadyCompiled: Boolean = false,
)

data class MemoryReadResult(
    val pinned: List<io.zer0.memory.pin.PinnedMemoryStore.PinnedEntry> = emptyList(),
    val facts: List<io.zer0.memory.fact.FactStore.Fact> = emptyList(),
    val compiled: CompiledMemorySnapshot? = null,
    val sessionSummary: io.zer0.memory.summary.SessionSummaryManager.SummaryData? = null,
    val sources: List<MemorySourceRevision> = emptyList(),
    val usedTokens: Int = 0,
    val truncated: Boolean = false,
)
