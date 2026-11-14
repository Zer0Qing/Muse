package io.zer0.memory.runtime

enum class MemorySourceKind {
    SESSION_SUMMARY,
    FACT,
    COMPILED_SECTION,
    PINNED,
    EXPERIENCE,
}

/**
 * 单条记忆来源的版本标识。
 *
 * key 指向同一 kind 下的稳定资源 id（sessionId、fact id、section key、pinned id）。
 */
data class MemorySourceRevision(
    val kind: MemorySourceKind,
    val key: String,
    val revision: Long,
    val fingerprint: String,
)
