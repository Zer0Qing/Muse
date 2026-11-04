package io.zer0.muse.miniphone.util

import io.zer0.common.Logger
import kotlinx.coroutines.CoroutineExceptionHandler

/**
 * 后台作用域的"只记日志"异常处理器。
 *
 * 用在哪：模块内部自己开的长期作用域（录音器、语音播放器），
 * 它们是 `CoroutineScope(SupervisorJob() + …)` —— **没有**异常处理器时，
 * 子任务里任何意料外的异常都会落到线程默认处理器，表现就是整个应用弹掉。
 *
 * 为什么不是给每个 `launch` 单独包一层（那是 UI 动作的做法，见 `launchGuarded`）：
 * 这两个作用域里的任务是**后台长活**（采集写入、播放推进），
 * 单个任务死了不该让整个功能停摆，但"它为什么死了"必须留在日志里；
 * 挂一个处理器就够了，不用把每个 `launch` 都改写一遍。
 *
 * 注意它**不吞取消**：协程的取消是正常控制流，`CoroutineExceptionHandler`
 * 本来也只会在"未被 catch 的异常"上触发，取消不会走到这里。
 */
internal fun logOnlyExceptionHandler(tag: String): CoroutineExceptionHandler =
    CoroutineExceptionHandler { _, error ->
        Logger.w(
            tag,
            "后台任务抛出了没人接的异常（已记录，未让应用崩掉）: ${error.message ?: error.javaClass.simpleName}",
            error,
        )
    }
