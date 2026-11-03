package io.zer0.muse.automation.core

import io.zer0.muse.tools.ToolApprovalState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class AutonomousAccessibilityApprovalTest {
    private val window = AutonomousAccessibilityWindow(
        enabled = true,
        startMinuteOfDay = 14 * 60,
        endMinuteOfDay = 17 * 60,
        allowedPackages = setOf("com.xingin.xhs"),
        allowedActions = setOf(AutomationAction.READ_SCREEN, AutomationAction.SCROLL),
        maxRunsPerDay = 3,
    )

    @Test
    fun `allowed screen scroll inside the window becomes automatic`() {
        val result =
            AutonomousAccessibilityApproval.evaluate(
                window = window,
                toolName = "screen_swipe",
                currentPackage = "com.xingin.xhs",
                minuteOfDay = 15 * 60,
                runCountToday = 0,
                userConfirmed = true,
                pending = ToolApprovalState.Pending,
            )

        assertEquals(ToolApprovalState.Auto, result.approval)
    }

    @Test
    fun `unknown or disallowed package stays pending`() {
        val unknown =
            AutonomousAccessibilityApproval.evaluate(
                window = window,
                toolName = "screen_swipe",
                currentPackage = null,
                minuteOfDay = 15 * 60,
                runCountToday = 0,
                userConfirmed = true,
                pending = ToolApprovalState.Pending,
            )
        val disallowed =
            AutonomousAccessibilityApproval.evaluate(
                window = window,
                toolName = "screen_swipe",
                currentPackage = "com.example.other",
                minuteOfDay = 15 * 60,
                runCountToday = 0,
                userConfirmed = true,
                pending = ToolApprovalState.Pending,
            )

        assertSame(ToolApprovalState.Pending, unknown.approval)
        assertSame(ToolApprovalState.Pending, disallowed.approval)
    }

    @Test
    fun `input remains pending even when configured in the window`() {
        val result =
            AutonomousAccessibilityApproval.evaluate(
                window = window.copy(allowedActions = window.allowedActions + AutomationAction.INPUT),
                toolName = "screen_input",
                currentPackage = "com.xingin.xhs",
                minuteOfDay = 15 * 60,
                runCountToday = 0,
                userConfirmed = true,
                pending = ToolApprovalState.Pending,
            )

        assertSame(ToolApprovalState.Pending, result.approval)
    }

    @Test
    fun `existing deny wins and non-screen tools are untouched`() {
        val denied = ToolApprovalState.Denied("explicitly disabled")
        val result =
            AutonomousAccessibilityApproval.evaluate(
                window = window,
                toolName = "screen_read",
                currentPackage = "com.xingin.xhs",
                minuteOfDay = 15 * 60,
                runCountToday = 0,
                userConfirmed = true,
                pending = denied,
            )
        val nonScreen =
            AutonomousAccessibilityApproval.evaluate(
                window = window,
                toolName = "web_search",
                currentPackage = "com.xingin.xhs",
                minuteOfDay = 15 * 60,
                runCountToday = 0,
                userConfirmed = true,
                pending = ToolApprovalState.Pending,
            )

        assertSame(denied, result.approval)
        assertSame(ToolApprovalState.Pending, nonScreen.approval)
    }
}
