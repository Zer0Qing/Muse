package io.zer0.muse.miniphone.data.chat.auto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 「这一轮该不该主动发私信」的纯判定。
 *
 * 这里不涉及 Room、不发网络、不读系统时钟:时间点、时区与骰子都由测试给出,
 * 于是「23 点该跳过」「距上次 2 小时该跳过」这类边界可以被钉死。
 */
class MpProactiveChatPlanTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    @Test
    fun switchOffSkipsBeforeAnythingElse() {
        // 开关关着时,即便时间、会话、概率都满足也不发(它是默认值,绝大多数用户走这一条)
        assertEquals(MpProactiveChatDecision.Skip(MpProactiveChatSkipReason.DISABLED), decide(at(14), enabled = false))
    }

    @Test
    fun quietHourAtNightSkips() {
        assertEquals(MpProactiveChatDecision.Skip(MpProactiveChatSkipReason.QUIET_HOUR), decide(at(23)))
    }

    @Test
    fun noCharacterSkips() {
        assertEquals(MpProactiveChatDecision.Skip(MpProactiveChatSkipReason.NO_CHARACTER), decide(at(14), characterCount = 0))
    }

    @Test
    fun noEligibleSessionSkips() {
        assertEquals(
            MpProactiveChatDecision.Skip(MpProactiveChatSkipReason.NO_HISTORY),
            decide(at(14), hasEligibleSession = false),
        )
    }

    @Test
    fun twoHoursAfterLastProactiveChatSkipsAsTooSoon() {
        val state = decide(at(14), lastProactiveAtMillis = at(12))
        assertEquals(MpProactiveChatDecision.Skip(MpProactiveChatSkipReason.TOO_SOON), state)
    }

    @Test
    fun highRollSkipsAsProbability() {
        assertEquals(MpProactiveChatDecision.Skip(MpProactiveChatSkipReason.PROBABILITY), decide(at(14), roll = 0.9f))
    }

    @Test
    fun allClearSends() {
        assertEquals(MpProactiveChatDecision.Send, decide(at(14)))
    }

    @Test
    fun userTalkingThreeMinutesAgoSkipsAsJustActive() {
        val state = decide(at(14), lastUserMessageAtMillis = at(13, minute = 57))
        assertEquals(MpProactiveChatDecision.Skip(MpProactiveChatSkipReason.USER_JUST_ACTIVE), state)
    }

    @Test
    fun exactlyTenMinutesOfSilenceKeepsGoing() {
        // 边界在「小于 10 分钟」上:刚好 10 分钟就该放行
        val state = decide(at(14), lastUserMessageAtMillis = at(13, minute = 50))
        assertEquals(MpProactiveChatDecision.Send, state)
    }

    @Test
    fun userNeverSpokeHereIsNotJustActive() {
        // 会话里用户一句话都没说过(null):不能当成「刚刚活跃」
        assertEquals(MpProactiveChatDecision.Send, decide(at(14), lastUserMessageAtMillis = null))
    }

    @Test
    fun userJustActiveWinsOverTooSoon() {
        // 距上次主动私信 5 小时(已过 4 小时间隔),但用户 3 分钟前说过话 → 应当报 user_just_active
        val state = decide(at(14), lastProactiveAtMillis = at(9), lastUserMessageAtMillis = at(13, minute = 57))
        assertEquals(MpProactiveChatDecision.Skip(MpProactiveChatSkipReason.USER_JUST_ACTIVE), state)
    }

    @Test
    fun neverSentBeforeIsNotTooSoon() {
        // 从没发过(lastProactiveAtMillis = null):不能判「太快」,否则第一次永远发不出去
        assertEquals(MpProactiveChatDecision.Send, decide(at(14), lastProactiveAtMillis = null))
    }

    @Test
    fun fourHoursAfterLastProactiveChatMaySend() {
        // 间隔正好 4 小时:边界在「小于」上,到点就该放行
        assertEquals(MpProactiveChatDecision.Send, decide(at(14), lastProactiveAtMillis = at(10)))
    }

    @Test
    fun rollJustBelowProbabilitySends() {
        assertEquals(MpProactiveChatDecision.Send, decide(at(14), roll = 0.34f))
    }

    @Test
    fun rollAtProbabilitySkips() {
        assertEquals(MpProactiveChatDecision.Skip(MpProactiveChatSkipReason.PROBABILITY), decide(at(14), roll = 0.35f))
    }

    @Test
    fun quietHourWinsOverOtherReasons() {
        // 23 点且距上次 10 分钟:应当先报安静时段,而不是 too_soon
        val state = decide(at(23), lastProactiveAtMillis = at(22, minute = 50))
        assertEquals(MpProactiveChatDecision.Skip(MpProactiveChatSkipReason.QUIET_HOUR), state)
    }

    @Test
    fun quietHourCoversLateEveningAndEarlyMorning() {
        assertTrue(MpProactiveChatPlan.isQuietHour(22))
        assertTrue(MpProactiveChatPlan.isQuietHour(2))
        assertFalse(MpProactiveChatPlan.isQuietHour(8))
        assertFalse(MpProactiveChatPlan.isQuietHour(21))
    }

    private fun decide(
        now: Long,
        enabled: Boolean = true,
        lastProactiveAtMillis: Long? = null,
        lastUserMessageAtMillis: Long? = null,
        characterCount: Int = 1,
        hasEligibleSession: Boolean = true,
        roll: Float = 0.1f,
    ): MpProactiveChatDecision = MpProactiveChatPlan.decide(
        nowMillis = now,
        enabled = enabled,
        lastUserMessageAtMillis = lastUserMessageAtMillis,
        characterCount = characterCount,
        hasEligibleSession = hasEligibleSession,
        lastProactiveAtMillis = lastProactiveAtMillis,
        random = { roll },
        zone = zone,
    )

    private fun at(hour: Int, minute: Int = 0): Long = LocalDateTime.of(2026, 5, 12, hour, minute).atZone(zone).toInstant().toEpochMilli()
}
