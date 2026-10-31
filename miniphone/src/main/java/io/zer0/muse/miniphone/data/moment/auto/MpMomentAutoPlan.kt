package io.zer0.muse.miniphone.data.moment.auto

import java.time.Instant
import java.time.ZoneId

/**
 * 「这一轮要不要发」的判定结果。
 *
 * 只有 [Generate] 会继续往下走,其余一律原地结束 —— 后台生成是锦上添花的事,
 * 任何一个前置条件不满足都宁可不发。
 */
sealed interface MpMomentAutoDecision {
    /** 该发了。 */
    data object Generate : MpMomentAutoDecision

    /** 这次不发,原因见 [reason]。 */
    data class Skip(val reason: MpMomentSkipReason) : MpMomentAutoDecision
}

/**
 * 不生成的原因。
 *
 * [value] 是写进日志的取值,形如 `朋友圈定时跳过 | reason=too_soon`;
 * 用固定英文标识而不是中文句子,是为了事后能直接 grep 和统计。
 */
enum class MpMomentSkipReason(
    val value: String,
) {
    /** 「自动发朋友圈」开关关着。 */
    DISABLED("disabled"),

    /** 安静时段([MpMomentAutoPlan.QUIET_FROM_HOUR]–[MpMomentAutoPlan.QUIET_TO_HOUR]),不打扰。 */
    QUIET_HOURS("quiet_hours"),

    /** 离上次生成不足 [MpMomentAutoPlan.MIN_INTERVAL_MS]:WorkManager 的周期不精确,可能连着触发。 */
    TOO_SOON("too_soon"),

    /** 概率没中:留白比刷屏重要。 */
    PROBABILITY("probability"),

    /** 宿主那边一个角色都没有。 */
    NO_CHARACTER("no_character"),
}

/**
 * 后台生成动态的纯判定。
 *
 * 这里不碰时钟、不碰随机源、不碰宿主 —— 时间与随机数由调用方交进来,
 * 于是「23 点该不该发」「距上次 30 分钟该不该发」可以在纯 JVM 测试里钉死。
 *
 * 判定顺序固定为:开关 → 安静时段 → 防抖 → 概率 → 有没有角色。
 * 越省事的条件越靠前:安静时段与防抖都是纯本地判断,先它俩能挡掉绝大多数唤醒,
 * 一次网络调用都不会发。
 */
object MpMomentAutoPlan {
    /** 安静时段起点(含):22 点。 */
    const val QUIET_FROM_HOUR: Int = 22

    /** 安静时段终点(不含):8 点。 */
    const val QUIET_TO_HOUR: Int = 8

    /** 两次生成的最小间隔:90 分钟。 */
    const val MIN_INTERVAL_MS: Long = 90L * 60L * 1000L

    /** 真的发出去的概率 60%:剩下 40% 的唤醒什么都不发,给时间线留白。 */
    const val PUBLISH_PROBABILITY: Double = 0.6

    /** 取当前小时(调用方给的时区,用户看到的「现在几点」)。 */
    fun hourOf(nowMillis: Long, zone: ZoneId): Int = Instant.ofEpochMilli(nowMillis).atZone(zone).hour

    /** 是否落在安静时段:[QUIET_FROM_HOUR] 起、到次日 [QUIET_TO_HOUR] 止,跨零点。 */
    fun isQuietHour(hourOfDay: Int): Boolean = hourOfDay >= QUIET_FROM_HOUR || hourOfDay < QUIET_TO_HOUR

    /**
     * 这一轮发不发。
     *
     * @param lastGeneratedAtMillis 上次成功生成的时间;`null`/`0` 表示从没生成过
     * @param roll 本次的概率骰子,取值 `[0, 1)`;`>=` [PUBLISH_PROBABILITY] 就跳过
     * @param hasCharacter 宿主当前是否有可用角色
     */
    fun decide(
        nowMillis: Long,
        lastGeneratedAtMillis: Long?,
        roll: Double,
        hasCharacter: Boolean,
        enabled: Boolean = true,
        zone: ZoneId = ZoneId.systemDefault(),
    ): MpMomentAutoDecision = when {
        !enabled -> MpMomentAutoDecision.Skip(MpMomentSkipReason.DISABLED)
        isQuietHour(hourOf(nowMillis, zone)) -> MpMomentAutoDecision.Skip(MpMomentSkipReason.QUIET_HOURS)
        isTooSoon(nowMillis, lastGeneratedAtMillis) -> MpMomentAutoDecision.Skip(MpMomentSkipReason.TOO_SOON)
        roll >= PUBLISH_PROBABILITY -> MpMomentAutoDecision.Skip(MpMomentSkipReason.PROBABILITY)
        !hasCharacter -> MpMomentAutoDecision.Skip(MpMomentSkipReason.NO_CHARACTER)
        else -> MpMomentAutoDecision.Generate
    }

    /** 按小时给一句「这一条写点什么」的范围提示,交给宿主的生成器当素材。 */
    fun hintFor(hourOfDay: Int): String = when (hourOfDay) {
        in 8..10 -> "早上刚醒，随手记一句今天的状态"
        in 11..13 -> "中午得空，说点手头在做的事"
        in 14..17 -> "下午的碎片时间，记一句"
        in 18..21 -> "晚上收工，随便聊聊今天"
        else -> "夜里还没睡，随口感叹一句"
    }

    /** 从没生成过(`null` / `0`)不算「太快」,否则首次唤醒永远发不出去。 */
    private fun isTooSoon(nowMillis: Long, lastGeneratedAtMillis: Long?): Boolean {
        if (lastGeneratedAtMillis == null || lastGeneratedAtMillis <= 0L) return false
        return nowMillis - lastGeneratedAtMillis < MIN_INTERVAL_MS
    }
}
