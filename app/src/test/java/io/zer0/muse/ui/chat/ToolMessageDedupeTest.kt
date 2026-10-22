package io.zer0.muse.ui.chat

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.ToolCallInfo
import io.zer0.ai.core.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 产物重复渲染去重的测试。
 *
 * 用户反馈「一个卡里面和那个地方展示两个卡片」：工具轮会落两条消息 ——
 * 一条助手消息挂着任务卡（步骤里已经画了卡片），外加每个工具一条展示消息
 * （它自己也走产物直出）。不去重就会看到同一张卡上下各一份。
 */
class ToolMessageDedupeTest {

    private fun assistant(id: Uuid = Uuid.random()) = UIMessage(
        id = id,
        role = MessageRole.ASSISTANT,
        content = "",
    )

    private fun toolMessage(parentId: Uuid, toolName: String = "show_card") = UIMessage(
        role = MessageRole.ASSISTANT,
        content = "",
        parentMessageId = parentId.toString(),
        toolCallInfo = ToolCallInfo(
            toolName = toolName,
            arguments = """{"title":"card"}""",
            result = "Card rendered",
            isSuccess = true,
        ),
    )

    @Test
    fun `tool message under a message with task card is covered`() {
        val owner = assistant()
        val tool = toolMessage(owner.id)

        val covered = toolMessageIdsCoveredByTaskCards(
            messages = listOf(owner, tool),
            taskCardOwnerIds = setOf(owner.id.toString()),
        )

        assertEquals(setOf(tool.id.toString()), covered)
    }

    @Test
    fun `tool message under a message without task card is not covered`() {
        val owner = assistant()
        val tool = toolMessage(owner.id)

        val covered = toolMessageIdsCoveredByTaskCards(
            messages = listOf(owner, tool),
            taskCardOwnerIds = emptySet(),
        )

        assertTrue("没有任务卡就没有重复来源,不该隐藏产物", covered.isEmpty())
    }

    @Test
    fun `tool message whose parent has no task card stays visible`() {
        val cardOwner = assistant()
        val otherOwner = assistant()
        val toolUnderOther = toolMessage(otherOwner.id)

        val covered = toolMessageIdsCoveredByTaskCards(
            messages = listOf(cardOwner, otherOwner, toolUnderOther),
            taskCardOwnerIds = setOf(cardOwner.id.toString()),
        )

        assertTrue("父消息没有任务卡时产物必须保留", covered.isEmpty())
    }

    @Test
    fun `tool message without parent linkage is never covered`() {
        val orphan = UIMessage(
            role = MessageRole.ASSISTANT,
            content = "",
            toolCallInfo = ToolCallInfo(
                toolName = "show_card",
                arguments = "{}",
                result = "ok",
                isSuccess = true,
            ),
        )

        val covered = toolMessageIdsCoveredByTaskCards(
            messages = listOf(orphan),
            taskCardOwnerIds = setOf(orphan.id.toString()),
        )

        assertTrue("没有父关联就无从判断重复,保守起见保留", covered.isEmpty())
    }

    @Test
    fun `messages without tool info are never covered`() {
        val owner = assistant()
        val plain = UIMessage(
            role = MessageRole.ASSISTANT,
            content = "普通回复",
            parentMessageId = owner.id.toString(),
        )

        val covered = toolMessageIdsCoveredByTaskCards(
            messages = listOf(owner, plain),
            taskCardOwnerIds = setOf(owner.id.toString()),
        )

        assertTrue(covered.isEmpty())
    }

    @Test
    fun `multiple tool messages under the same task card are all covered`() {
        val owner = assistant()
        val first = toolMessage(owner.id, "show_card")
        val second = toolMessage(owner.id, "generate_image")

        val covered = toolMessageIdsCoveredByTaskCards(
            messages = listOf(owner, first, second),
            taskCardOwnerIds = setOf(owner.id.toString()),
        )

        assertEquals(setOf(first.id.toString(), second.id.toString()), covered)
    }
}
