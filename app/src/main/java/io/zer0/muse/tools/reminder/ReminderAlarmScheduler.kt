package io.zer0.muse.tools.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import io.zer0.common.Logger
import io.zer0.common.resultOf

/**
 * 提醒闹钟的统一排期入口。
 *
 * 提醒工具（`schedule_reminder`）与快速记录页的提醒共用这里，避免三处 AlarmManager
 * 调用各自实现、行为漂移。
 *
 * 排期策略：
 *  - 有精确闹钟权限时用 `setExactAndAllowWhileIdle`，到点准时触发；
 *  - 没有权限时降级到 `setAndAllowWhileIdle`（非精确，可能延迟数分钟），保证「会响」，
 *    而不是像以前那样直接失败、把用户委托的提醒悄悄丢掉。
 *  - 系统拒绝排期（抛异常）时返回 false，并把失败原因通过 [ReminderEntry.scheduleError] 记到条目上。
 */
object ReminderAlarmScheduler {

    private const val TAG = "ReminderAlarmScheduler"

    /** 单次排期结果。 */
    data class ScheduleResult(
        val scheduled: Boolean,
        /** 是否用的是精确闹钟（false 表示已降级为非精确，可能延迟）。 */
        val exact: Boolean,
        /** 是否需要引导用户去开启「闹钟和提醒」权限。 */
        val needsExactPermission: Boolean,
        /** 失败时的可读原因，成功为 null。 */
        val error: String? = null,
    )

    /** 当前是否具备精确闹钟权限（API < 31 视为始终具备）。 */
    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return am.canScheduleExactAlarms()
    }

    /**
     * 跳到系统的「闹钟和提醒」授权页，让用户手动开启精确闹钟权限。
     *
     * 优先用 `ACTION_REQUEST_SCHEDULE_EXACT_ALARM`；个别 ROM 没有该页面时退回应用详情页，
     * 保证用户总能到达一个能改权限的地方。
     *
     * @return 是否成功拉起某个系统页面
     */
    fun requestExactAlarmPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val pkgUri = android.net.Uri.fromParts("package", context.packageName, null)
        val candidates = listOf(
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkgUri),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri),
        )
        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val ok = resultOf { context.startActivity(intent) }
                .onError { msg, _ -> Logger.w(TAG, "打开精确闹钟设置页失败: $msg") }
                .isSuccess
            if (ok) return true
        }
        return false
    }

    /** 拼接触发提醒用的 PendingIntent（与 [ReminderAlarmReceiver] 约定一致）。 */
    private fun buildPendingIntent(
        context: Context,
        id: String,
        title: String,
        message: String,
        targetType: String,
        targetId: String,
    ): PendingIntent {
        val intent = Intent(context, ReminderAlarmReceiver::class.java).apply {
            putExtra(ReminderAlarmReceiver.EXTRA_ID, id)
            putExtra(ReminderAlarmReceiver.EXTRA_TITLE, title)
            putExtra(ReminderAlarmReceiver.EXTRA_MESSAGE, message)
            putExtra(ReminderAlarmReceiver.EXTRA_TARGET_TYPE, targetType)
            putExtra(ReminderAlarmReceiver.EXTRA_TARGET_ID, targetId)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getBroadcast(context, id.hashCode(), intent, flags)
    }

    /**
     * 排期一条提醒。
     *
     * @param isCatchUp 补发场景（提醒时间已过）：标题加「(补)」，并且仍然走精确/降级同一套逻辑。
     */
    fun schedule(
        context: Context,
        id: String,
        title: String,
        message: String,
        triggerAtMillis: Long,
        targetType: String = ReminderAlarmReceiver.TARGET_HOME,
        targetId: String = "",
    ): ScheduleResult {
        val exactAvailable = canScheduleExact(context)
        return resultOf {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = buildPendingIntent(context, id, title, message, targetType, targetId)
            if (exactAvailable) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
                } else {
                    am.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
                }
            } else {
                // 无精确闹钟权限：降级为非精确排期，保证提醒「会响」，只是可能晚几分钟。
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
                } else {
                    am.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
                }
            }
            ScheduleResult(
                scheduled = true,
                exact = exactAvailable,
                needsExactPermission = !exactAvailable,
            )
        }.onError { msg, _ -> Logger.w(TAG, "排期提醒失败: id=$id, $msg") }
            .getOrNull()
            ?: ScheduleResult(
                scheduled = false,
                exact = false,
                needsExactPermission = !exactAvailable,
                error = "alarm_schedule_failed",
            )
    }

    /** 取消一条提醒的闹钟（不影响其它条目的 PendingIntent）。 */
    fun cancel(context: Context, id: String) {
        resultOf {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, ReminderAlarmReceiver::class.java)
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pi = PendingIntent.getBroadcast(context, id.hashCode(), intent, flags)
            am.cancel(pi)
            pi.cancel()
        }.onError { msg, _ -> Logger.w(TAG, "取消提醒闹钟失败: id=$id, $msg") }
    }
}
