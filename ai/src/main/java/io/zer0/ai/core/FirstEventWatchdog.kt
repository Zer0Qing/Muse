package io.zer0.ai.core

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** R-AI-04: 深度推理/超长上下文模型放宽首事件超时,普通模型保持默认。 */
internal fun Model.firstEventTimeoutMs(defaultMs: Long = 15_000L): Long =
    if (supportsReasoning() || (contextWindow ?: 0) > 200_000) 60_000L else defaultMs

/**
 * B3-01: 首事件看门狗。
 *
 * SSE 连接建立后若 [timeoutMs] 内没有收到任何有效事件,自动取消上游流,
 * 改用非流式 [fallback] 重试一次,并向 UI 发出 [ChatStreamEvent.FallbackNotice]。
 *
 * 审查修复 (2.0 B-25): callbackFlow → channelFlow — callbackFlow 内部 channel 容量
 * 固定 64,快速生产/慢消费(如 UI 节流 50ms 自适应切片)时 trySend 返回 false 静默丢片,
 * 且下游 `.buffer(UNLIMITED)` 无法改变其内部容量;channelFlow 内部 channel 恒为
 * UNLIMITED,与 OpenAIProvider 的 C-35 修复同一方案。
 */
fun Flow<ChatStreamEvent>.withFirstEventWatchdog(
    timeoutMs: Long = 15_000L,
    fallback: suspend () -> ChatCompletion,
    // 审计修复 (7.8): 可选取消检查 — 调用方传入"用户是否已停止"的判断,
    // fallback 触发前检查,避免用户停止后仍发一次计费请求。
    abortCheck: () -> Boolean = { false },
): Flow<ChatStreamEvent> = channelFlow {
    var firstEventReceived = false
    var finished = false
    var fallbackStarted = false
    var meaningfulEventReceived = false
    lateinit var upstreamJob: Job

    suspend fun emitFallback(notice: String, cancelUpstream: Boolean) {
        if (fallbackStarted || abortCheck()) return
        fallbackStarted = true
        finished = true
        if (cancelUpstream) upstreamJob.cancel()
        trySend(ChatStreamEvent.FallbackNotice(notice))
        try {
            val completion = fallback()
            completion.reasoningContent?.takeIf { it.isNotBlank() }?.let {
                trySend(ChatStreamEvent.ReasoningDelta(it))
            }
            if (completion.text.isNotBlank()) {
                trySend(ChatStreamEvent.ContentDelta(completion.text))
            }
            completion.toolCalls?.forEachIndexed { index, tc ->
                trySend(
                    ChatStreamEvent.ToolCallDelta(
                        index = index,
                        id = tc.id,
                        name = tc.name,
                        argumentsDelta = tc.arguments,
                    ),
                )
            }
            trySend(ChatStreamEvent.Done(completion.finishReason))
        } catch (t: Throwable) {
            trySend(ChatStreamEvent.Error(t.message ?: "非流式回退失败", t))
        } finally {
            close()
        }
    }

    upstreamJob = launch {
        try {
            collect { event ->
                if (finished) return@collect
                if (!firstEventReceived &&
                    event !is ChatStreamEvent.Error &&
                    event !is ChatStreamEvent.StreamInterrupted
                ) {
                    firstEventReceived = true
                }
                when (event) {
                    is ChatStreamEvent.ContentDelta -> {
                        if (event.delta.isNotEmpty()) meaningfulEventReceived = true
                        trySend(event)
                    }
                    is ChatStreamEvent.ReasoningDelta -> {
                        if (event.delta.isNotEmpty()) meaningfulEventReceived = true
                        trySend(event)
                    }
                    is ChatStreamEvent.ImageDelta,
                    is ChatStreamEvent.ToolCallDelta,
                    -> {
                        meaningfulEventReceived = true
                        trySend(event)
                    }
                    is ChatStreamEvent.Done -> {
                        // 部分 OpenAI 兼容中转会建立 SSE 后只返回一个空 Done。
                        // 这不是可展示的正常回复,立即走一次非流式请求,避免用户看到空消息并长时间等待。
                        if (!meaningfulEventReceived) {
                            emitFallback("流式响应为空，正在切换请求方式", cancelUpstream = false)
                        } else {
                            trySend(event)
                            finished = true
                            close()
                        }
                    }
                    is ChatStreamEvent.Error,
                    is ChatStreamEvent.StreamInterrupted,
                    is ChatStreamEvent.FallbackNotice,
                    is ChatStreamEvent.UsageDelta,
                    -> {
                        trySend(event)
                        if (event is ChatStreamEvent.Error ||
                            event is ChatStreamEvent.StreamInterrupted
                        ) {
                            finished = true
                            close()
                        }
                    }
                }
            }
            // 注意：上游流“无任何事件即结束”（如空流 / SSE 立即断开）不应视为正常完成。
            // 此时保持 finished=false，让 watchdog 超时后触发非流式回退。
            // 用户停止生成的路径由 Provider 发出 StreamInterrupted 事件覆盖（finished=true）。
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) {
                // 取消 / abort：标记结束并向上传播，不把取消当成 stream failed，
                // 也阻止 watchdog 在取消后继续触发回退。
                finished = true
                throw t
            }
            if (!finished) {
                finished = true
                trySend(ChatStreamEvent.Error(t.message ?: "stream failed", t))
                close()
            }
        }
    }

    val watchdogJob = launch {
        delay(timeoutMs)
        if (!firstEventReceived && !finished) {
            // 审计修复 (7.8): 用户已停止时不再发 fallback,省一次计费请求。
            emitFallback("网络较慢，已切换请求方式", cancelUpstream = true)
        }
    }

    awaitClose {
        upstreamJob.cancel()
        watchdogJob.cancel()
    }
}
