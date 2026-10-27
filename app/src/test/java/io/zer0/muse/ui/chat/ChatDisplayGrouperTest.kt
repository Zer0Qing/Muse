package io.zer0.muse.ui.chat

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ChatDisplayGrouper] 的纯逻辑单测(不依赖 Android)。
 *
 * 覆盖:空列表 / 全单条 / 全可聚合 / 单条可聚合不成组 / 首尾与中间夹层 / 索引映射。
 */
class ChatDisplayGrouperTest {

    /** 用 content 当稳定标识(避免依赖实验性的 Uuid.parse)。 */
    private fun msg(marker: String) = UIMessage(role = MessageRole.ASSISTANT, content = marker, createdAt = 1L)

    /** 可聚合 = content 以 "tool" 开头(与业务无关,仅测试用)。 */
    private val isGroupable: (UIMessage) -> Boolean = { it.content.startsWith("tool") }

    private fun contentsOf(items: List<ChatDisplayItem>): List<String> = items.flatMap { item ->
        when (item) {
            is ChatDisplayItem.Single -> listOf(item.msg.content)
            is ChatDisplayItem.Grouped -> item.msgs.map { it.content }
        }
    }

    @Test
    fun `empty list yields empty result`() {
        assertTrue(ChatDisplayGrouper.group(emptyList(), isGroupable).isEmpty())
    }

    @Test
    fun `all non groupable stay single`() {
        val items = ChatDisplayGrouper.group(listOf(msg("a"), msg("b")), isGroupable)
        assertEquals(2, items.size)
        assertTrue(items.all { it is ChatDisplayItem.Single })
        assertEquals(listOf("a", "b"), contentsOf(items))
    }

    @Test
    fun `single groupable also forms a group`() {
        // MIN_RUN_SIZE 已改为 1：一轮只调一个工具或只有一段思考时，
        // 也要收敛进过程卡，而不是以独立的工具/思考块出现。
        val items = ChatDisplayGrouper.group(listOf(msg("tool1")), isGroupable)
        assertEquals(1, items.size)
        val grouped = items.single() as ChatDisplayItem.Grouped
        assertEquals(listOf("tool1"), grouped.msgs.map { it.content })
    }

    @Test
    fun `two or more consecutive groupables form one group`() {
        val items = ChatDisplayGrouper.group(
            listOf(msg("tool1"), msg("tool2"), msg("tool3")),
            isGroupable,
        )
        assertEquals(1, items.size)
        val grouped = items.single() as ChatDisplayItem.Grouped
        assertEquals(listOf("tool1", "tool2", "tool3"), grouped.msgs.map { it.content })
    }

    @Test
    fun `groups split on non groupable in between`() {
        val items = ChatDisplayGrouper.group(
            listOf(msg("tool1"), msg("tool2"), msg("text1"), msg("tool3"), msg("tool4")),
            isGroupable,
        )
        assertEquals(3, items.size)
        assertTrue(items[0] is ChatDisplayItem.Grouped)
        assertTrue(items[1] is ChatDisplayItem.Single)
        assertTrue(items[2] is ChatDisplayItem.Grouped)
        assertEquals(listOf("tool1", "tool2", "text1", "tool3", "tool4"), contentsOf(items))
    }

    @Test
    fun `leading and trailing singles are preserved`() {
        val items = ChatDisplayGrouper.group(
            listOf(msg("text1"), msg("tool1"), msg("tool2"), msg("text2")),
            isGroupable,
        )
        assertEquals(3, items.size)
        assertTrue(items[0] is ChatDisplayItem.Single)
        assertTrue(items[1] is ChatDisplayItem.Grouped)
        assertTrue(items[2] is ChatDisplayItem.Single)
        assertEquals(listOf("text1", "tool1", "tool2", "text2"), contentsOf(items))
    }

    @Test
    fun `index maps every covered message to its item position`() {
        val items = ChatDisplayGrouper.group(
            listOf(msg("text1"), msg("tool1"), msg("tool2"), msg("text2")),
            isGroupable,
        )
        val index = ChatDisplayGrouper.indexByMessageId(items)
        val ids = items.flatMap { it.messageIds }
        assertEquals(4, ids.size)
        // 布局:[Single(text1), Grouped(tool1,tool2), Single(text2)]
        // 组内两条(tool1/tool2)指向同一个列表项下标
        assertEquals(index[ids[1]], index[ids[2]])
        // 单条与组、组与末尾单条各自不同项
        assertTrue(index[ids[0]] != index[ids[1]])
        assertTrue(index[ids[2]] != index[ids[3]])
        // 组内两条消息指向同一个列表项,且该项确实是一个 Grouped
        assertTrue(items[index[ids[1]]!!] is ChatDisplayItem.Grouped)
    }

    @Test
    fun `transparent message does not split the group`() {
        // 一轮回复里，任务卡挂在中间那条消息上：它不参与分组，
        // 但前后两段过程要合并成一组，而不是被它切成两半。
        val tool1 = msg("tool1")
        val plan = msg("plan")
        val tool2 = msg("tool2")
        val items = ChatDisplayGrouper.group(
            messages = listOf(tool1, plan, tool2),
            isGroupable = isGroupable,
            isTransparent = { it.content == "plan" },
        )
        // 期望：一个组（tool1 + tool2），加一条独立的任务卡消息。
        assertEquals(2, items.size)
        val grouped = items[0] as ChatDisplayItem.Grouped
        assertEquals(listOf("tool1", "tool2"), grouped.msgs.map { it.content })
        assertEquals("plan", (items[1] as ChatDisplayItem.Single).msg.content)
    }

    @Test
    fun `transparent message keeps its own position`() {
        val tool1 = msg("tool1")
        val plan = msg("plan")
        val tool2 = msg("tool2")
        val items = ChatDisplayGrouper.group(
            messages = listOf(tool1, plan, tool2),
            isGroupable = isGroupable,
            isTransparent = { it.content == "plan" },
        )
        // 任务卡排在组之后，顺序稳定，不丢消息。
        assertEquals(listOf("tool1", "tool2", "plan"), contentsOf(items))
    }
}
