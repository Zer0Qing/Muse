package io.zer0.muse.transformer

/**
 * S-03: 旧版 pinned_memories.json 条目 schema（仅迁移用）。
 *
 * 从 `SystemPromptAssembler.kt` 切出——只在迁移路径里反序列化一次，
 * 与组装器本身无关。
 */
/**
 * S-03: 旧版 pinned_memories.json 条目 schema(仅迁移用;现统一走 PinnedMemoryStore)。
 */
@kotlinx.serialization.Serializable
internal data class LegacyPinnedMemoryItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val content: String,
    val createdAt: Long = System.currentTimeMillis(),
)
