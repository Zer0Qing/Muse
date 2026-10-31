package io.zer0.muse.miniphone.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.miniphone.data.chat.auto.MpProactiveChatRunner
import org.koin.core.context.GlobalContext

/**
 * 后台定时让角色主动发一条私信的 Worker。
 *
 * 本体只做三件事:从 Koin 取 [MpProactiveChatRunner]、跑一轮、无论结果都返回 success。
 * 「发不发」的判断全在 runner 里(纯函数 + 日志),这里不掺业务。
 *
 * 永不返回 `retry`:主动消息不是必须送达的任务,失败就等下个周期 —— 返回 retry 只会让系统
 * 在 30 秒后用同一个必然失败的上下文再打一次网络,把一次打扰变成连着三次。
 *
 * Koin 取不到(安全模式 / 模块未装配)时同样返回 success:重试也取不到。
 */
class MpProactiveChatWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val runner = resultOf { GlobalContext.get().get<MpProactiveChatRunner>() }
            .onError { msg, t -> Logger.w(TAG, "主动私信失败 | reason=koin_missing | ${t?.message ?: msg}", t) }
            .getOrNull()
        if (runner == null) return Result.success()
        resultOf { runner.run() }
            .onError { msg, t -> Logger.w(TAG, "主动私信失败 | reason=runner_error | ${t?.message ?: msg}", t) }
        return Result.success()
    }

    companion object {
        private const val TAG: String = "MiniPhoneChat"
    }
}
