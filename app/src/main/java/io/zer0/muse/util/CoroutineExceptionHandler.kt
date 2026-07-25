package io.zer0.muse.util

import io.zer0.common.Logger
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlin.coroutines.CoroutineContext

/**
 * v0.53: 全局协程异常处理器(企业级容错)。
 *
 * 防止协程内未捕获异常导致应用崩溃。
 * 异常会被记录到日志,不会传播到默认线程 UncaughtExceptionHandler。
 *
 * 用法:
 * ```
 * viewModelScope.launch(GlobalCoroutineExceptionHandler) { ... }
 * // 与 dispatcher 组合:
 * viewModelScope.launch(AppDispatchers.io + GlobalCoroutineExceptionHandler) { ... }
 * ```
 *
 * 注意:本处理器只兜底"未被 try-catch 捕获"的异常。
 * 已被业务代码 try-catch 的异常不会触发本处理器。
 * CancellationException 不会触发本处理器(协程取消语义由 coroutines 框架处理)。
 */
val GlobalCoroutineExceptionHandler = CoroutineExceptionHandler { _: CoroutineContext, throwable ->
    Logger.e("Coroutine", "Uncaught coroutine exception", throwable)
}
