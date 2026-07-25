package io.zer0.common

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * 全局调度器入口,所有模块统一通过这里取 dispatcher,
 * 便于后续做测试替身或按平台切换。
 */
object AppDispatchers {
    val io: CoroutineDispatcher get() = Dispatchers.IO
    val default: CoroutineDispatcher get() = Dispatchers.Default
    val main: CoroutineDispatcher get() = Dispatchers.Main
}
