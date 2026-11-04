package io.zer0.ai.core

import io.zer0.common.Logger
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 流式**帧间静默**阈值。
 *
 * 背景：流式请求刻意不设总超时（`STREAM_CALL_TIMEOUT_SEC = 0L`，
 * 否则共享 client 的 600s callTimeout 会把整条流硬杀），
 * 而它自己的注释写着"静默挂起仍由 readTimeout(300s) 抓住" —— 也就是
 * **服务端半死不活时，用户要看着光标转整整 5 分钟**才知道这次没戏。
 *
 * 这里把等待从 300s 压到 90s，理由与取舍：
 *  - 90s 比最宽的首事件档（推理/超长上下文 90s）不更短，避免"首包慢"被误杀；
 *  - 已经有可见内容之后长时间零字节，基本就是链路死了，不可能是"在想"：
 *    思考期也在持续吐 reasoning delta；
 *  - 判死之后发的是 [ChatStreamEvent.StreamInterrupted] + `IOException`，
 *    上层（`ChatToolLoopRunner`）据此走**保留已显示内容 + 等网络恢复后续传**的分支，
 *    而不是清屏重发，且有 `MAX_STREAM_RETRIES` 上限，不会变成死循环重连。
 */
internal const val STREAM_IDLE_TIMEOUT_MS = 90_000L

/**
 * 给一条流式事件序列加"帧间静默"看门狗。
 *
 * **只包原始流，不要包在最外层**：首事件超时后 [withFirstEventWatchdog] 会改走
 * 非流式回退请求，那条请求可以合法地几分钟不吐任何事件（共享 client 的
 * `CALL_TIMEOUT_SEC = 600L`），套在外面就会把正常回退误判成死流。
 *
 * 到点之后取消上游、发出一次中断事件并结束，不重试（重试由上层统一管预算）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun Flow<ChatStreamEvent>.withIdleWatchdog(
    idleMs: Long = STREAM_IDLE_TIMEOUT_MS,
): Flow<ChatStreamEvent> = channelFlow {
    val source = this@withIdleWatchdog.produceIn(this)
    try {
        // 用状态位收口成单一出口（仓库里 break/continue 堆在循环里会被 detekt 拦，
        // 而且这条循环有三个终止条件，多出口读起来更累）。
        var finished = false
        while (!finished) {
            val arrived = withTimeoutOrNull(idleMs) { source.receiveCatching() }
            when {
                arrived == null -> {
                    Logger.w(
                        TAG,
                        "流式 ${idleMs / 1000}s 未收到任何事件，判定为静默挂起并中断（不再干等 readTimeout 的 300s）",
                    )
                    source.cancel()
                    send(
                        ChatStreamEvent.StreamInterrupted(
                            "响应中断：${idleMs / 1000} 秒没有新的内容",
                            IOException("stream idle for ${idleMs} ms"),
                        ),
                    )
                    finished = true
                }

                arrived.isClosed -> finished = true

                else -> {
                    val event = arrived.getOrNull()
                    if (event == null) {
                        finished = true
                    } else {
                        send(event)
                        if (event is ChatStreamEvent.Done) finished = true
                    }
                }
            }
        }
    } finally {
        source.cancel()
    }
}

private const val TAG = "StreamIdleWatchdog"
