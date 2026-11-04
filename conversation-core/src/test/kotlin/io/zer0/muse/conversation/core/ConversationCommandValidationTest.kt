package io.zer0.muse.conversation.core

import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * 命令的入参校验（`:conversation-core` 体检第三处）。
 *
 * 命令是"用户按了一下"的载体，构造即校验；这里出问题有两条路：
 * 校得松 → 会话里多出空气泡（用户要再找一次才能删）；
 * 各条支各的规矩 → 后来的人只能靠试。
 *
 * 本轮对齐的两处不对称：
 *  - `Submit.text` 原来只查 `isNotEmpty()`，一串空格算合法提交；
 *  - `EditUser/EditAssistant.content` 完全没查，可以把消息编成空白。
 */
class ConversationCommandValidationTest {

    private fun submit(text: String, sessionId: String = "s1", commandId: String = "c1") =
        ConversationCommand.Submit(
            commandId = commandId,
            sessionId = sessionId,
            text = text,
            selectionSetId = "sel-1",
        )

    private fun editUser(content: String, messageId: String = "m1") = ConversationCommand.EditUser(
        commandId = "c1",
        sessionId = "s1",
        messageId = messageId,
        content = content,
    )

    private fun editAssistant(content: String, messageId: String = "m1") = ConversationCommand.EditAssistant(
        commandId = "c1",
        sessionId = "s1",
        messageId = messageId,
        content = content,
    )

    @Test
    fun `提交有内容的文本是合法的`() {
        val command = submit("帮我看看这份日志")

        assert(command.text == "帮我看看这份日志")
    }

    @Test
    fun `空白文本不能提交`() {
        // 光发一串空格会在会话里留下一条看着是空的用户消息：占位、难认，还得再找一次才能删
        listOf("", " ", "\n", "  \t ").forEach { blank ->
            assertThrows(
                "空白文本「$blank」不该被接受",
                IllegalArgumentException::class.java,
            ) { submit(blank) }
        }
    }

    @Test
    fun `编辑不能把消息改成空白`() {
        listOf("", "   ", "\n")
            .forEach { blank ->
                assertThrows("用户消息编成空白不该被接受", IllegalArgumentException::class.java) { editUser(blank) }
                assertThrows("助手消息编成空白不该被接受", IllegalArgumentException::class.java) { editAssistant(blank) }
            }
    }

    @Test
    fun `命令 id 空白一律不接受`() {
        listOf("", "  ").forEach { blankId ->
            assertThrows(IllegalArgumentException::class.java) { submit("有内容", commandId = blankId) }
            assertThrows(
                "命令 id 空白要拒绝",
                IllegalArgumentException::class.java,
            ) {
                ConversationCommand.Create(
                    commandId = blankId,
                    sessionId = "s1",
                    assistantId = "a1",
                    createdAt = 1L,
                )
            }
        }
    }

    @Test
    fun `会话 id 空白一律不接受`() {
        listOf("", " ").forEach { blank ->
            assertThrows(
                "提交必须落在某个会话里",
                IllegalArgumentException::class.java,
            ) { submit("有内容", sessionId = blank) }
            assertThrows(
                "取消同样要有会话",
                IllegalArgumentException::class.java,
            ) {
                ConversationCommand.Cancel(commandId = "c1", sessionId = blank, turnId = "t1")
            }
        }
    }

    @Test
    fun `审批决定要带全四段身份`() {
        listOf(
            mapOf("turnId" to ""),
            mapOf("generationId" to ""),
            mapOf("toolCallId" to ""),
        ).forEach { missing ->
            assertThrows(
                "缺 ${missing.keys.first()} 就不该接受审批决定",
                IllegalArgumentException::class.java,
            ) {
                ConversationCommand.ResolveApproval(
                    commandId = "c1",
                    sessionId = "s1",
                    turnId = missing["turnId"] ?: "t1",
                    generationId = missing["generationId"] ?: "g1",
                    toolCallId = missing["toolCallId"] ?: "tc1",
                    approved = true,
                )
            }
        }
    }
}
