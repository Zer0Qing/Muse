package io.zer0.muse.data.proactive

/**
 * 主动消息「主动程度」档位。
 *
 * 用户只需要表达"想让它多主动"，不需要理解「间隔 / 发送概率 / 每日上限」三个实现参数。
 * 本枚举把三者的常用组合打包成三档；[from] 支持从旧的三值反推档位，
 * 反推不到（用户自定义过）返回 null，界面显示「自定义」。
 *
 * 注意：STANDARD 的三个值刻意与 [io.zer0.muse.data.ProactiveMessageConfig] 的默认值一致，
 * 保证老用户升级后界面直接显示「标准」，配置不漂移。
 */
enum class ProactivePace(
    /** 触发间隔（分钟）。 */
    val intervalMinutes: Int,
    /** 发送概率（0-100）。 */
    val sendProbability: Int,
    /** 每日主动消息上限（条）。 */
    val maxDailyMessages: Int,
) {
    /** 少：半天一次左右，一半概率，每天最多 1 条。 */
    LIGHT(720, 50, 1),

    /** 标准：约 4 小时一次，每次都发，每天最多 3 条（默认）。 */
    STANDARD(240, 100, 3),

    /** 多：约 2 小时一次，每天都发满，每天最多 8 条。 */
    HEAVY(120, 100, 8),

    ;

    companion object {
        /** 从当前三值反推档位；不匹配任何档位返回 null（界面显示「自定义」）。 */
        fun from(intervalMinutes: Int, sendProbability: Int, maxDailyMessages: Int): ProactivePace? =
            values().firstOrNull {
                it.intervalMinutes == intervalMinutes &&
                    it.sendProbability == sendProbability &&
                    it.maxDailyMessages == maxDailyMessages
            }
    }
}
