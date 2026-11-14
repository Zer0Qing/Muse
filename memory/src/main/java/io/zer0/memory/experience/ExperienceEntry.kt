package io.zer0.memory.experience

import kotlinx.serialization.Serializable

/**
 * 助手经验条目。
 *
 * 与用户事实分开：Experience 记录助手自己的操作教训和工作偏好，
 * 不属于“用户是谁/用户喜欢什么”的记忆投影。
 */
@Serializable
data class ExperienceEntry(
    val id: String,
    val assistantId: String,
    val category: String,
    val content: String,
    val createdAt: String,
)
