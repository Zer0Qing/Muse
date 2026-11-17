package io.zer0.memory.experience

import kotlinx.coroutines.flow.StateFlow

/**
 * 经验库领域入口。
 *
 * 目前直接委托 [ExperienceStore]；后续若换 Room 存储，调用方只需改这一层。
 *
 * 这里是经验库的唯一读写的门：prompt、记忆页 UI 与工具都走它，
 * 不允许再出现第二条读旧表的路径 —— 两处读法一旦不同，用户就会看到「工具记了但页面没有」。
 */
class ExperienceRepository(
    private val store: ExperienceStore,
) {
    /** 写入信号：单调递增，订阅者据此重读。 */
    val revision: StateFlow<Long> get() = store.revision

    suspend fun record(
        assistantId: String,
        category: String,
        content: String,
        title: String = "",
        tags: List<String> = emptyList(),
        source: String = ExperienceEntry.SOURCE_MANUAL,
        sessionId: String? = null,
        createdAt: String? = null,
        updatedAt: String? = null,
    ): String = store.record(
        assistantId = assistantId,
        category = category,
        content = content,
        title = title,
        tags = tags,
        source = source,
        sessionId = sessionId,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    suspend fun list(assistantId: String, category: String? = null): List<ExperienceEntry> =
        store.list(assistantId, category)

    /** 跨助手读取：prompt 注入与记忆页列表用。工具读写仍按助手隔离。 */
    suspend fun listAll(): List<ExperienceEntry> = store.listAll()

    suspend fun update(entry: ExperienceEntry): Boolean = store.update(entry)

    suspend fun delete(id: String): Boolean = store.delete(id)
}
