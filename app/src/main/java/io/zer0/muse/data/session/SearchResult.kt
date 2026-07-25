package io.zer0.muse.data.session

/**
 * 搜索结果项。
 *
 * @param messageId 匹配的消息 id
 * @param sessionId 所属会话 id(点击跳转用)
 * @param sessionTitle 会话标题(结果显示)
 * @param contentSnippet 内容片段(匹配关键词前后 30 字)
 * @param role 消息角色
 * @param createdAt 创建时间戳
 * @param content 任务 2:消息原文(供 UI 提取前后 2 句上下文 + 关键词高亮);
 *   FTS4 snippet 路径用 [contentSnippet] 即可,本字段从 LIKE 路径或 JOIN 投影填充
 */
data class SearchResult(
    val messageId: String,
    val sessionId: String,
    val sessionTitle: String,
    val contentSnippet: String,
    val role: String,
    val createdAt: Long,
    val content: String = "",
)
