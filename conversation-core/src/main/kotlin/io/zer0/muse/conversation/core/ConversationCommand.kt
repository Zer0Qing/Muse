package io.zer0.muse.conversation.core

abstract class ConversationCommand internal constructor(
    commandId: String,
) {
    open val commandId: String = commandId

    init {
        require(commandId.isNotBlank()) { "command id must not be blank" }
    }

    data class Create(
        override val commandId: String,
        val sessionId: String?,
        val assistantId: String,
        val createdAt: Long,
    ) : ConversationCommand(requireCommandId(commandId)) {
        init {
            require(assistantId.isNotBlank()) { "assistant id must not be blank" }
        }
    }

    data class Submit(
        override val commandId: String,
        val sessionId: String,
        val text: String,
        val selectionSetId: String,
    ) : ConversationCommand(requireCommandId(commandId)) {
        init {
            require(sessionId.isNotBlank()) { "session id must not be blank" }
            // 原先只查 `isNotEmpty()`，是全类唯一一条"空白也算内容"的校验 ——
            // 光发一串空格过去，会话里就多出一条看着是空的用户消息：占位、难认，
            // 要删还得先找到它。真要支持"只发附件不发字"，该给本命令加附件字段
            // 并在这里明确放宽，而不是靠空白文本绕开。
            require(text.isNotBlank()) { "submitted text must not be blank" }
            require(selectionSetId.isNotBlank()) { "selection set id must not be blank" }
        }
    }

    data class Retry(
        override val commandId: String,
        val sessionId: String,
        val replyId: String,
        val selectionSetId: String,
    ) : ConversationCommand(requireCommandId(commandId)) {
        init {
            require(sessionId.isNotBlank()) { "session id must not be blank" }
            require(replyId.isNotBlank()) { "reply id must not be blank" }
            require(selectionSetId.isNotBlank()) { "selection set id must not be blank" }
        }
    }

    data class Continue(
        override val commandId: String,
        val sessionId: String,
        val replyId: String,
        val selectionSetId: String,
    ) : ConversationCommand(requireCommandId(commandId)) {
        init {
            require(sessionId.isNotBlank()) { "session id must not be blank" }
            require(replyId.isNotBlank()) { "reply id must not be blank" }
            require(selectionSetId.isNotBlank()) { "selection set id must not be blank" }
        }
    }

    data class EditUser(
        override val commandId: String,
        val sessionId: String,
        val messageId: String,
        val content: String,
    ) : ConversationCommand(requireCommandId(commandId)) {
        init {
            require(sessionId.isNotBlank()) { "session id must not be blank" }
            require(messageId.isNotBlank()) { "message id must not be blank" }
            // 编成空白等于把这条消息变成空气泡：用户本意通常是"删掉它"，
            // 而空气泡既占位又难认，删除入口还得再找一次。
            require(content.isNotBlank()) { "edited content must not be blank" }
        }
    }

    data class EditAssistant(
        override val commandId: String,
        val sessionId: String,
        val messageId: String,
        val content: String,
    ) : ConversationCommand(requireCommandId(commandId)) {
        init {
            require(sessionId.isNotBlank()) { "session id must not be blank" }
            require(messageId.isNotBlank()) { "message id must not be blank" }
            // 编成空白等于把这条消息变成空气泡：用户本意通常是"删掉它"，
            // 而空气泡既占位又难认，删除入口还得再找一次。
            require(content.isNotBlank()) { "edited content must not be blank" }
        }
    }

    data class Cancel(
        override val commandId: String,
        val sessionId: String,
        val turnId: String,
    ) : ConversationCommand(requireCommandId(commandId)) {
        init {
            require(sessionId.isNotBlank()) { "session id must not be blank" }
            require(turnId.isNotBlank()) { "turn id must not be blank" }
        }
    }

    data class ResolveApproval(
        override val commandId: String,
        val sessionId: String,
        val turnId: String,
        val generationId: String,
        val toolCallId: String,
        val approved: Boolean,
    ) : ConversationCommand(requireCommandId(commandId)) {
        init {
            require(sessionId.isNotBlank()) { "session id must not be blank" }
            require(turnId.isNotBlank()) { "turn id must not be blank" }
            require(generationId.isNotBlank()) { "generation id must not be blank" }
            require(toolCallId.isNotBlank()) { "tool call id must not be blank" }
        }
    }

    data class Delete(
        override val commandId: String,
        val sessionId: String,
        val messageId: String,
        val deleteFollowing: Boolean,
    ) : ConversationCommand(requireCommandId(commandId)) {
        init {
            require(sessionId.isNotBlank()) { "session id must not be blank" }
            require(messageId.isNotBlank()) { "message id must not be blank" }
        }
    }

    private companion object {
        fun requireCommandId(commandId: String): String {
            require(commandId.isNotBlank()) { "command id must not be blank" }
            return commandId
        }
    }
}
