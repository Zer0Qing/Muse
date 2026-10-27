package io.zer0.muse.transformer

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.memory.summary.ContextCheckpointEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话检查点合并的语义测试。
 *
 * 这一层的失败模式很隐蔽：合并错了不会报错，只会让模型"忘了用户说过的话"或"重复看到已经
 * 压掉的内容"。所以这里逐条锁死：什么必须留、什么可以压、边界怎么定位、异常输入怎么办。
 */
class ContextCheckpointMergeTest {

    private fun msg(role: MessageRole, text: String) = UIMessage(role = role, content = text)

    private fun checkpoint(boundaryId: String, summary: String = "在修压缩", coveredSeq: Long = 42L) = ContextCheckpointEntity(
        sessionId = "s-1",
        coveredSeq = coveredSeq,
        lastCoveredMessageId = boundaryId,
        coveredCount = 3,
        summary = summary,
        tokensBefore = 9_000,
        tokensAfter = 900,
        updatedAt = 1_700_000_000_000L,
    )

    @Test
    fun `compresses assistant side before boundary and keeps everything after`() {
        val sys = msg(MessageRole.SYSTEM, "你是 Muse")
        val user1 = msg(MessageRole.USER, "帮我看下压缩")
        val assistant1 = msg(MessageRole.ASSISTANT, "好的，我先读代码……（很长）")
        val boundary = msg(MessageRole.ASSISTANT, "读完了，压缩在 transformer 里")
        val user2 = msg(MessageRole.USER, "那改成落库")
        val assistant2 = msg(MessageRole.ASSISTANT, "这就改")

        val result = ContextCheckpointMerge.apply(
            listOf(sys, user1, assistant1, boundary, user2, assistant2),
            checkpoint(boundary.id.toString()),
        )

        assertTrue(result.applied)
        // 摘要排在最前
        assertTrue(result.messages.first().content.startsWith(ContextCheckpointMerge.MARKER))
        assertTrue(result.messages.first().content.contains("在修压缩"))
        // 用户消息与系统前缀必须保留（这是"用户到底说了什么"的原始记录）
        assertEquals(setOf("你是 Muse", "帮我看下压缩", "那改成落库", "这就改"), retainedContents(result))
        // 边界之前的助手长内容被摘要取代
        assertFalse(result.messages.any { it.content == assistant1.content })
        assertFalse(result.messages.any { it.content == boundary.content })
    }

    @Test
    fun `keeps user message even when it sits before the boundary`() {
        val user1 = msg(MessageRole.USER, "记住：预算上限 500")
        val assistant1 = msg(MessageRole.ASSISTANT, "记下了")
        val boundary = msg(MessageRole.ASSISTANT, "继续")
        val result = ContextCheckpointMerge.apply(
            listOf(user1, assistant1, boundary),
            checkpoint(boundary.id.toString()),
        )
        assertTrue(result.applied)
        assertTrue(result.messages.any { it.content == "记住：预算上限 500" })
    }

    @Test
    fun `boundary not found means no merge`() {
        val history = listOf(msg(MessageRole.USER, "a"), msg(MessageRole.ASSISTANT, "b"))
        val result = ContextCheckpointMerge.apply(history, checkpoint("missing-id"))
        assertFalse(result.applied)
        assertEquals(history, result.messages)
    }

    @Test
    fun `partial paged history can apply checkpoint by covered sequence`() {
        val system = msg(MessageRole.SYSTEM, "系统")
        val user = msg(MessageRole.USER, "当前问题")
        val assistantAfter =
            msg(MessageRole.ASSISTANT, "边界之后的回复").copy(seq = 50, commitSeq = 2)
        val toolAfter =
            msg(MessageRole.TOOL, "边界之后的工具结果").copy(seq = 51, commitSeq = 1)

        val result =
            ContextCheckpointMerge.apply(
                listOf(system, user, assistantAfter, toolAfter),
                checkpoint(boundaryId = "older-message", coveredSeq = 42L),
            )

        assertTrue("分页窗口不含边界时仍应使用持久检查点摘要", result.applied)
        assertTrue(result.messages.first().content.startsWith(ContextCheckpointMerge.MARKER))
        assertTrue(result.messages.any { it.content == "当前问题" })
        assertTrue(result.messages.any { it.content == "边界之后的回复" })
        assertTrue(result.messages.any { it.content == "边界之后的工具结果" })
    }

