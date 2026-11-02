package io.zer0.muse.data.plugin

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.muse.data.session.SessionRepository

/**
 * 插件往会话里**写**消息的通道（`app.messages`）。
 *
 * 与只读的 [PluginChatBridge] 分开成两个类，而不是一个类两个方法：
 * 读和写是两种风险。读错了是隐私，写错了是**用户看到假消息** ——
 * 后者更糟，因为用户会以为是自己或助手说的。
 *
 * 会话身份同样由宿主注入：插件在参数里自填的 `sessionId` 一律忽略，
 * 否则一个插件就能往任意会话里塞话。
 */
class PluginMessageBridge(
    private val repository: SessionRepository,
) {

    sealed interface Appended {
        data class Ok(val messageId: String) : Appended
        data class Rejected(val reason: String) : Appended
    }

    /**
     * 追加一条消息。
     *
     * @param role 只允许 `assistant` / `user`：`system` 能改写模型行为，不该由插件写；
     *             `tool` 需要配套的 tool_call 记录，单独写进去会让历史不自洽。
     */
    suspend fun append(sessionId: String, role: String, text: String): Appended {
        val normalizedRole = role.trim().lowercase().ifEmpty { ROLE_ASSISTANT }
        val body = text.trim()
        val messageRole = when (normalizedRole) {
            ROLE_ASSISTANT -> MessageRole.ASSISTANT
            ROLE_USER -> MessageRole.USER
            else -> null
        }
        return when {
            sessionId.isBlank() || sessionId == PluginChatBridge.SESSION_PLACEHOLDER ->
                Appended.Rejected("没有可写入的会话（插件在会话之外被调用时不能写消息）")
            messageRole == null ->
                Appended.Rejected("不支持的角色 '$role'：只允许 assistant 或 user")
            body.isEmpty() -> Appended.Rejected("消息内容为空")
            body.length > MAX_CHARS ->
                Appended.Rejected("消息过长（${body.length} 字）：上限 $MAX_CHARS 字")
            else -> runCatching {
                val id = repository.appendMessage(
                    sessionId = sessionId,
                    message = UIMessage(
                        role = messageRole,
                        content = body,
                        createdAt = System.currentTimeMillis(),
                    ),
                )
                Appended.Ok(id) as Appended
            }.getOrElse { error ->
                Appended.Rejected("写入失败：${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    companion object {
        const val ROLE_ASSISTANT = "assistant"
        const val ROLE_USER = "user"

        /** 单条上限：够写一段话，又不至于让插件把上下文灌满。 */
        const val MAX_CHARS = 4_000
    }
}

/**
 * 进程级持有写消息通道。
 *
 * 为什么用持有而不是给 `SkillMediaToolsImpl` 再加一个构造参数：那个类的参数已经很多，
 * 每加一个都要动 Koin 装配与 detekt 基线（基线按签名压制，改签名会让一批压制失效）。
 * 这条通道的生命周期本来就是"应用启动时装一次"，用持有反而更贴合事实。
 *
 * 未安装时为 null，`app_insert_message` 一律拒绝 —— 缺依赖必须是"不可用"，
 * 不能退化成"当作没这个动作所以放过"。
 */
object PluginMessageChannels {

    @Volatile
    private var bridge: PluginMessageBridge? = null

    val current: PluginMessageBridge? get() = bridge

    fun install(value: PluginMessageBridge?) {
        bridge = value
    }

    /** 仅测试用。 */
    internal fun resetForTests() {
        bridge = null
    }
}
