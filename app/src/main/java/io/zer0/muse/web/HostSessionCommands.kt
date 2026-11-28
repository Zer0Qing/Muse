package io.zer0.muse.web

/** 会话写命令的落地动作,由 [HostWebSocketGateway] 用 ChatViewModel 实现。 */
internal interface HostSessionMutations {
    fun rename(sessionId: String, title: String)

    fun archive(sessionId: String, archived: Boolean)

    fun delete(sessionId: String)
}

/**
 * session.rename / session.archive / session.delete 的实现。
 *
 * 三条命令与 session.select 同属写命令:先过焦点锁,未持焦点的连接拿到
 * `focus_not_held` 错误事件,且不会触碰任何会话数据。删除的二次确认由客户端负责,
 * 服务端只执行。
 */
internal class HostSessionCommands(
    private val focus: HostFocusTokenHolder,
    private val mutations: HostSessionMutations,
) {

    companion object {
        const val TYPE_RENAME = "session.rename"
        const val TYPE_ARCHIVE = "session.archive"
        const val TYPE_DELETE = "session.delete"

        const val EVENT_ERROR = "error"
        const val EVENT_ACCEPTED = "command.accepted"
        const val ERROR_FOCUS_NOT_HELD = "focus_not_held"

        val types: Set<String> = setOf(TYPE_RENAME, TYPE_ARCHIVE, TYPE_DELETE)
    }

    @Suppress("ThrowsCount")
    suspend fun handle(command: HostCommand, connectionId: String): HostEvent {
        val requestId = command.requestId
        if (!focus.holds(connectionId, command.sessionId)) {
            return HostEvent(type = EVENT_ERROR, requestId = requestId, error = ERROR_FOCUS_NOT_HELD)
        }
        val sessionId = command.sessionId?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw HostProtocolException("${command.type} requires sessionId")
        return when (command.type) {
            TYPE_RENAME -> {
                val title = command.title?.trim().orEmpty()
                if (title.isEmpty()) throw HostProtocolException("session.rename requires title")
                mutations.rename(sessionId, title)
                accepted(requestId, sessionId)
            }
            TYPE_ARCHIVE -> {
                val archived = command.archived ?: throw HostProtocolException("session.archive requires archived")
                mutations.archive(sessionId, archived)
                accepted(requestId, sessionId)
            }
            TYPE_DELETE -> {
                mutations.delete(sessionId)
                accepted(requestId, sessionId)
            }
            else -> throw HostProtocolException("unsupported session command: ${command.type}")
        }
    }

    private fun accepted(requestId: String?, sessionId: String): HostEvent = HostEvent(
        type = EVENT_ACCEPTED,
        requestId = requestId,
        sessionId = sessionId,
    )
}
