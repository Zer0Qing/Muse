package io.zer0.muse.automation.ui

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
    fun `input cannot be selected as an autonomous action`() {
        val next =
            AutonomousAccessibilityDraftState().toggleAction(
                io.zer0.muse.automation.core.AutomationAction.INPUT,
            )

        assertFalse(io.zer0.muse.automation.core.AutomationAction.INPUT in next.allowedActions)
    }

    @Test
    fun `package editor uses one trimmed package per nonempty line`() {
        assertEquals(
            setOf("com.xingin.xhs", "com.example.app"),
            parseAutonomousPackages(" com.xingin.xhs \n\ncom.example.app\n"),
        )
    }
}
