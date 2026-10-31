package io.zer0.muse.miniphone.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import io.zer0.common.Logger
import io.zer0.common.resultOf
import java.util.concurrent.TimeUnit

/**
 * 角色主动私信的周期任务注册口。
 *
 * 由模块的后台注册口([MiniPhoneMomentScheduler.start])一并排期,宿主不需要多认一个入口。
 *
 * 注册要点:
 * - **周期 3 小时**:与朋友圈同一个节拍,但**初始延迟 90 分钟**:两者错开半天,
 *   不会在同一个时刻一起醒来抢网络与 CPU
 * - **约束**:要联网(生成必须走网络)、电量不低(低电量时别把用户手机拉起来做生成)
 * - **唯一任务 + KEEP**:同一时刻只有一个实例;已注册就保留旧的,不重置周期
 *   (否则每次冷启动都会把计时器清零,永远不会到点)
 */
object MiniPhoneProactiveChatScheduler {

    /** 唯一任务名。改它会留下一份孤儿周期任务,只能靠 [stop] 显式取消。 */
    const val UNIQUE_WORK_NAME: String = "miniphone_proactive_chat"

    /** 周期:3 小时。 */
    const val REPEAT_INTERVAL_HOURS: Long = 3L

    /** 首次排期往后挪 90 分钟:与朋友圈任务错开,避免同一时刻抢资源。 */
    const val INITIAL_DELAY_MINUTES: Long = 90L

    /** 注册周期任务。重复调用是安全的(KEEP)。 */
    fun start(context: Context) {
        resultOf {
            val request = PeriodicWorkRequestBuilder<MpProactiveChatWorker>(REPEAT_INTERVAL_HOURS, TimeUnit.HOURS)
                .setInitialDelay(INITIAL_DELAY_MINUTES, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }.onError { msg, t -> Logger.w(TAG, "主动私信注册失败 | reason=${t?.message ?: msg}", t) }
    }

    /** 取消周期任务(下次 [start] 会重新排期)。 */
    fun stop(context: Context) {
        resultOf { WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE_WORK_NAME) }
            .onError { msg, t -> Logger.w(TAG, "主动私信取消失败 | reason=${t?.message ?: msg}", t) }
    }

    private const val TAG: String = "MiniPhoneChat"
}
