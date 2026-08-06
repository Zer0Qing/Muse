package io.zer0.ai.core

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch

/** R-AI-04: 深度推理/超长上下文模型放宽首事件超时,普通模型保持默认。 */
internal fun Model.firstEventTimeoutMs(defaultMs: Long = 15_000L): Long =
    if (supportsReasoning() || (contextWindow ?: 0) > 200_000) 60_000L else defaultMs

/**
 * B3-01: 首事件看门狗。
 *
 * SSE 连接建立后若 [timeoutMs] 内没有收到任何有效事件,自动取消上游流,
 * 改用非流式 [fallback] 重试一次,并向 UI 发出 [ChatStreamEvent.FallbackNotice]。
 */
fun Flow<ChatStreamEvent>.withFirstEventWatchdog(
    timeoutMs: Long = 15_000L,
    fallback: suspend () -> ChatCompletion,
): Flow<ChatStreamEvent> = callbackFlow {
    var firstEventReceived = false
    var finished = false

    val upstreamJob = launch {
        try {
            collect { event ->
                if (finished) return@collect
                if (!firstEventReceived &&
                    event !is ChatStreamEvent.Error &&
                    event !is ChatStreamEvent.StreamInterrupted
                ) {
                    firstEventReceived = true
                }
                trySend(event)
                if (event is ChatStreamEvent.Done ||
                    event is ChatStreamEvent.Error ||
                    event is ChatStreamEvent.StreamInterrupted
                ) {
                    finished = true
                    close()
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
            finished = true
            upstreamJob.cancel()
            trySend(ChatStreamEvent.FallbackNotice("网络较慢，已切换请求方式"))
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
    }

    awaitClose {
        upstreamJob.cancel()
        watchdogJob.cancel()
    }
}
