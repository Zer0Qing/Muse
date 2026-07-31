package io.zer0.ai.decorator

import io.zer0.ai.core.ChatCompletion
import io.zer0.ai.core.ChatRequest
import io.zer0.ai.core.ChatStreamEvent
import io.zer0.ai.core.Provider
import io.zer0.ai.util.SlidingWindowRateLimiter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * P1-3: 限流装饰器。
 *
 * 在 [Provider] 外层叠加两道前置控制(请求发出前生效):
 *  1. **RPM 限流**([SlidingWindowRateLimiter])—— 滑动窗口控制每分钟最大请求数,
 *     超限时挂起等待空位,而非直接拒绝。用户对话路径友好(多等几秒胜过报错)。
 *  2. **并发控制**([Semaphore])—— 限制同时在途的请求数,防止短时间大量并发
 *     打爆上游(尤其多 Key 池场景下 KeyRoulette 只管 key 轮询不管总并发)。
 *
 * 任一参数为 0 表示不启用对应限制(跳过装饰逻辑,直接委托)。
 *
 * 与 [io.zer0.ai.util.KeyRoulette] 的协作关系:
 *  - RateLimitDecorator 是**前置**控制:请求发出前限流 + 并发闸
 *  - KeyRoulette 是**后置**控制:429 返回后切换到下一个 key 重试
 *  - 两者叠加:先过限流闸 → 选 key 发请求 → 429 时切 key 重试
 *
 * 流式语义:[streamChat] 返回的 Flow 在**收集时**(而非构造时)获取限流许可,
 * 并在整个流式收集期间持有并发许可;收集结束/取消时释放。
 * 这确保并发计数反映真实的在途流而非"已发起但未开始收集"的请求。
 *
 * @param delegate 被装饰的真实 Provider(OpenAI/Anthropic/Gemini)
 * @param requestLimitPerMinute 每分钟最大请求数,0 表示不限
 * @param maxConcurrentRequests 最大并发在途请求数,0 表示不限
 */
class RateLimitDecorator(
    private val delegate: Provider,
    requestLimitPerMinute: Int,
    maxConcurrentRequests: Int,
) : Provider by delegate {

    private val limiter: SlidingWindowRateLimiter? =
        if (requestLimitPerMinute > 0) SlidingWindowRateLimiter(requestLimitPerMinute) else null

    private val semaphore: Semaphore? =
        if (maxConcurrentRequests > 0) Semaphore(maxConcurrentRequests) else null

    override fun streamChat(request: ChatRequest): Flow<ChatStreamEvent> = flow {
        // 限流:在流开始收集时获取 RPM 许可(挂起等待空位)
        limiter?.acquire()
        // 并发:在整个流式收集期间持有许可,结束/取消时释放
        if (semaphore != null) {
            semaphore.acquire()
            try {
                delegate.streamChat(request).collect { emit(it) }
            } finally {
                semaphore.release()
            }
        } else {
            delegate.streamChat(request).collect { emit(it) }
        }
    }

    override suspend fun completeText(request: ChatRequest): ChatCompletion {
        limiter?.acquire()
        return if (semaphore != null) {
            semaphore.withPermit { delegate.completeText(request) }
        } else {
            delegate.completeText(request)
        }
    }
}