    @Test
    fun `blank boundary id means no merge`() {
        val history = listOf(msg(MessageRole.USER, "a"), msg(MessageRole.ASSISTANT, "b"))
        assertFalse(ContextCheckpointMerge.apply(history, checkpoint("")).applied)
    }

    @Test
    fun `null checkpoint and empty history are no-ops`() {
        val history = listOf(msg(MessageRole.USER, "a"))
        assertFalse(ContextCheckpointMerge.apply(history, null).applied)
        assertFalse(ContextCheckpointMerge.apply(emptyList(), checkpoint("x")).applied)
    }

    @Test
    fun `does not insert a second summary when one is already present`() {
        val existing = msg(MessageRole.SYSTEM, "${ContextCheckpointMerge.MARKER} 历史对话摘要（更早的内容已压缩）\n\n旧摘要")
        val boundary = msg(MessageRole.ASSISTANT, "边界")
        val user = msg(MessageRole.USER, "新问题")
        val history = listOf(existing, boundary, user)

        val result = ContextCheckpointMerge.apply(history, checkpoint(boundary.id.toString()))
        assertFalse("已有摘要时不应重复插入", result.applied)
        assertEquals(history, result.messages)
    }

    @Test
    fun `last message as boundary keeps the summary only`() {
        val boundary = msg(MessageRole.ASSISTANT, "最后一条")
        val result = ContextCheckpointMerge.apply(listOf(boundary), checkpoint(boundary.id.toString()))
        assertTrue(result.applied)
        assertEquals(1, result.messages.size)
        assertTrue(result.messages.first().content.startsWith(ContextCheckpointMerge.MARKER))
    }

    @Test
    fun `summary content is trimmed and carries the marker for the divider`() {
        val boundary = msg(MessageRole.ASSISTANT, "x")
        val result = ContextCheckpointMerge.apply(
            listOf(boundary),
            checkpoint(boundary.id.toString(), summary = "   有前后空白的摘要   "),
        )
        val content = result.messages.first().content
        assertTrue(content.contains("有前后空白的摘要"))
        assertFalse("不应把多余空白带进上下文", content.endsWith("   "))
    }

    @Test
    fun `covered ids match exactly what apply would replace`() {
        val sys = msg(MessageRole.SYSTEM, "系统")
        val user1 = msg(MessageRole.USER, "问题")
        val assistant1 = msg(MessageRole.ASSISTANT, "很长的回复")
        val boundary = msg(MessageRole.ASSISTANT, "边界")
        val user2 = msg(MessageRole.USER, "后续")
        val history = listOf(sys, user1, assistant1, boundary, user2)
        val cp = checkpoint(boundary.id.toString())

        val covered = ContextCheckpointMerge.coveredMessageIds(history, cp)
        assertEquals(setOf(assistant1.id.toString(), boundary.id.toString()), covered)

        // 与 apply 实际替换掉的内容必须一致：用户消息和系统前缀既不被替代，也不算"省下的上下文"
        val applied = ContextCheckpointMerge.apply(history, cp)
        val keptIds = applied.messages.map { it.id.toString() }.toSet()
        covered.forEach { id ->
            assertFalse("被标记为覆盖的消息不应留在合并结果里: $id", id in keptIds)
        }
    }

    @Test
    fun `covered ids are empty when boundary is missing or blank`() {
        val history = listOf(msg(MessageRole.ASSISTANT, "x"))
        assertTrue(ContextCheckpointMerge.coveredMessageIds(history, null).isEmpty())
        assertTrue(ContextCheckpointMerge.coveredMessageIds(history, checkpoint("")).isEmpty())
        assertTrue(ContextCheckpointMerge.coveredMessageIds(history, checkpoint("nope")).isEmpty())
    }

    private fun retainedContents(result: ContextCheckpointMerge.Result): Set<String> =
        result.messages.filterNot { it.content.startsWith(ContextCheckpointMerge.MARKER) }
            .mapTo(mutableSetOf()) { it.content }
}
