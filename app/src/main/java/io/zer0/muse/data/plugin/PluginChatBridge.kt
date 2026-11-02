package io.zer0.muse.data.plugin

import io.zer0.muse.data.session.SessionRepository
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 协程上下文元素：本次插件调用发生在哪个会话。
 *
 * 为什么用协程上下文而不是给 `execPluginTool` 加参数：
 *  - 那个函数的 `ReturnCount` 被 detekt 基线**按签名**压制，加参数会让压制失效、
 *    六个早退集体变红（本会话已在 `SkillEngine.callFunction` 上踩过同一件事）；
 *  - 更重要的是语义：会话身份是"随这次调用带进来的环境"，不是插件可见的输入。
 *    插件参数里的 `session_id` 一律不读，否则一个插件就能跨会话捞别人的记录。
 */
class PluginSessionContext(val sessionId: String) :
    kotlin.coroutines.AbstractCoroutineContextElement(PluginSessionContext) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<PluginSessionContext>
}

/**
 * 插件读会话的通道（`chat_read`）。
 *
 * 拆成一个显式通道而不是让 [PluginHostApi] 直接持有 [SessionRepository]，理由有两个：
 *  1. 那样宿主 API 就依赖整个会话层，JVM 上没法单测授权与限额逻辑；
 *  2. 会话身份必须**由宿主链路注入**。插件如果在参数里自填 `session_id`，
 *     一个插件就能跨会话捞别人的聊天记录 —— 所以这里的入参是宿主传进来的 sessionId，
 *     插件的参数里那个字段一律忽略。
 */
class PluginChatBridge(
    private val repository: SessionRepository,
) {

    /** 一行会话内容。只给必要字段，不给内部 id/签名/变体树。 */
    data class Line(val role: String, val content: String, val createdAt: Long)

    /**
     * 读最近 [limit] 条，返回给插件的 JSON 数组文本。
     *
     * 行数由调用方夹好上限：让插件一次要"全部历史"等于把整个会话塞进它自己的上下文与回传里，
     * 既慢也容易把内容带出到不该去的地方。
     */
    suspend fun readRecent(sessionId: String, limit: Int): List<Line> =
        repository.getRecentMessages(sessionId, limit).map { message ->
            Line(
                role = message.role.name.lowercase(),
                content = message.content,
                createdAt = message.createdAt,
            )
        }

    companion object {
        /** [SkillExecutor.execute] 的老默认值：代表「没有绑定真实会话」。 */
        const val SESSION_PLACEHOLDER = "default"

        /** 一次调用最多能读多少条。 */
        const val MAX_LINES = 30

        fun render(lines: List<Line>): String = buildJsonArray {
            lines.forEach { line ->
                add(
                    buildJsonObject {
                        put("role", line.role)
                        put("content", line.content)
                        put("createdAt", line.createdAt)
                    },
                )
            }
        }.toString()
    }
}
