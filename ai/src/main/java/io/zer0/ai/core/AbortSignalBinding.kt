package io.zer0.ai.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion

/**
 * 把"下游不再要事件了"翻译成 [AbortSignal.aborted]。
 *
 * 为什么需要它：`ChatRequest.abortSignal` 的默认值是一个**全新的空信号**，
 * 而生产链路上从来没人置位它 —— `app/src/main` 对 `AbortSignal` 的引用数是 0。
 * 结果是 Provider 侧几十处 `request.abortSignal.aborted` 判定恒为 false：
 * 那些"用户已停止就别重连、别再发计费请求"的分支从来没有真的走过。
 *
 * 线上并没有因此出错（退避重连那处同时被"取消的 scope 不会启动新协程"和
 * `scope.isClosedForSend` 兜住），所以这里要修的不是崩溃，而是**一条一直在说谎的可观测性**：
 * 排查时看到的那些 aborted 分支永远不触发，会让人误判"用户根本没停过"。
 *
 * 语义边界（写清楚，免得反向误导）：
 *  - 只有**取消**会置位；正常收完、正常结束**不算**中止（上游自己抛错也不算，那是错误不是停止）；
 */
// `Flow.onCancel` 在新版协程里已经不存在（我先凭印象用了它，编译当场打回）。
// `onCompletion` 的 cause 恰好给出我们要的区分：正常收完是 null，取消是 CancellationException。
internal fun <T> Flow<T>.abortedOnCancel(signal: AbortSignal): Flow<T> = onCompletion { cause ->
    if (cause is CancellationException) signal.abort()
}
