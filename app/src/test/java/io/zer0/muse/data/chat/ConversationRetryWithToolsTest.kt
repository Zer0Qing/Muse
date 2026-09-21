package io.zer0.muse.data.chat

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.ToolCallInfo
import io.zer0.ai.core.UIMessage
import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.0.92: 工具调用参与时的重试树行为 (回归测试)。
 *
 * 背景: 工具展示消息 ([UIMessage.toolCallInfo] != null) 在树里是独立节点,
 * 旧版 `retryLastAssistant` 直接取 `assistantNodes.last()`,有工具调用时会把
 * 新变体挂进"工具组"(工具卡变成 工具/回复 的 1/2 变体);修复后只认"回复节点"。
 */
class ConversationRetryWithToolsTest {

    private fun user(content: String, at: Long) = UIMessage(
        id = Uuid.random(),
        role = MessageRole.USER,
        content = content,
        createdAt = at,
    )

    private fun assistant(
        content: String,
        at: Long,
        group: String? = null,
        index: Int = 0,
        count: Int = 1,
        parent: String? = null,
        toolName: String? = null,
    ) = UIMessage(
        id = Uuid.random(),
        role = MessageRole.ASSISTANT,
        content = content,
        createdAt = at,
        variantGroupId = group,
        variantIndex = index,
        variantCount = count,
        parentGroupId = parent,
        toolCallInfo = toolName?.let { ToolCallInfo(it, "{}", "结果", true) },
    )

    @Test
    fun retry_withTrailingToolNodes_targetsReplyNode_notToolNode() {
        val t0 = 1_700_000_000_000L
        val u = user("帮我查下时间和天气", t0)
        val a0 = assistant("", t0 + 1)
        val t1 = assistant("", t0 + 100, toolName = "get_current_time")
        val t2 = assistant("", t0 + 200, toolName = "get_weather")
        val a0final = a0.copy(content = "现在12点,今天晴")

        val tree = ConversationTree.build(listOf(u, a0final, t1, t2))
        val update = tree.retryLastAssistant()

        // 新变体必须落在"回复组"(= a0 的组),而不是工具组。
        assertEquals(a0.id.toString(), update.newMessage?.variantGroupId)
        val variant = update.tree.userNodes.first().currentVariant!!
        // 回复节点变体数 2,工具节点变体数保持 1。
        val replyNode = variant.assistantNodes.first { it.groupId == a0.id.toString() }
        assertEquals(2, replyNode.variants.size)
        val toolNodes = variant.assistantNodes.filter { it.groupId != a0.id.toString() }
        assertEquals(2, toolNodes.size)
        assertTrue("工具节点不得被追加变体", toolNodes.all { it.variants.size == 1 })
        // 新变体裁剪到选中。
        assertEquals(1, replyNode.selectIndex)
    }

    @Test
    fun retry_onlyToolNodes_createsFreshReplyGroupAtEnd() {
        val t0 = 1_700_000_000_000L
        val u = user("帮我查下", t0)
        val t1 = assistant("", t0 + 100, toolName = "get_current_time")
        val t2 = assistant("", t0 + 200, toolName = "get_weather")

        val tree = ConversationTree.build(listOf(u, t1, t2))
        val update = tree.retryLastAssistant()

        val newGroup = update.newMessage?.variantGroupId
        assertNotEquals(t1.variantGroupId ?: t1.id.toString(), newGroup)
        assertNotEquals(t2.id.toString(), newGroup)
        val variant = update.tree.userNodes.first().currentVariant!!
        // 新回复组追加在末尾。
        assertEquals(newGroup, variant.assistantNodes.last().groupId)
        assertEquals(1, variant.assistantNodes.last().variants.size)
        assertEquals("", variant.assistantNodes.last().currentVariant?.content)
    }
}
