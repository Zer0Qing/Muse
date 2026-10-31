package io.zer0.muse.miniphone.data.chat.auto

import io.zer0.muse.miniphone.data.moment.auto.MpMomentAutoPlan
import java.time.ZoneId

/**
 * 「这一轮要不要主动发私信」的判定结果。
 *
 * 只有 [Send] 会继续往下走:主动私信是锦上添花的事,任何一个前置条件不满足都宁可不发。
 */
sealed interface MpProactiveChatDecision {
    /** 该发了。 */
    data object Send : MpProactiveChatDecision

    /** 这次不发,原因见 [reason]。 */
    data class Skip(val reason: MpProactiveChatSkipReason) : MpProactiveChatDecision
}

/**
 * 不主动发私信的原因。
 *
 * [value] 是写进日志的取值,形如 `主动私信跳过 | reason=too_soon`;
 * 用固定英文标识而不是中文句子,是为了事后能直接 grep 和统计。
 */
enum class MpProactiveChatSkipReason(
    val value: String,
) {
    /** 「角色主动私信」开关关着。 */
    DISABLED("disabled"),

    /** 安静时段(22–08),不打扰。 */
    QUIET_HOUR("quiet_hour"),

    /** 用户刚刚还在说话(10 分钟内):此时插一句会打断对话。 */
    USER_JUST_ACTIVE("user_just_active"),

    /** 宿主那边一个角色都没有。 */
    NO_CHARACTER("no_character"),

    /** 没有可搭话的会话:一条消息都没有过,角色开口会像骚扰。 */
    NO_HISTORY("no_history"),

    /** 距上次主动私信不足 [MpProactiveChatPlan.MIN_INTERVAL_MS]。 */
    TOO_SOON("too_soon"),

    /** 概率没中:大多数唤醒什么都不发。 */
    PROBABILITY("probability"),

    /** 判定说要发,但这一句没生成出来(空白输出),不落库。 */
    NO_CONTENT("no_content"),
}

/**
 * 主动发私信的纯判定。
 *
 * 这里不碰时钟、不碰随机源、不碰宿主 —— 时间与随机数由调用方交进来,
 * 于是「23 点该不该发」「距上次 3 小时该不该发」可以在纯 JVM 测试里钉死。
 *
 * 判定顺序固定(顺序即优先级,前一条命中就返回,入参也按这个顺序排列):
 * 1. 开关
 * 2. 安静时段
 * 3. 用户刚刚活跃(10 分钟内说过话)
 * 4. 有没有角色
 * 5. 有没有可搭话的会话
 * 6. 距上次的间隔
 * 7. 概率
 * 8. 都不拦 → [MpProactiveChatDecision.Send]
 *
 * 越省事的条件越靠前:开关、时段、用户活跃、防抖全是纯本地判断,先它们能挡掉绝大多数唤醒,
 * 一次网络调用都不会发。选择会话本身有成本,所以由调用方先选好会话、把「最后一条用户消息时间」
 * 交进来 —— 会话选择在 [decide] 之前完成,判定链里只剩纯比较。
 *
 * 安静时段的边界与朋友圈共用一处定义(22 点起、8 点止),不在这里重写一份,
 * 免得两条边界日后各自漂移。
 */
object MpProactiveChatPlan {
    /** 两次主动私信的最小间隔:4 小时。 */
    const val MIN_INTERVAL_MS: Long = 4L * 60L * 60L * 1000L

    /** 用户说过话之后的冷却时间:10 分钟。 */
    const val USER_ACTIVE_WINDOW_MS: Long = 10L * 60L * 1000L

    /** 真的发出去的概率 35%:剩下 65% 的唤醒什么都不发,不刷屏。 */
    const val SEND_PROBABILITY: Float = 0.35f

    /** 取当前小时(调用方给的时区,用户看到的「现在几点」)。 */
    fun hourOf(nowMillis: Long, zone: ZoneId): Int = MpMomentAutoPlan.hourOf(nowMillis, zone)

    /** 是否落在安静时段:22 点起、到次日 8 点止,跨零点。 */
    fun isQuietHour(hourOfDay: Int): Boolean = MpMomentAutoPlan.isQuietHour(hourOfDay)

    /**
     * 这一轮发不发。
     *
     * @param lastUserMessageAtMillis 选中会话里最后一条用户消息的时间;`null`/`0` 表示用户没在这里说过话
     * @param lastProactiveAtMillis 上次主动私信成功的时间;`null`/`0` 表示从没发过,不判「太快」
     * @param random 概率骰子,取值 `[0, 1)`;`>=` [SEND_PROBABILITY] 就跳过。只在前七条都没拦下时才会被调用
     */
    // 参数表与判定链一一对应(顺序即优先级):收进一个对象就看不出判定顺序了
    @Suppress("LongParameterList")
    fun decide(
        nowMillis: Long,
        enabled: Boolean,
        lastUserMessageAtMillis: Long?,
        characterCount: Int,
        hasEligibleSession: Boolean,
        lastProactiveAtMillis: Long?,
        random: () -> Float,
        zone: ZoneId = ZoneId.systemDefault(),
    ): MpProactiveChatDecision = when {
        !enabled -> MpProactiveChatDecision.Skip(MpProactiveChatSkipReason.DISABLED)
        isQuietHour(hourOf(nowMillis, zone)) -> MpProactiveChatDecision.Skip(MpProactiveChatSkipReason.QUIET_HOUR)
        isUserJustActive(nowMillis, lastUserMessageAtMillis) ->
            MpProactiveChatDecision.Skip(MpProactiveChatSkipReason.USER_JUST_ACTIVE)

        characterCount <= 0 -> MpProactiveChatDecision.Skip(MpProactiveChatSkipReason.NO_CHARACTER)
        !hasEligibleSession -> MpProactiveChatDecision.Skip(MpProactiveChatSkipReason.NO_HISTORY)
        isTooSoon(nowMillis, lastProactiveAtMillis) -> MpProactiveChatDecision.Skip(MpProactiveChatSkipReason.TOO_SOON)
        random() >= SEND_PROBABILITY -> MpProactiveChatDecision.Skip(MpProactiveChatSkipReason.PROBABILITY)
        else -> MpProactiveChatDecision.Send
    }

    /** 用户 10 分钟内说过话:此刻插话就是打断。从没说过话(`null` / `0`)不算活跃。 */
    private fun isUserJustActive(nowMillis: Long, lastUserMessageAtMillis: Long?): Boolean {
        if (lastUserMessageAtMillis == null || lastUserMessageAtMillis <= 0L) return false
        return nowMillis - lastUserMessageAtMillis < USER_ACTIVE_WINDOW_MS
    }

    /** 从没发过(`null` / `0`)不算「太快」,否则首次唤醒永远发不出去。 */
    private fun isTooSoon(nowMillis: Long, lastProactiveAtMillis: Long?): Boolean {
        if (lastProactiveAtMillis == null || lastProactiveAtMillis <= 0L) return false
        return nowMillis - lastProactiveAtMillis < MIN_INTERVAL_MS
    }
}
