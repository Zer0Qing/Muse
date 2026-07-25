package io.zer0.muse.transformer

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.muse.data.promptinjection.PromptInjectionEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PromptInjectionTransformer 单元测试。
 *
 * 从 context.extra 读取注入条目,纯函数测试。
 */
class PromptInjectionTransformerTest {

    private val transformer = PromptInjectionTransformer()

    private fun makeInjection(
        name: String = "test-mode",
        content: String = "你是一个测试助手",
        priority: Int = 0,
        insertionPosition: String = "after_system",
        enabled: Boolean = true,
    mode: String = "default",
) = PromptInjectionEntity(
    mode = mode,
        id = name,
        name = name,
        content = content,
        priority = priority,
        insertionPosition = insertionPosition,
        enabled = enabled,
    )

    @Test
    fun `injection inserted after system message`() = runTest {
        val messages = listOf(
            UIMessage(role = MessageRole.SYSTEM, content = "你是一个助手"),
            UIMessage(role = MessageRole.USER, content = "你好"),
        )
        val context = TransformContext(
            extras = mapOf("prompt_injections" to listOf(makeInjection())),
        )
        val result = transformer.transform(messages, context)

        assertEquals(3, result.size)
        assertEquals("你是一个助手", result[0].content)
        assertTrue(result[1].content.contains("[ModeInjection: test-mode]"))
        assertTrue(result[1].content.contains("你是一个测试助手"))
        assertEquals("你好", result[2].content)
    }

    @Test
    fun `injection inserted before system message`() = runTest {
        val messages = listOf(
            UIMessage(role = MessageRole.SYSTEM, content = "你是一个助手"),
            UIMessage(role = MessageRole.USER, content = "你好"),
        )
        val context = TransformContext(
            extras = mapOf("prompt_injections" to listOf(
                makeInjection(insertionPosition = "before_system"),
            )),
        )
        val result = transformer.transform(messages, context)

        assertEquals(3, result.size)
        assertTrue(result[0].content.contains("[ModeInjection:"))
        assertEquals("你是一个助手", result[1].content)
        assertEquals("你好", result[2].content)
    }

    @Test
    fun `no injection when prompt_injections is null`() = runTest {
        val messages = listOf(
            UIMessage(role = MessageRole.SYSTEM, content = "你是一个助手"),
        )
        val context = TransformContext(extras = emptyMap())
        val result = transformer.transform(messages, context)

        assertEquals(messages, result)
    }

    @Test
    fun `disabled injection is skipped`() = runTest {
        val messages = listOf(
            UIMessage(role = MessageRole.SYSTEM, content = "你是一个助手"),
        )
        val context = TransformContext(
            extras = mapOf("prompt_injections" to listOf(makeInjection(enabled = false))),
        )
        val result = transformer.transform(messages, context)

        assertEquals(messages, result)
    }

    @Test
    fun `multiple injections ordered by priority`() = runTest {
        val messages = listOf(
            UIMessage(role = MessageRole.SYSTEM, content = "助手"),
            UIMessage(role = MessageRole.USER, content = "你好"),
        )
        val context = TransformContext(
            extras = mapOf("prompt_injections" to listOf(
                makeInjection(name = "low", priority = 1, content = "低优先级"),
                makeInjection(name = "high", priority = 10, content = "高优先级"),
            )),
        )
        val result = transformer.transform(messages, context)

        assertEquals(4, result.size)
        // 高优先级在前
        assertTrue(result[1].content.contains("高优先级"))
        assertTrue(result[2].content.contains("低优先级"))
    }

    @Test
    fun `no system messages inserts at head`() = runTest {
        val messages = listOf(
            UIMessage(role = MessageRole.USER, content = "只有用户消息"),
        )
        val context = TransformContext(
            extras = mapOf("prompt_injections" to listOf(makeInjection())),
        )
        val result = transformer.transform(messages, context)

        assertEquals(2, result.size)
        assertTrue(result[0].content.contains("[ModeInjection:"))
        assertEquals("只有用户消息", result[1].content)
    }
}
