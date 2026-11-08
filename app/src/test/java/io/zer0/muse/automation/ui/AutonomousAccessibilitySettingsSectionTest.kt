package io.zer0.muse.automation.ui

import io.zer0.muse.automation.core.AutomationAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutonomousAccessibilitySettingsSectionTest {
    @Test
    fun `autonomous gate defaults to the xiaohongshu plan but remains off`() {
        val initial = AutonomousAccessibilityDraftState()

        assertFalse(initial.enabled)
        assertEquals(setOf("com.xingin.xhs"), initial.allowedPackages)
        assertEquals(14 * 60, initial.startMinute)
        assertEquals(17 * 60, initial.endMinute)
        assertEquals(6, initial.maxRunsPerDay)
    }

    @Test
    fun `默认四个自主动作全开`() {
        val initial = AutonomousAccessibilityDraftState()
        assertEquals(AutomationAction.entries.toSet(), initial.allowedActions)
    }

    @Test
    fun `输入可以单独关掉而不是被硬挡且可再开`() {
        val off = AutonomousAccessibilityDraftState().toggleAction(AutomationAction.INPUT)
        assertFalse(AutomationAction.INPUT in off.allowedActions)
        val on = off.toggleAction(AutomationAction.INPUT)
        assertTrue(AutomationAction.INPUT in on.allowedActions)
    }

    @Test
    fun `输入动作能存进窗口并原样读回`() {
        val window = AutonomousAccessibilityDraftState().toWindow()
        assertTrue("全开默认应含输入，不再被 toWindow 剥掉", AutomationAction.INPUT in window.allowedActions)
        val back = AutonomousAccessibilityDraftState.fromWindow(window)
        assertTrue("读回也不剥输入", AutomationAction.INPUT in back.allowedActions)
    }

    @Test
    fun `package editor uses one trimmed package per nonempty line`() {
        assertEquals(
            setOf("com.xingin.xhs", "com.example.app"),
            parseAutonomousPackages(" com.xingin.xhs \n\ncom.example.app\n"),
        )
    }
}
