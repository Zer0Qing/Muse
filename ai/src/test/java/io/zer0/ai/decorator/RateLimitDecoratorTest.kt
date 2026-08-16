package io.zer0.ai.decorator

import io.zer0.ai.core.ChatCompletion
import io.zer0.ai.core.ChatRequest
import io.zer0.ai.core.ChatStreamEvent
import io.zer0.ai.core.Model
import io.zer0.ai.core.Provider
import io.zer0.ai.core.UIMessage
import io.zer0.ai.core.MessageRole
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * P1-3: [RateLimitDecorator] 单元测试。
 *
 * 验证:
 *  - 并发控制(Semaphore)限制同时在途请求数
 *  - 任一参数为 0 时跳过装饰(直接委托)
 *  - streamChat 的并发许可在流收集期间持有,结束/取消时释放
 *
 * 注:RPM 限流逻辑由 [io.zer0.ai.util.SlidingWindowRateLimiterTest] 覆盖
 * (decorator 内部 limiter 用实时时钟,无法注入虚拟时钟,故不在此重复测 RPM 等待)。
 *
 * C-31 评估:[FakeProvider] 只在 ai 模块本文件使用,全仓库无同名/同职责的跨文件复制
 * (VideoGenerationServiceTest 的 FakeVideoProvider 用途不同)。重复度低,不值得建
 * testFixtures 基建,保持文件内私有。见深度审计报告 C-31 修正说明。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RateLimitDecoratorTest {

    /** 测试用假 Provider:记录调用数、峰值并发,可注入延迟。 */
    private class FakeProvider(private val delayMs: Long = 0) : Provider {
        override val id: String = "fake"
        override val displayName: String = "Fake"

        val completeCallCount = AtomicInteger(0)
        val streamCallCount = AtomicInteger(0)
        private val activeCount = AtomicInteger(0)
        val maxObservedActive = AtomicInteger(0)

        private fun enter() {
            val active = activeCount.incrementAndGet()
            // 记录峰值并发(自旋更新直到成功)
            var prev = maxObservedActive.get()
            while (active > prev && !maxObservedActive.compareAndSet(prev, active)) {
                prev = maxObservedActive.get()
            }
        }

        private fun exit() {
            activeCount.decrementAndGet()
        }

        override suspend fun completeText(request: ChatRequest): ChatCompletion {
            enter()
            try {
                if (delayMs > 0) delay(delayMs)
                completeCallCount.incrementAndGet()
                return ChatCompletion(text = "ok")
            } finally {
                exit()
            }
        }

        override fun streamChat(request: ChatRequest): Flow<ChatStreamEvent> = flow {
            enter()
            try {
                if (delayMs > 0) delay(delayMs)
                streamCallCount.incrementAndGet()
                emit(ChatStreamEvent.ContentDelta("hi"))
                emit(ChatStreamEvent.Done())
            } finally {
                exit()
            }
        }
    }

    private fun dummyRequest(): ChatRequest = ChatRequest(
        messages = listOf(UIMessage(role = MessageRole.USER, content = "hi")),
        model = Model(id = "m", name = "M", providerId = "fake"),
    )

    @Test
    fun `completeText should limit concurrency to maxConcurrentRequests`() = runTest {
        val fake = FakeProvider(delayMs = 100)
        val decorator = RateLimitDecorator(
            delegate = fake,
            requestLimitPerMinute = 0, // 不限 RPM,只测并发
            maxConcurrentRequests = 2,
        )
        // 6 个并发请求,每个耗时 100ms,但并发上限 2 → 峰值并发 ≤ 2
        val jobs = (1..6).map { async { decorator.completeText(dummyRequest()) } }
        jobs.awaitAll()
        assertEquals(6, fake.completeCallCount.get())
        assertTrue(
            "max observed active ${fake.maxObservedActive.get()} should be <= 2",
            fake.maxObservedActive.get() <= 2,
        )
    }

    @Test
    fun `completeText should delegate directly when both limits are zero`() = runTest {
        val fake = FakeProvider(delayMs = 0)
        val decorator = RateLimitDecorator(
            delegate = fake,
            requestLimitPerMinute = 0,
            maxConcurrentRequests = 0,
        )
        decorator.completeText(dummyRequest())
        assertEquals(1, fake.completeCallCount.get())
        // 无延迟,虚拟时间未推进
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun `streamChat should hold concurrency permit during collection`() = runTest {
        val fake = FakeProvider(delayMs = 100)
        val decorator = RateLimitDecorator(
            delegate = fake,
            requestLimitPerMinute = 0,
            maxConcurrentRequests = 2,
        )
        // 4 个并发流,每个收集耗时 100ms,并发上限 2 → 峰值 ≤ 2
        val jobs = (1..4).map {
            async { decorator.streamChat(dummyRequest()).toList() }
        }
        jobs.awaitAll()
        // 每个流发出 ContentDelta + Done
        assertEquals(4, fake.streamCallCount.get())
        assertTrue(
            "max observed active ${fake.maxObservedActive.get()} should be <= 2",
            fake.maxObservedActive.get() <= 2,
        )
    }

    @Test
    fun `decorator should preserve delegate id and displayName`() {
        val fake = FakeProvider()
        val decorator = RateLimitDecorator(fake, requestLimitPerMinute = 10, maxConcurrentRequests = 2)
        // Provider by delegate 转发属性
        assertEquals("fake", decorator.id)
        assertEquals("Fake", decorator.displayName)
    }
}
