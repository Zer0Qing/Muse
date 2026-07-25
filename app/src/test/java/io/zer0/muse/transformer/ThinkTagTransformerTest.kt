package io.zer0.muse.transformer

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ThinkTagTransformer + stripThinkTags 单元测试。
 *
 * ThinkTagTransformer 用于把 ASSISTANT 消息中的 `<think>...</think>` 标签提取到 reasoning 字段。
 * stripThinkTags 是独立工具函数,用于翻译路径的 think 标签清理。
 */
class ThinkTagTransformerTest {

    private val transformer = ThinkTagTransformer()

    // ======================== ThinkTagTransformer ========================

    @Test
    fun `think tag extracted to reasoning field`() = runTest {
        val messages = listOf(
            UIMessage(
                role = MessageRole.ASSISTANT,
                content = "<think>这是思考过程</think>这是最终回复",
            ),
        )
        val result = transformer.transform(messages, TransformContext())

        assertEquals(1, result.size)
        assertEquals("这是思考过程", result[0].reasoning)
        assertEquals("这是最终回复", result[0].content)
    }

    @Test
    fun `think tag removed from content`() = runTest {
        val messages = listOf(
            UIMessage(
                role = MessageRole.ASSISTANT,
                content = "<think>一步步分析</think>所以答案是42。",
            ),
        )
        val result = transformer.transform(messages, TransformContext())

        assertEquals("所以答案是42。", result[0].content)
        assertEquals("一步步分析", result[0].reasoning)
    }

    @Test
    fun `no think tag returns original message`() = runTest {
        val messages = listOf(
            UIMessage(
                role = MessageRole.ASSISTANT,
                content = "这是一个普通回复。",
            ),
        )
        val result = transformer.transform(messages, TransformContext())

        assertEquals(messages[0], result[0])
        assertNull(result[0].reasoning)
    }

    @Test
    fun `only think tag with no content after`() = runTest {
        val messages = listOf(
            UIMessage(
                role = MessageRole.ASSISTANT,
                content = "<think>思考过程</think>",
            ),
        )
        val result = transformer.transform(messages, TransformContext())

        assertEquals("", result[0].content)
        assertEquals("思考过程", result[0].reasoning)
    }

    @Test
    fun `empty think tag stripped`() = runTest {
        val messages = listOf(
            UIMessage(
                role = MessageRole.ASSISTANT,
                content = "<think></think>回复内容",
            ),
        )
        val result = transformer.transform(messages, TransformContext())

        assertEquals("回复内容", result[0].content)
        assertNull(result[0].reasoning)  // 空标签,extracted=null
    }

    @Test
    fun `unclosed think tag left intact`() = runTest {
        val messages = listOf(
            UIMessage(
                role = MessageRole.ASSISTANT,
                content = "<think>未闭合标签",
            ),
        )
        val result = transformer.transform(messages, TransformContext())

        assertEquals(messages[0].content, result[0].content)
        assertNull(result[0].reasoning)
    }

    @Test
    fun `only ASSISTANT messages are processed`() = runTest {
        val messages = listOf(
            UIMessage(role = MessageRole.SYSTEM, content = "你是助手"),
            UIMessage(role = MessageRole.USER, content = "<think>用户思考</think>你好"),
            UIMessage(role = MessageRole.ASSISTANT, content = "<think>助手思考</think>回复"),
        )
        val result = transformer.transform(messages, TransformContext())

        // SYSTEM 消息保持不变
        assertEquals(messages[0], result[0])
        // USER 消息保持不变(即使包含 think 标签)
        assertEquals(messages[1], result[1])
        // ASSISTANT 消息已处理
        assertEquals("回复", result[2].content)
        assertEquals("助手思考", result[2].reasoning)
    }

    @Test
    fun `existing reasoning field skips processing`() = runTest {
        val messages = listOf(
            UIMessage(
                role = MessageRole.ASSISTANT,
                content = "<think>思考</think>回复",
                reasoning = "已有推理",
            ),
        )
        val result = transformer.transform(messages, TransformContext())

        assertEquals("已有推理", result[0].reasoning)
        // content 保持不变,因为已有 reasoning 字段 -> 跳过处理
        assertEquals("<think>思考</think>回复", result[0].content)
    }

    @Test
    fun `multiple think tags in one message`() = runTest {
        val messages = listOf(
            UIMessage(
                role = MessageRole.ASSISTANT,
                content = "<think>第一步</think><think>第二步</think>最终答案",
            ),
        )
        val result = transformer.transform(messages, TransformContext())

        assertEquals("最终答案", result[0].content)
        assertEquals("第一步\n第二步", result[0].reasoning)
    }

    @Test
    fun `think tag case insensitive`() = runTest {
        val messages = listOf(
            UIMessage(
                role = MessageRole.ASSISTANT,
                content = "<THINK>大写标签</THINK>内容",
            ),
        )
        val result = transformer.transform(messages, TransformContext())

        assertEquals("内容", result[0].content)
        assertEquals("大写标签", result[0].reasoning)
    }

    @Test
    fun `think tag cross line`() = runTest {
        val messages = listOf(
            UIMessage(
                role = MessageRole.ASSISTANT,
                content = "<think>\n多行\n思考\n</think>\n回复内容",
            ),
        )
        val result = transformer.transform(messages, TransformContext())

        assertNotNull(result[0].reasoning)
        assertTrue(result[0].reasoning!!.contains("多行"))
        assertTrue(result[0].reasoning!!.contains("思考"))
        assertEquals("回复内容", result[0].content.trim())
    }

    // ======================== stripThinkTags utility ========================

    @Test
    fun `stripThinkTags removes think blocks`() {
        val result = stripThinkTags("<think>思考</think>回复")
        assertEquals("回复", result)
    }

    @Test
    fun `stripThinkTags returns original when no tags`() {
        val result = stripThinkTags("普通文本无标签")
        assertEquals("普通文本无标签", result)
    }

    @Test
    fun `stripThinkTags handles empty content with only tags`() {
        val result = stripThinkTags("<think>思考</think>")
        assertEquals("", result)
    }

    @Test
    fun `stripThinkTags handles text before and after tags`() {
        val result = stripThinkTags("开头<think>中间</think>结尾")
        assertEquals("开头结尾", result)
    }
}
