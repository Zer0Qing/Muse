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
 * 模块后台定时任务的注册口。
 *
 * 由宿主在「小手机可用」的时机调 [start] 一次、在关掉小手机时调 [stop]。
 * 本模块没有自己的 Application,也没有 manifest 里的初始化入口 —— 不在模块里偷偷自启:
 * WorkManager 是宿主已经在用的同一个运行时,注册时机属于宿主的产品决定。
 *
 * 宿主只认这一个入口,所以模块里的两套周期任务(朋友圈动态、角色主动私信)都在这里排期:
 * 主动私信那一套见 [MiniPhoneProactiveChatScheduler]。
 *
 * 注册要点:
 * - **周期 3 小时**:低频、不打扰。WorkManager 的周期本来就不精确(系统会按电量/待机往后挪),
 *   所以防抖不靠周期精度,靠 runner 里的判定
 * - **约束**:要联网(生成必须走网络)、电量不低(低电量时别把用户手机拉起来做生成)
 * - **唯一任务 + KEEP**:同一时刻只有一个实例;已注册就保留旧的,不重置周期
 *   (否则每次冷启动都会把计时器清零,永远不会到点)
 */
object MiniPhoneMomentScheduler {

    /** 唯一任务名。改它会留下一份孤儿周期任务,只能靠 [stop] 显式取消。 */
    const val UNIQUE_WORK_NAME: String = "miniphone_auto_moment"

    /** 周期:3 小时。 */
    private const val REPEAT_INTERVAL_HOURS: Long = 3L

    /** 注册周期任务。重复调用是安全的(KEEP)。 */
    fun start(context: Context) {
        resultOf {
            val request = PeriodicWorkRequestBuilder<MpMomentWorker>(REPEAT_INTERVAL_HOURS, TimeUnit.HOURS)
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
        }.onError { msg, t -> Logger.w(TAG, "朋友圈定时注册失败 | reason=${t?.message ?: msg}", t) }
        // 主动私信:同样的 3 小时周期 + 90 分钟初始延迟,与上面错开半拍
        MiniPhoneProactiveChatScheduler.start(context)
    }

    /** 取消周期任务(下次 [start] 会重新排期)。 */
    fun stop(context: Context) {
        resultOf { WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE_WORK_NAME) }
            .onError { msg, t -> Logger.w(TAG, "朋友圈定时取消失败 | reason=${t?.message ?: msg}", t) }
        MiniPhoneProactiveChatScheduler.stop(context)
    }

    private const val TAG: String = "MiniPhoneMoment"
}
