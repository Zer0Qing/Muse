package io.zer0.muse.data.proactive

/**
 * 主动消息「主动程度」档位。
 *
 * 用户只需要表达"想让它多主动"，不需要理解「间隔 / 发送概率」两个实现参数。
 * 本枚举把两者的常用组合打包成四档；[from] 支持从旧的三值反推档位，
 * 反推不到（用户自定义过）返回 null，界面显示「自定义」。
 *
 * **每日条数不在这里**：它由 [ProactiveIntensity.cap] 控制（安静 3 / 克制 8 /
 * 标准 15 / 主动 30 / 黏人不限）。这里只管「多久考虑一次、命中后发不发」。
 */
enum class ProactivePace(
    /** 触发间隔（分钟）。 */
    val intervalMinutes: Int,
    /** 发送概率（0-100）。 */
    val sendProbability: Int,
) {
    /** 少：半天一次左右，一半概率。 */
    LIGHT(720, 50),

    /** 标准：约 4 小时一次，每次都发（默认）。 */
    STANDARD(240, 100),

    /** 多：约 2 小时一次，每次都发。 */
    HEAVY(120, 100),

    /**
     * 多：约 30 分钟一次，每次都发。
     *
     * 这是最密的档：30 分钟正好是判定层允许的间隔下限
     * （[ProactiveTimingEngine.MIN_INTERVAL_MINUTES]），超出下限再密也不会生效。
     * 每日条数仍由 [ProactiveIntensity.cap] 决定。
     */
    EXTREME(30, 100),

    ;

    companion object {
        /** 从间隔与概率反推档位；不匹配任何档位返回 null（界面显示「自定义」）。 */
        fun from(intervalMinutes: Int, sendProbability: Int): ProactivePace? = values().firstOrNull {
            it.intervalMinutes == intervalMinutes &&
                it.sendProbability == sendProbability
        }
    }
}
