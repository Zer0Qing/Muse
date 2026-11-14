package io.zer0.memory.experience

/**
 * 经验库领域入口。
 *
 * 目前直接委托 [ExperienceStore]；后续若换 Room 存储，调用方只需改这一层。
 */
class ExperienceRepository(
    private val store: ExperienceStore,
) {
    suspend fun record(assistantId: String, category: String, content: String): String = store.record(assistantId, category, content)

    suspend fun list(assistantId: String, category: String? = null): List<ExperienceEntry> = store.list(assistantId, category)
}
