package io.zer0.muse.data.chat

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * v2.5.3 (P3-1): 编辑助手消息 = 新增版本节点，旧版本永久保留。
 */
class ConversationTreeAssistantEditTest {

    private fun userAndReply(): Triple<ConversationTree, UIMessage, UIMessage> {
        val userId = Uuid.random()
        val replyGroup = "asg_reply"
        val user =
            UIMessage(
                id = userId,
                role = MessageRole.USER,
                content = "你好",
                createdAt = 100L,
                variantGroupId = "usg_1",
            )
        val reply =
            UIMessage(
                id = Uuid.random(),
                role = MessageRole.ASSISTANT,
                content = "原始回复",
                createdAt = 101L,
                variantGroupId = replyGroup,
                parentGroupId = userId.toString(),
            )
        val tree =
            ConversationTree.build(
                listOf(
                    user,
                    reply.copy(variantGroupId = replyGroup, variantIndex = 0, variantCount = 1),
                ),
            )
        return Triple(tree, user, reply)
    }

    @Test
    fun `editing assistant appends a new variant and keeps the old one`() {
        val (tree, _, reply) = userAndReply()
        val update = tree.editAssistantMessage(reply.id, "编辑后的回复")
        assertTrue("编辑应返回新树", update?.newMessage != null)
        val newMsg = update!!.newMessage!!
        assertNotEquals("新版本必须用新 id，不能覆盖旧版本", reply.id, newMsg.id)
        assertEquals("编辑后的回复", newMsg.content)
        // 旧版本仍在树里
        val allAssistantContents =
            update.tree.userNodes
                .flatMap { it.variants }
                .flatMap { it.assistantNodes }
                .flatMap { it.variants }
                .map { it.content }
        assertTrue("旧版本应保留: $allAssistantContents", allAssistantContents.contains("原始回复"))
        assertTrue("新版本应存在: $allAssistantContents", allAssistantContents.contains("编辑后的回复"))
    }

    @Test
    fun `editing selects the new variant as current`() {
        val (tree, _, reply) = userAndReply()
        val update = tree.editAssistantMessage(reply.id, "编辑后的回复")!!
        val displayContents = update.tree.displayMessages.map { it.content }
        assertTrue("当前显示应为新版本: $displayContents", displayContents.contains("编辑后的回复"))
    }

    @Test
    fun `editing unknown message returns null`() {
        val (tree, _, _) = userAndReply()
        assertNull(tree.editAssistantMessage(Uuid.random(), "x"))
    }
}
