package io.zer0.muse.automation.core

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class AutonomousAccessibilityGrantTest {
    @Test
    fun `a task confirmation requires an operation request that names the target package`() {
        assertTrue(
            AutonomousAccessibilityTaskScope.confirms(
                "下午两点到五点可以自己刷 com.xingin.xhs，浏览小红书",
                "com.xingin.xhs",
            ),
        )
        assertFalse(
            AutonomousAccessibilityTaskScope.confirms(
                "帮我记住 com.xingin.xhs",
                "com.xingin.xhs",
            ),
        )
        assertFalse(
            AutonomousAccessibilityTaskScope.confirms(
                "帮我刷微信",
                "com.xingin.xhs",
            ),
        )
        assertTrue(
            AutonomousAccessibilityTaskScope.confirms(
                "帮我刷小红书",
                "com.xingin.xhs",
            ),
        )
        assertTrue(
            AutonomousAccessibilityTaskScope.confirms(
                "Use com.xingin.xhs to browse posts between 2 and 5",
                "com.xingin.xhs",
            ),
        )
    }

    @Test
    fun `task fingerprints are stable without persisting the original text`() {
        val first = AutonomousAccessibilityTaskScope.fingerprint("打开 com.xingin.xhs 刷小红书")
        val same = AutonomousAccessibilityTaskScope.fingerprint("  打开   com.xingin.xhs   刷小红书 ")
        val other = AutonomousAccessibilityTaskScope.fingerprint("在 com.xingin.xhs 点击购买")

        assertEquals(first, same)
        assertNotEquals(first, other)
        assertFalse(first.contains("小红书"))
    }

    @Test
    fun `local minute and calendar day are computed from the same instant`() {
        val zone = ZoneId.of("Asia/Singapore")
        val instant = java.time.ZonedDateTime.of(2026, 11, 3, 14, 30, 0, 0, zone).toInstant().toEpochMilli()

        assertEquals(14 * 60 + 30, AutonomousAccessibilityState.minuteOfDay(instant, zone))
        assertEquals("2026-11-03", AutonomousAccessibilityState.dateKey(instant, zone))
        assertTrue(AutonomousAccessibilityState.isSameDate("2026-11-03", instant, zone))
        assertFalse(AutonomousAccessibilityState.isSameDate("2026-11-02", instant, zone))
        assertFalse(AutonomousAccessibilityState.isSameDate("", instant, zone))
    }

    @Test
    fun `a grant round trips as structured data`() {
        val grant =
            AutonomousAccessibilityGrant(
                sessionId = "s1",
                packageName = "com.xingin.xhs",
                taskFingerprint = "task:abc",
                confirmedAtMillis = 123L,
            )
        assertEquals(grant, Json.decodeFromString(AutonomousAccessibilityGrant.serializer(), Json.encodeToString(
            AutonomousAccessibilityGrant.serializer(),
            grant,
        )))
    }
}
