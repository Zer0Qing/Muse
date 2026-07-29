package io.zer0.muse.transformer

import io.zer0.ai.core.UIMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * TransformerPipeline 管道执行器单元测试。
 *
 * 测试管道串联、空管道、容错行为,以及三个钩子(execute/visualTransform/onGenerationFinish)
 * 的覆盖与异常重抛语义(H-PIPE1 CancellationException / M-PIPE2 Error)。
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

    /**
     * 自定义 CancellationException 子类,用于验证协程取消异常被重抛(H-PIPE1)。
     *
     * 用子类而不用 JobCancellationException,避免与 runTest 内部 Job 状态纠缠。
     * 带唯一标记 message 便于断言识别。
     */
    private class TestCancellationException(message: String) :
        CancellationException(message)

    /** 抛 CancellationException 的 Transformer,用于验证 H-PIPE1 重抛语义。 */
    private class CancellingTransformer : Transformer {
        override val name: String = "Cancelling"
        override suspend fun transform(
            messages: List<UIMessage>,
            context: TransformContext,
        ): List<UIMessage> = throw TestCancellationException("cancel-from-test")
    }

    /** 抛 OutOfMemoryError 的 Transformer,用于验证 M-PIPE2 重抛语义。 */
    private class ErrorTransformer : Transformer {
        override val name: String = "Error"
        override suspend fun transform(
            messages: List<UIMessage>,
            context: TransformContext,
        ): List<UIMessage> = throw OutOfMemoryError("simulated-oom")
    }

    // 在 visualTransform 阶段对 content 追加 [V] 标记的 Transformer
    private class VisualMarkTransformer : Transformer {
        override val name: String = "VisualMark"
        override suspend fun transform(
            messages: List<UIMessage>,
            context: TransformContext,
        ): List<UIMessage> = messages  // transform 阶段不改

        override suspend fun visualTransform(
            messages: List<UIMessage>,
            context: TransformContext,
        ): List<UIMessage> = messages.map { it.copy(content = "[V]${it.content}") }
    }

    // 在 onGenerationFinish 阶段对 content 追加 [F] 标记的 Transformer
    private class FinishMarkTransformer : Transformer {
        override val name: String = "FinishMark"
        override suspend fun transform(
            messages: List<UIMessage>,
            context: TransformContext,
        ): List<UIMessage> = messages  // transform 阶段不改

        override suspend fun onGenerationFinish(
            messages: List<UIMessage>,
            context: TransformContext,
        ): List<UIMessage> = messages.map { it.copy(content = "${it.content}[F]") }
    }

    // 在 visualTransform 阶段抛 RuntimeException 的 Transformer
    private class VisualFailingTransformer : Transformer {
        override val name: String = "VisualFailing"
        override suspend fun transform(
            messages: List<UIMessage>,
            context: TransformContext,
        ): List<UIMessage> = messages

        override suspend fun visualTransform(
            messages: List<UIMessage>,
            context: TransformContext,
        ): List<UIMessage> = throw RuntimeException("visual-fail")
    }

    // 在 onGenerationFinish 阶段抛 RuntimeException 的 Transformer
    private class FinishFailingTransformer : Transformer {
        override val name: String = "FinishFailing"
        override suspend fun transform(
            messages: List<UIMessage>,
            context: TransformContext,
        ): List<UIMessage> = messages

        override suspend fun onGenerationFinish(
            messages: List<UIMessage>,
            context: TransformContext,
        ): List<UIMessage> = throw RuntimeException("finish-fail")
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

    // ── Phase 2.3.6 补充:applyVisualTransform / applyOnGenerationFinish 钩子 ────

    @Test
    fun `applyVisualTransform chains visual hooks in order`() = runTest {
        val pipeline = TransformerPipeline(listOf(
            VisualMarkTransformer(),
            object : Transformer {
                override val name: String = "VisualSuffix"
                override suspend fun transform(messages: List<UIMessage>, context: TransformContext) = messages
                override suspend fun visualTransform(messages: List<UIMessage>, context: TransformContext) =
                    messages.map { it.copy(content = "${it.content}[V2]") }
            },
        ))
        val msg = io.zer0.ai.core.UIMessage(
            role = io.zer0.ai.core.MessageRole.ASSISTANT,
            content = "streaming",
        )
        val result = pipeline.applyVisualTransform(listOf(msg), TransformContext())

        // 链式:VisualMark 加 [V] 前缀 → VisualSuffix 加 [V2] 后缀
        assertEquals("[V]streaming[V2]", result[0].content)
    }

    @Test
    fun `applyVisualTransform with default hooks returns original list`() = runTest {
        // 默认实现 visualTransform 原样返回,管道应等价于 no-op
        val pipeline = TransformerPipeline(listOf(
            object : Transformer {
                override val name: String = "DefaultVisual"
                override suspend fun transform(messages: List<UIMessage>, context: TransformContext) = messages
                // visualTransform 用默认实现
            },
        ))
        val msgs = listOf(
            io.zer0.ai.core.UIMessage(role = io.zer0.ai.core.MessageRole.USER, content = "hi"),
        )
        val result = pipeline.applyVisualTransform(msgs, TransformContext())

        // 默认 visualTransform 原样返回,应得到等价列表(可能不是同一引用,但内容一致)
        assertEquals(1, result.size)
        assertEquals("hi", result[0].content)
    }

    @Test
    fun `applyVisualTransform empty pipeline returns original messages`() = runTest {
        val pipeline = TransformerPipeline(emptyList())
        val msgs = listOf(
            io.zer0.ai.core.UIMessage(role = io.zer0.ai.core.MessageRole.USER, content = "x"),
        )
        val result = pipeline.applyVisualTransform(msgs, TransformContext())
        // L-PIPE3: 空管道提前返回原列表
        assertSame("空管道应返回同一引用(L-PIPE3 优化)", msgs, result)
    }

    @Test
    fun `applyVisualTransform tolerates single transformer failure`() = runTest {
        val pipeline = TransformerPipeline(listOf(
            VisualMarkTransformer(),
            VisualFailingTransformer(),
            object : Transformer {
                override val name: String = "AfterFail"
                override suspend fun transform(messages: List<UIMessage>, context: TransformContext) = messages
                override suspend fun visualTransform(messages: List<UIMessage>, context: TransformContext) =
                    messages.map { it.copy(content = "${it.content}-after") }
            },
        ))
        val msg = io.zer0.ai.core.UIMessage(
            role = io.zer0.ai.core.MessageRole.ASSISTANT,
            content = "text",
        )
        val result = pipeline.applyVisualTransform(listOf(msg), TransformContext())

        // VisualFailingTransformer 失败被跳过,保留前一步结果;后续 AfterFail 继续处理
        assertEquals("[V]text-after", result[0].content)
    }

    @Test
    fun `applyOnGenerationFinish chains finish hooks in order`() = runTest {
        val pipeline = TransformerPipeline(listOf(
            FinishMarkTransformer(),
            object : Transformer {
                override val name: String = "FinishPrefix"
                override suspend fun transform(messages: List<UIMessage>, context: TransformContext) = messages
                override suspend fun onGenerationFinish(messages: List<UIMessage>, context: TransformContext) =
                    messages.map { it.copy(content = "[F2]${it.content}") }
            },
        ))
        val msg = io.zer0.ai.core.UIMessage(
            role = io.zer0.ai.core.MessageRole.ASSISTANT,
            content = "final",
        )
        val result = pipeline.applyOnGenerationFinish(listOf(msg), TransformContext())

        // 链式:FinishMark 加 [F] 后缀 → FinishPrefix 加 [F2] 前缀
        assertEquals("[F2]final[F]", result[0].content)
    }

    @Test
    fun `applyOnGenerationFinish with default hooks returns original list`() = runTest {
        val pipeline = TransformerPipeline(listOf(
            object : Transformer {
                override val name: String = "DefaultFinish"
                override suspend fun transform(messages: List<UIMessage>, context: TransformContext) = messages
                // onGenerationFinish 用默认实现
            },
        ))
        val msgs = listOf(
            io.zer0.ai.core.UIMessage(role = io.zer0.ai.core.MessageRole.USER, content = "hi"),
        )
        val result = pipeline.applyOnGenerationFinish(msgs, TransformContext())

        assertEquals(1, result.size)
        assertEquals("hi", result[0].content)
    }

    @Test
    fun `applyOnGenerationFinish empty pipeline returns original messages`() = runTest {
        val pipeline = TransformerPipeline(emptyList())
        val msgs = listOf(
            io.zer0.ai.core.UIMessage(role = io.zer0.ai.core.MessageRole.USER, content = "x"),
        )
        val result = pipeline.applyOnGenerationFinish(msgs, TransformContext())
        assertSame("空管道应返回同一引用(L-PIPE3 优化)", msgs, result)
    }

    @Test
    fun `applyOnGenerationFinish tolerates single transformer failure`() = runTest {
        val pipeline = TransformerPipeline(listOf(
            FinishMarkTransformer(),
            FinishFailingTransformer(),
            object : Transformer {
                override val name: String = "AfterFail"
                override suspend fun transform(messages: List<UIMessage>, context: TransformContext) = messages
                override suspend fun onGenerationFinish(messages: List<UIMessage>, context: TransformContext) =
                    messages.map { it.copy(content = "${it.content}-after") }
            },
        ))
        val msg = io.zer0.ai.core.UIMessage(
            role = io.zer0.ai.core.MessageRole.ASSISTANT,
            content = "text",
        )
        val result = pipeline.applyOnGenerationFinish(listOf(msg), TransformContext())

        // FinishFailingTransformer 失败被跳过,后续 AfterFail 继续处理
        assertEquals("text[F]-after", result[0].content)
    }

    // ── Phase 2.3.6 补充:异常重抛语义(H-PIPE1 / M-PIPE2) ────────────────

    @Test
    fun `execute rethrows CancellationException instead of swallowing`() = kotlinx.coroutines.runBlocking {
        // H-PIPE1: 协程取消异常必须重抛,不可吞掉
        val pipeline = TransformerPipeline(listOf(
            PrependTransformer("before-"),
            CancellingTransformer(),
        ))
        val msg = io.zer0.ai.core.UIMessage(
            role = io.zer0.ai.core.MessageRole.USER,
            content = "x",
        )
        var caught: TestCancellationException? = null
        try {
            pipeline.execute(listOf(msg), TransformContext())
        } catch (e: TestCancellationException) {
            caught = e
        }
        assertTrue("应捕获到 TestCancellationException(被重抛,非吞掉)", caught != null)
        assertEquals("cancel-from-test", caught!!.message)
    }

    @Test
    fun `execute rethrows Error instead of swallowing`() = kotlinx.coroutines.runBlocking {
        // M-PIPE2: OOM/StackOverflow 等 Error 不属于可恢复异常,重抛让上层处理
        val pipeline = TransformerPipeline(listOf(
            PrependTransformer("before-"),
            ErrorTransformer(),
        ))
        val msg = io.zer0.ai.core.UIMessage(
            role = io.zer0.ai.core.MessageRole.USER,
            content = "x",
        )
        var caught: OutOfMemoryError? = null
        try {
            pipeline.execute(listOf(msg), TransformContext())
        } catch (e: OutOfMemoryError) {
            caught = e
        }
        assertTrue("应捕获到 OutOfMemoryError(Error 被重抛,非吞掉)", caught != null)
        assertEquals("simulated-oom", caught!!.message)
    }

    @Test
    fun `applyVisualTransform rethrows CancellationException`() = kotlinx.coroutines.runBlocking {
        // H-PIPE1: visualTransform 阶段同样必须重抛 CancellationException
        val pipeline = TransformerPipeline(listOf(
            object : Transformer {
                override val name: String = "VisualCancelling"
                override suspend fun transform(messages: List<UIMessage>, context: TransformContext) = messages
                override suspend fun visualTransform(messages: List<UIMessage>, context: TransformContext) =
                    throw TestCancellationException("visual-cancel")
            },
        ))
        val msg = io.zer0.ai.core.UIMessage(
            role = io.zer0.ai.core.MessageRole.ASSISTANT,
            content = "x",
        )
        var caught: TestCancellationException? = null
        try {
            pipeline.applyVisualTransform(listOf(msg), TransformContext())
        } catch (e: TestCancellationException) {
            caught = e
        }
        assertTrue("visualTransform 阶段应重抛 CancellationException", caught != null)
        assertEquals("visual-cancel", caught!!.message)
    }

    @Test
    fun `applyOnGenerationFinish rethrows CancellationException`() = kotlinx.coroutines.runBlocking {
        // H-PIPE1: onGenerationFinish 阶段同样必须重抛 CancellationException
        val pipeline = TransformerPipeline(listOf(
            object : Transformer {
                override val name: String = "FinishCancelling"
                override suspend fun transform(messages: List<UIMessage>, context: TransformContext) = messages
                override suspend fun onGenerationFinish(messages: List<UIMessage>, context: TransformContext) =
                    throw TestCancellationException("finish-cancel")
            },
        ))
        val msg = io.zer0.ai.core.UIMessage(
            role = io.zer0.ai.core.MessageRole.ASSISTANT,
            content = "x",
        )
        var caught: TestCancellationException? = null
        try {
            pipeline.applyOnGenerationFinish(listOf(msg), TransformContext())
        } catch (e: TestCancellationException) {
            caught = e
        }
        assertTrue("onGenerationFinish 阶段应重抛 CancellationException", caught != null)
        assertEquals("finish-cancel", caught!!.message)
    }

    @Test
    fun `execute continues after non-fatal exception in middle transformer`() = runTest {
        // 验证:中间 Transformer 抛 RuntimeException,后续 Transformer 仍继续执行
        // (容错策略:跳过失败步骤,保留前一步结果,继续后续)
        val pipeline = TransformerPipeline(listOf(
            PrependTransformer("A-"),
            FailingTransformer(),  // 跳过
            AppendTransformer("-Z"),
        ))
        val msg = io.zer0.ai.core.UIMessage(
            role = io.zer0.ai.core.MessageRole.USER,
            content = "x",
        )
        val result = pipeline.execute(listOf(msg), TransformContext())

        assertEquals(1, result.size)
        // Failing 被跳过,current 保留 "A-x";AppendTransformer 继续追加 -Z
        assertEquals("A-x-Z", result[0].content)
    }
}
