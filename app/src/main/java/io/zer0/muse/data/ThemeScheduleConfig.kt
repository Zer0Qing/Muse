package io.zer0.muse.data

import kotlinx.serialization.Serializable

/**
 * 主题定时切换配置。
 *
 * 在指定时间自动切换亮色/深色模式。
 */
@Serializable
data class ThemeScheduleConfig(
    /** 总开关。 */
    val enabled: Boolean = false,
    /** 起床时间(小时,0-23)。到此后切换为浅色模式(或跟随系统)。 */
    val wakeUpHour: Int = 7,
    /** 起床时间(分钟,0-59)。 */
    val wakeUpMinute: Int = 0,
    /** 睡觉时间(小时,0-23)。到此后切换为深色模式。 */
    val sleepHour: Int = 22,
    /** 睡觉时间(分钟,0-59)。 */
    val sleepMinute: Int = 0,
)
