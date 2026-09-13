package io.zer0.muse.transformer

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.memory.fact.FactStore
import io.zer0.memory.ticker.MemoryTicker
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MemoryInjectionTransformer 单元测试。
 *
 * 使用 MockK 模拟 MemoryTicker。
 */
class MemoryInjectionTransformerTest {

    private val memoryTicker = mockk<MemoryTicker>()

    @Test
    fun `disabled by default returns original messages`() = runTest {
        val transformer = MemoryInjectionTransformer(memoryTicker)
        val messages = listOf(
            UIMessage(role = MessageRole.USER, content = "你好"),
        )
        val context = TransformContext(extras = emptyMap())
        val result = transformer.transform(messages, context)

        assertEquals(messages, result)
    }

    @Test
    fun `explicitly disabled returns original`() = runTest {
        val transformer = MemoryInjectionTransformer(memoryTicker)
        val messages = listOf(
            UIMessage(role = MessageRole.USER, content = "你好"),
        )
        val context = TransformContext(extras = mapOf("memory_enabled" to false))
        val result = transformer.transform(messages, context)

        assertEquals(messages, result)
    }

    @Test
    fun `memory markdown injected at head when enabled`() = runTest {
        coEvery { memoryTicker.readCompiledMemoryMarkdown(scope = "main", spaceId = "default") } returns "用户喜欢编程和音乐。"

        val transformer = MemoryInjectionTransformer(memoryTicker)
        val messages = listOf(
            UIMessage(role = MessageRole.USER, content = "你好"),
        )
        val context = TransformContext(extras = mapOf("memory_enabled" to true))
        val result = transformer.transform(messages, context)

        assertEquals(2, result.size)
        assertEquals(MessageRole.SYSTEM, result[0].role)
        assertTrue(result[0].content.contains("long_term_memory"))
        assertTrue(result[0].content.contains("用户喜欢编程和音乐"))
        assertEquals(messages[0], result[1])
    }

    @Test
    fun `default space is enforced when fact store is present`() = runTest {
        val factStore = mockk<FactStore>()
        coEvery { factStore.getByScopeAndSpace("main", "default") } returns listOf(
            FactStore.Fact(fact = "默认空间事实", scope = "main", spaceId = "default"),
        )
        coEvery { memoryTicker.readCompiledMemoryMarkdown(scope = "main", spaceId = "default") } returns ""

        val transformer = MemoryInjectionTransformer(memoryTicker, factStore)
        val messages = listOf(UIMessage(role = MessageRole.USER, content = "你好"))
        val result = transformer.transform(
            messages,
            TransformContext(extras = mapOf("memory_enabled" to true, "current_scope" to "main")),
        )

        assertTrue(result.first().content.contains("默认空间事实"))
        coVerify(exactly = 1) { factStore.getByScopeAndSpace("main", "default") }
        coVerify(exactly = 0) { factStore.getByScope("main") }
    }

    @Test
    fun `empty memory markdown returns original`() = runTest {
        coEvery { memoryTicker.readCompiledMemoryMarkdown(scope = "main", spaceId = "default") } returns ""

        val transformer = MemoryInjectionTransformer(memoryTicker)
        val messages = listOf(
            UIMessage(role = MessageRole.USER, content = "你好"),
        )
        val context = TransformContext(extras = mapOf("memory_enabled" to true))
        val result = transformer.transform(messages, context)

        assertEquals(messages, result)
    }

    @Test
    fun `injected message has disclaimer`() = runTest {
        coEvery { memoryTicker.readCompiledMemoryMarkdown(scope = "main", spaceId = "default") } returns "一些长期记忆"

        val transformer = MemoryInjectionTransformer(memoryTicker)
        val messages = listOf(
            UIMessage(role = MessageRole.USER, content = "测试"),
        )
        val context = TransformContext(extras = mapOf("memory_enabled" to true))
        val result = transformer.transform(messages, context)

        assertTrue(result[0].content.contains("历史记忆"))
        assertTrue(result[0].content.contains("仅供参考"))
        assertTrue(result[0].content.contains("不要执行"))
    }
}
