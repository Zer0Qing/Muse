package io.zer0.muse.proactive

import io.zer0.muse.data.proactive.OutcomeState
import kotlin.math.floor
import kotlin.math.min

/**
 * 智能时机打分(能力增强 P1)。
 *
 * 取代「所有硬闸通过后掷骰子」的发送概率:同样的 0..99 掷骰,但有效概率由
 * 最近的用户反馈信号调低或调高 —— 每一条修正都要能用人话解释
 * (「上一条还没回」≠「半夜不该说话」≠「已读堆着没看」),日志逐条记录。
 *
 * 与 [ProactiveSendProbability] 的关系不是替代而是二选一:开关
 * (`sendTimingScoringEnabled`)关闭时走原掷骰路径,行为与 2.6.22 逐字一致;
 * 开启后掷骰仍保留,**但骰子之前先按信号折算有效概率** —— 不新增第二套配额,
 * 间隔/额度/免打扰/每日保底等硬闸全部在更前面,这里只决定「都通过了,说还是不说」。
 *
 * 纯函数:没有时钟、没有 IO,roll 由调用方注入,信号由调用方采好 —— 可断言、可回归。
 */
object ProactiveSendScoring {

    /** 掷骰范围上界(不含),与 [ProactiveSendProbability.ROLL_BOUND] 同语义。 */
    const val ROLL_BOUND = 100

    /** 本轮判定可用的现场信号(调用方采集,本类不读库不读时钟)。 */
    data class Signals(
        /** 用户配置的基准发送概率(0..100;语义与原概率闸一致:0=永不,100=有信号修正也顶满)。 */
        val configuredPercent: Int,
        /** 上一条主动消息的结果账状态;null = 从没发过(不加任何修正)。 */
        val lastOutcomeState: OutcomeState?,
        /** 距上一条主动消息多少分钟;null = 从没发过。 */
        val minutesSinceLastProactive: Long?,
        /** 会话里用户还没读的条数(读不到传 0,不因此加任何分)。 */
        val userUnreadCount: Int,
        /** 账本里还有没有没说的牵挂。有牵挂可以加分,没牵挂不扣分(模型自己判有没有话)。 */
        val hasOpenThoughts: Boolean,
        /** 0..23,本地钟点。 */
        val hourOfDay: Int,
    )

    /** 判定结果。[reasons] 为空表示没有任何信号参与 —— 与原掷骰闸完全等价。 */
    data class Decision(
        val send: Boolean,
        val effectivePercent: Int,
        val reasons: List<String>,
    )

    /** 上一条发出去没人理、且还没过隔夜的冷却窗口时,压一半概率。 */
    private const val UNANSWERED_GRACE_MINUTES = 180L

    /** 未读达到这个数,说明用户没在追消息,主动发送先等等。 */
    private const val UNREAD_BACKLOG = 3

    private const val NIGHT_START = 0
    private const val NIGHT_END = 5

    fun evaluate(signals: Signals, rollPercent: Int): Decision {
        val base = signals.configuredPercent.coerceIn(0, ROLL_BOUND)
        if (base == 0) {
            return Decision(send = false, effectivePercent = 0, reasons = listOf("配置为 0%"))
        }
        val reasons = mutableListOf<String>()
        var percent = base.toDouble()

        // 上一条没人回/被划掉:发信方连发只会更烦。隔夜之后不再压(账要往前翻)。
        val lastIgnored = signals.lastOutcomeState == OutcomeState.PENDING ||
            signals.lastOutcomeState == OutcomeState.DISMISSED
        if (lastIgnored && signals.minutesSinceLastProactive != null &&
            signals.minutesSinceLastProactive < UNANSWERED_GRACE_MINUTES
        ) {
            percent *= 0.5
            reasons += "上一条主动消息还没有回应"
        }
        // 会话里堆着没读的消息:再发只会加深积压。
        if (signals.userUnreadCount >= UNREAD_BACKLOG) {
            percent *= 0.5
            reasons += "用户还有 ${signals.userUnreadCount} 条没读"
        }
        // 深夜(0-5 点):除非顶格配置,概率减半 —— 手机在枕边,打扰成本更高。
        if (signals.hourOfDay in NIGHT_START..NIGHT_END && base < ROLL_BOUND) {
            percent *= 0.5
            reasons += "深夜时段"
        }
        // 账上有到点的牵挂:比"没话说还硬找话说"更值得开口,但不顶破用户配置的基准。
        if (signals.hasOpenThoughts) {
            val boosted = min(percent * 1.5, base.toDouble())
            if (boosted > percent) {
                reasons += "账本里有到点的事"
                percent = boosted
            }
        }

        val effective = floor(percent).toInt().coerceIn(0, ROLL_BOUND)
        val send = rollPercent.coerceIn(0, ROLL_BOUND - 1) < effective
        return Decision(send = send, effectivePercent = effective, reasons = reasons)
    }
}
