package io.zer0.ai.core

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch

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
            // 上游流正常结束但未收到终止事件（例如用户停止后 Provider 静默 close）。
            // 必须置 finished，否则 watchdog 15s 后仍会触发非流式回退，
            // 表现为“点了停止还在输入中 / 停止后自动重发请求”。
            if (!finished) {
                finished = true
                close()
            }
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
            trySend(ChatStreamEvent.FallbackNotice("已切换非流式"))
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
