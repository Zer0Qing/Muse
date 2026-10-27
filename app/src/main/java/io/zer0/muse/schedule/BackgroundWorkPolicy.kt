package io.zer0.muse.schedule

import kotlinx.coroutines.CancellationException

/**
 * 后台调度总控的失败策略。
 *
 * SettingsRepository 的 Flow 自身提供默认值；只有读取链路明确得到 true 才允许
 * 继续执行副作用任务。读取失败或依赖缺失时跳过本次执行，避免用户关闭后台调度
 * 后因 DataStore/Koin 异常被 fail-open 放行通知、网络或备份操作。
 */
internal fun scheduleWorkEnabledOrFalse(value: Boolean?): Boolean = value == true

internal fun scheduleWorkEnabledFromRepository(available: Boolean, value: Boolean?): Boolean =
    available && scheduleWorkEnabledOrFalse(value)

internal suspend fun readScheduleWorkEnabledOrFalse(reader: suspend () -> Boolean): Boolean = try {
    scheduleWorkEnabledOrFalse(reader())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    false
}
