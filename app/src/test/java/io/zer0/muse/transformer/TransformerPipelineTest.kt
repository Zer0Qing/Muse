package io.zer0.muse.transformer

import io.zer0.ai.core.UIMessage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * TransformerPipeline 管道执行器单元测试。
 *
 * 测试管道串联、空管道、容错行为。
 */
@RunWith(RobolectricTestRunner::class)
class TransformerPipelineTest {

    // A transformer that prepends a fixed string
    private class PrependTransformer(private val prefix: String) : Transformer {
        override val name: String = "Prepend($prefix)"
        override suspend fun transform(
            messages: List<UIMessage>,
            context: TransformContext,
        ): List<UIMessage> = messages.map { it.copy(content = "$prefix${it.content}") }
    }

    // 在末尾拼接后缀的 Transformer
    private class AppendTransformer(private val suffix: String) : Transformer {
        override val name: String = "Append($suffix)"
        override suspend fun transform(
            messages: List<UIMessage>,
            context: TransformContext,
        ): List<UIMessage> = messages.map { it.copy(content = "${it.content}$suffix") }
    }

    // A transformer that always throws (test fault tolerance)
    private class FailingTransformer : Transformer {
        override val name: String = "Failing"
        override suspend fun transform(
            messages: List<UIMessage>,
            context: TransformContext,
        ): List<UIMessage> = throw RuntimeException("模拟失败")
    }

    @Test
    fun `pipeline executes transformers in order`() = runTest {
        val pipeline = TransformerPipeline(listOf(
            PrependTransformer("A"),
            AppendTransformer("B"),
        ))
        val msg = io.zer0.ai.core.UIMessage(
            role = io.zer0.ai.core.MessageRole.USER,
            content = "X",
        )
        val result = pipeline.execute(listOf(msg), TransformContext())

        assertEquals(1, result.size)
        assertEquals("AXB", result[0].content)
    }

    @Test
    fun `first transformer output is second transformer input`() = runTest {
        val pipeline = TransformerPipeline(listOf(
            PrependTransformer("P1:"),
            PrependTransformer("P2:"),
        ))
        val msg = io.zer0.ai.core.UIMessage(
            role = io.zer0.ai.core.MessageRole.USER,
            content = "hello",
        )
        val result = pipeline.execute(listOf(msg), TransformContext())

        assertEquals("P2:P1:hello", result[0].content)
    }

    @Test
    fun `empty pipeline returns original messages`() = runTest {
        val pipeline = TransformerPipeline(emptyList())
        val msgs = listOf(
            io.zer0.ai.core.UIMessage(role = io.zer0.ai.core.MessageRole.USER, content = "你好"),
        )
        val result = pipeline.execute(msgs, TransformContext())

        assertEquals(msgs, result)
    }

    @Test
    fun `failing transformer keeps previous result`() = runTest {
        val pipeline = TransformerPipeline(listOf(
            PrependTransformer("OK-"),
            FailingTransformer(),
        ))
        val msg = io.zer0.ai.core.UIMessage(
            role = io.zer0.ai.core.MessageRole.USER,
            content = "test",
        )
        val result = pipeline.execute(listOf(msg), TransformContext())

        assertEquals(1, result.size)
        // FailingTransformer 被跳过,保留 PrependTransformer 的输出
        assertEquals("OK-test", result[0].content)
    }

    @Test
    fun `builder pattern works`() = runTest {
        val pipeline = TransformerPipeline.Builder()
            .add(PrependTransformer("B1:"))
            .add(AppendTransformer(":B2"))
            .build()

        val msg = io.zer0.ai.core.UIMessage(
            role = io.zer0.ai.core.MessageRole.USER,
            content = "x",
        )
        val result = pipeline.execute(listOf(msg), TransformContext())

        assertEquals("B1:x:B2", result[0].content)
    }
}
