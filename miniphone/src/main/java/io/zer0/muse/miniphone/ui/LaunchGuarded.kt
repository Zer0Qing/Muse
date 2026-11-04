package io.zer0.muse.miniphone.ui

import io.zer0.common.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * UI 动作协程的兜底：**记日志、不崩应用**。
 *
 * 为什么需要它：`viewModelScope.launch { … }` 里没有 catch 时，异常会沿着作用域冒到
 * 默认处理器 —— 对小手机来说就是**用户点个赞、刷一下动态，整个应用崩掉**。
 * 之前 `MiniPhoneMomentsViewModel` 里 9 个 `launch` 全是这种写法，
 * 只要 Room 抛一次（数据库忙、约束冲突、字段过长）就足以触发。
 *
 * 两条设计约束：
 *
 *  1. **`CancellationException` 必须原样抛回去**。它是协程取消的正常信号
 *     （ViewModel 清理、`scope.coroutineContext.cancel()` 都靠它），
 *     把它一起吞掉就等于让取消变成"静默不干活"—— 那比崩更难查。
 *  2. **不静默**。吞掉的每一个真异常都必须带 tag 和原因进日志：
 *     用户看到的是"点了没反应"，如果日志里也没有，就永远查不下去。
 *
 * @param tag 进日志的模块标签（用稳定的英文短名，便于 grep）
 * @param action 给用户的说法，出现在日志里（"点赞"/"刷新动态"），
 *               这样一条日志就能同时说明"哪个操作"和"为什么没成"
 * @param onError 额外收尾（例如收起 loading 状态、把界面恢复原样）
 */
// 这里有意兜住任意 Exception（不是具体某几种）：UI 动作的失败来源是开放的 ——
// Room/SQLite 抛的、宿主回调抛的、序列化抛的、并发状态改坏抛的，列不全。
// 列不全的代价不是"少记一条日志"，而是用户点个赞整个应用崩掉。
// 规则想防的是"用 catch (Exception) 掩盖本该分开处理的问题"，而这里
// 唯一的处理就是留痕 + 不崩，且 `Error` 仍然照常冒出去（见函数内注释）。
// 仓库里同样取舍的先例：ShellExecutor、BackupService、DeviceCapabilityHook。
@Suppress("TooGenericExceptionCaught")
internal fun CoroutineScope.launchGuarded(
    tag: String,
    action: String,
    onError: (suspend (Throwable) -> Unit)? = null,
    block: suspend CoroutineScope.() -> Unit,
): Job = launch {
    try {
        block()
    } catch (c: CancellationException) {
        throw c
    } catch (t: Exception) {
        // 只兜 Exception，不兜 Throwable/Error：`Error`（OOM、断言失败）意味着 JVM 层面已经坏了，
        // 让它被"一次点赞失败"掩盖掉会把真正的故障变成慢性静默。

        Logger.w(tag, "$action 失败（已兜住，不崩应用）: ${t.message ?: t.javaClass.simpleName}", t)
        // onError 自己出错也要留痕，否则"恢复界面"这一步失败又是一次静默
        runCatching { onError?.invoke(t) }
            .onFailure { e -> Logger.w(tag, "$action 的失败收尾也失败了: ${e.message ?: e.javaClass.simpleName}", e) }
    }
}
