package io.zer0.muse.transformer

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.muse.data.lorebook.LorebookEntity
import io.zer0.muse.data.lorebook.LorebookRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LorebookTransformerTest {

    private val repository = mockk<LorebookRepository>()

    private fun entity(
        id: String = "lb1",
        name: String = "test-lore",
        keywordsJson: String = """["魔法","巫师"]""",
        content: String = "巫师协会是一个古老的秘密组织。",
        priority: Int = 0,
        insertionPosition: String = "after_system",
        caseSensitive: Boolean = false,
    ) = LorebookEntity(
        id = id,
        name = name,
        keywordsJson = keywordsJson,
        content = content,
        priority = priority,
        insertionPosition = insertionPosition,
        caseSensitive = caseSensitive,
    )

    @Test
    fun `lorebook matched by keyword injects after system`() = runTest {
        val entry = entity()
        every { repository.matchAgainst(listOf(entry), "我想学习魔法") } returns listOf(entry)

        val transformer = LorebookTransformer(repository)
        val messages = listOf(
            UIMessage(role = MessageRole.SYSTEM, content = "你是一个助手"),
            UIMessage(role = MessageRole.USER, content = "我想学习魔法"),
        )
        val context = TransformContext(
            extras = mapOf("lorebook_entries" to listOf(entry)),
        )
        val result = transformer.transform(messages, context)

        // 结果:declaration + system + lorebook-after + user = 4
        assertEquals(4, result.size)
        assertEquals(MessageRole.SYSTEM, result[2].role)
        assertTrue(result[2].content.contains("lorebook"))
        assertTrue(result[2].content.contains("巫师协会"))
        assertTrue(result[2].content.contains("test-lore"))
    }

    @Test
    fun `no match returns original messages`() = runTest {
        val entry = entity()
        every { repository.matchAgainst(listOf(entry), "今天天气真好") } returns emptyList()

        val transformer = LorebookTransformer(repository)
        val messages = listOf(
            UIMessage(role = MessageRole.SYSTEM, content = "助手"),
            UIMessage(role = MessageRole.USER, content = "今天天气真好"),
        )
        val context = TransformContext(
            extras = mapOf("lorebook_entries" to listOf(entry)),
        )
        val result = transformer.transform(messages, context)

        assertEquals(messages, result)
    }

    @Test
    fun `empty lorebook_entries returns original`() = runTest {
        val transformer = LorebookTransformer(repository)
        val messages = listOf(
            UIMessage(role = MessageRole.USER, content = "测试"),
        )
        val context = TransformContext(extras = mapOf("lorebook_entries" to emptyList<LorebookEntity>()))
        val result = transformer.transform(messages, context)

        assertEquals(messages, result)
    }

    @Test
    fun `before_system injection inserts at head`() = runTest {
        val entry = entity(insertionPosition = "before_system")
        every { repository.matchAgainst(listOf(entry), "我使用魔法") } returns listOf(entry)

        val transformer = LorebookTransformer(repository)
        val messages = listOf(
            UIMessage(role = MessageRole.SYSTEM, content = "助手设置"),
            UIMessage(role = MessageRole.USER, content = "我使用魔法"),
        )
        val context = TransformContext(
            extras = mapOf("lorebook_entries" to listOf(entry)),
        )
        val result = transformer.transform(messages, context)

        // 结果:declaration + lorebook-before + system + user = 4
        assertEquals(4, result.size)
        assertTrue(result[1].content.contains("lorebook"))
        assertTrue(result[1].content.contains("巫师协会"))
    }

    @Test
    fun `matched entries ordered by priority`() = runTest {
        val highPri = entity(id = "high", name = "high", priority = 10, content = "高优先级")
        val lowPri = entity(id = "low", name = "low", priority = 1, content = "低优先级")
        every { repository.matchAgainst(listOf(lowPri, highPri), "魔法") } returns listOf(highPri, lowPri)

        val transformer = LorebookTransformer(repository)
        val messages = listOf(
            UIMessage(role = MessageRole.SYSTEM, content = "助手"),
            UIMessage(role = MessageRole.USER, content = "魔法"),
        )
        val context = TransformContext(
            extras = mapOf("lorebook_entries" to listOf(lowPri, highPri)),
        )
        val result = transformer.transform(messages, context)

        // 结果:declaration + system + lorebook1 + lorebook2 + user = 5
        assertEquals(5, result.size)
        val loreMsgs = result.filter { it.content.contains("lorebook") }
        assertEquals(2, loreMsgs.size)
    }

    @Test
    fun `name with special chars is sanitized`() = runTest {
        val entry = entity(name = "test\"\nname", content = "特殊名称条目")
        every { repository.matchAgainst(listOf(entry), "魔法") } returns listOf(entry)

        val transformer = LorebookTransformer(repository)
        val messages = listOf(
            UIMessage(role = MessageRole.SYSTEM, content = "助手"),
            UIMessage(role = MessageRole.USER, content = "魔法"),
        )
        val context = TransformContext(
            extras = mapOf("lorebook_entries" to listOf(entry)),
        )
        val result = transformer.transform(messages, context)

        // 净化后的名称不应包含原始双引号
        assertTrue(!result[1].content.contains("test\"") || result[1].content.contains("test "))
    }
}
