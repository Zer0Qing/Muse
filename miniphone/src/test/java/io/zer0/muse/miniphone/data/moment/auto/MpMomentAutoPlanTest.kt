package io.zer0.muse.miniphone.data.moment.auto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 「这一轮该不该发」的纯判定。
 *
 * 这里不涉及 Room、不发网络、不读系统时钟:时间点与时区都由测试给出,
 * 于是「23 点该跳过」「距上次 30 分钟该跳过」这类边界可以被钉死。
 */
class MpMomentAutoPlanTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    @Test
    fun quietHoursAtNightSkips() {
        assertEquals(MpMomentAutoDecision.Skip(MpMomentSkipReason.QUIET_HOURS), decide(at(23)))
    }

    @Test
    fun quietHoursBeforeMorningSkips() {
        assertEquals(MpMomentAutoDecision.Skip(MpMomentSkipReason.QUIET_HOURS), decide(at(7)))
    }

    @Test
    fun eightOClockIsAlreadyAwake() {
        assertEquals(MpMomentAutoDecision.Generate, decide(at(8)))
    }

    @Test
    fun thirtyMinutesAfterLastGenerationSkipsAsTooSoon() {
        val state = decide(at(14), lastGeneratedAtMillis = at(13, minute = 30))
        assertEquals(MpMomentAutoDecision.Skip(MpMomentSkipReason.TOO_SOON), state)
    }

    @Test
    fun ninetyMinutesAfterLastGenerationMayPublish() {
        val state = decide(at(14), lastGeneratedAtMillis = at(12, minute = 30))
        assertEquals(MpMomentAutoDecision.Generate, state)
    }

    @Test
    fun highRollSkipsAsProbability() {
        val state = decide(at(14), roll = 0.9)
        assertEquals(MpMomentAutoDecision.Skip(MpMomentSkipReason.PROBABILITY), state)
    }

    @Test
    fun lowRollKeepsGoing() {
        assertEquals(MpMomentAutoDecision.Generate, decide(at(14), roll = 0.59))
    }

    @Test
    fun noCharacterSkips() {
        val state = decide(at(14), hasCharacter = false)
        assertEquals(MpMomentAutoDecision.Skip(MpMomentSkipReason.NO_CHARACTER), state)
    }

    @Test
    fun switchOffSkipsBeforeAnythingElse() {
        // 开关关着时,即便时间、概率、角色都满足也不发
        val state = decide(at(14), enabled = false)
        assertEquals(MpMomentAutoDecision.Skip(MpMomentSkipReason.DISABLED), state)
    }

    @Test
    fun quietHoursWinOverOtherReasons() {
        // 23 点且距上次 10 分钟:应当先报安静时段,而不是 too_soon
        val state = decide(at(23), lastGeneratedAtMillis = at(22, minute = 50))
        assertEquals(MpMomentAutoDecision.Skip(MpMomentSkipReason.QUIET_HOURS), state)
    }

    @Test
    fun quietHourCoversLateEveningAndEarlyMorning() {
        assertTrue(MpMomentAutoPlan.isQuietHour(22))
        assertTrue(MpMomentAutoPlan.isQuietHour(2))
        assertFalse(MpMomentAutoPlan.isQuietHour(8))
        assertFalse(MpMomentAutoPlan.isQuietHour(21))
    }

    @Test
    fun hintFollowsTheHourOfDay() {
        assertTrue(MpMomentAutoPlan.hintFor(9).startsWith("早上"))
        assertTrue(MpMomentAutoPlan.hintFor(12).startsWith("中午"))
        assertTrue(MpMomentAutoPlan.hintFor(15).startsWith("下午"))
        assertTrue(MpMomentAutoPlan.hintFor(20).startsWith("晚上"))
    }

    private fun decide(
        now: Long,
        lastGeneratedAtMillis: Long? = null,
        roll: Double = 0.1,
        hasCharacter: Boolean = true,
        enabled: Boolean = true,
    ): MpMomentAutoDecision = MpMomentAutoPlan.decide(
        nowMillis = now,
        lastGeneratedAtMillis = lastGeneratedAtMillis,
        roll = roll,
        hasCharacter = hasCharacter,
        enabled = enabled,
        zone = zone,
    )

    private fun at(hour: Int, minute: Int = 0): Long = LocalDateTime.of(2026, 5, 12, hour, minute).atZone(zone).toInstant().toEpochMilli()
}
