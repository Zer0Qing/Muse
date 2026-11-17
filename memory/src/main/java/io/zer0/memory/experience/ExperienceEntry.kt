package io.zer0.memory.experience

import kotlinx.serialization.Serializable

/**
 * 助手经验条目。
 *
 * 与用户事实分开：Experience 记录助手自己的操作教训和工作偏好，
 * 不属于“用户是谁/用户喜欢什么”的记忆投影。
 *
 * title/tags/source 是为了让经验库能同时服务 prompt、UI 与导入：
 * 没有它们，UI 只能继续读另一张表，同一份经验就会出现两个真相源。
 * 全部带默认值，早期只写 content 的文件仍能直接读。
 */
@Serializable
data class ExperienceEntry(
    val id: String,
    val assistantId: String,
    val category: String,
    val content: String,
    /** 创建时间（ISO-8601）。 */
    val createdAt: String,
    /** 简短标题；工具写入的经验可以为空，UI 用正文首行兜底。 */
    val title: String = "",
    /** 检索标签。 */
    val tags: List<String> = emptyList(),
    /** "manual"（用户手记）/ "llm"（模型记录）/ "imported"（旧表导入）。 */
    val source: String = SOURCE_MANUAL,
    /** 提取来源会话 id，便于回溯；手动条目为空。 */
    val sessionId: String? = null,
    /** 更新时间（ISO-8601）；排序按它降序，与旧表行为一致。 */
    val updatedAt: String = createdAt,
) {
    companion object {
        const val SOURCE_MANUAL = "manual"
        const val SOURCE_LLM = "llm"
        const val SOURCE_IMPORTED = "imported"
    }
}
