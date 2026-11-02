package io.zer0.muse.automation.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 动作前后的画面证据（对比清单 P1-06「滚动证据 / 动作后观察」）。
 *
 * 要回答的问题很朴素：刚才这一下**到底有没有效果**。
 * 三态而不是两态：读不到前后画面时必须说"无法确认"，
 * 不能因为"没看到变化"就断言"没变化"。
 */
class ScreenChangeTest {

    @Before
    fun setUp() = AutomationObservationRegistry.resetForTests()

    @After
    fun tearDown() = AutomationObservationRegistry.resetForTests()

    @Test
    fun `identical fingerprints mean nothing changed`() {
        assertEquals(ScreenChange.Evidence.UNCHANGED, ScreenChange.compare("abc", "abc"))
    }

    @Test
    fun `different fingerprints mean the screen changed`() {
        assertEquals(ScreenChange.Evidence.CHANGED, ScreenChange.compare("abc", "def"))
    }

    @Test
    fun `a missing read is unknown rather than unchanged`() {
        assertEquals(ScreenChange.Evidence.UNKNOWN, ScreenChange.compare(null, "def"))
        assertEquals(ScreenChange.Evidence.UNKNOWN, ScreenChange.compare("abc", null))
        assertEquals(ScreenChange.Evidence.UNKNOWN, ScreenChange.compare("", "def"))
        assertEquals(ScreenChange.Evidence.UNKNOWN, ScreenChange.compare("abc", "   "))
    }

    @Test
    fun `an unchanged scroll tells the model to stop repeating it`() {
        val message = ScreenChange.scrollResult(ScreenChange.Evidence.UNCHANGED)

        assertTrue("要提醒别重复滑动: $message", message.contains("不要重复滑动"))
        assertTrue("要给出下一步: $message", message.contains("screen_read"))
    }

    @Test
    fun `an unknown scroll says it could not be confirmed`() {
        val message = ScreenChange.scrollResult(ScreenChange.Evidence.UNKNOWN)

        assertTrue("必须承认不知道: $message", message.contains("无法确认"))
        assertTrue("不能声称已移动: $message", !message.contains("内容已移动"))
    }

    @Test
    fun `after action wording covers all three states`() {
        assertTrue(ScreenChange.afterActionResult(ScreenChange.Evidence.CHANGED).contains("已变化"))
        assertTrue(ScreenChange.afterActionResult(ScreenChange.Evidence.UNCHANGED).contains("未变化"))
        assertTrue(ScreenChange.afterActionResult(ScreenChange.Evidence.UNKNOWN).contains("无法确认"))
    }

    @Test
    fun `the fingerprint follows visible text changes`() {
        val before = ScreenChange.fingerprintOf(
            ScreenInfo(packageName = "com.shop", activityName = "Detail", nodes = listOf(node("购物车"))),
        )
        val after = ScreenChange.fingerprintOf(
            ScreenInfo(packageName = "com.shop", activityName = "Detail", nodes = listOf(node("已下单"))),
        )

        assertEquals(
            ScreenChange.Evidence.CHANGED,
            ScreenChange.compare(before, after),
        )
    }

    @Test
    fun `an empty screen still yields a comparable fingerprint`() {
        val fingerprint = ScreenChange.fingerprintOf(ScreenInfo())

        assertTrue(fingerprint.isNotEmpty())
        assertEquals(ScreenChange.Evidence.UNCHANGED, ScreenChange.compare(fingerprint, fingerprint))
    }

    @Test
    fun `the registry can hand back the fingerprint for a still valid id`() {
        val observation = AutomationObservationRegistry.record("main", "fp-1", now = 1_000)

        assertEquals("fp-1", AutomationObservationRegistry.fingerprintOf("main", observation.id, now = 1_100))
        assertNull(
            "过期后不能再拿来比对",
            AutomationObservationRegistry.fingerprintOf("main", observation.id, now = 999_999),
        )
        assertNull(AutomationObservationRegistry.fingerprintOf("main", "obs-unknown", now = 1_100))
    }

    private fun node(text: String) = UiNode(text = text, isClickable = true, isEditable = false)
}
