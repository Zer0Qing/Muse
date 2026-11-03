package io.zer0.muse.automation.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutonomousAccessibilityPolicyTest {
    private val window = AutonomousAccessibilityWindow(
        enabled = true,
        startMinuteOfDay = 14 * 60,
        endMinuteOfDay = 17 * 60,
        allowedPackages = setOf("com.xingin.xhs"),
        allowedActions = setOf(AutomationAction.SCROLL, AutomationAction.CLICK, AutomationAction.READ_SCREEN),
        maxRunsPerDay = 6,
    )

    @Test
    fun `inside the configured window an allowed app action is permitted`() {
        val decision = AutonomousAccessibilityPolicy.evaluate(
            window = window,
            request = AutonomousAccessibilityRequest(
                minuteOfDay = 15 * 60,
                packageName = "com.xingin.xhs",
                action = AutomationAction.SCROLL,
                runCountToday = 2,
                userConfirmed = true,
            ),
        )
        assertTrue(decision.allowed)
    }

    @Test
    fun `outside the window is denied even for an allowed app`() {
        val decision = AutonomousAccessibilityPolicy.evaluate(
            window = window,
            request = AutonomousAccessibilityRequest(
                minuteOfDay = 18 * 60,
                packageName = "com.xingin.xhs",
                action = AutomationAction.SCROLL,
                runCountToday = 0,
                userConfirmed = true,
            ),
        )
        assertFalse(decision.allowed)
    }

    @Test
    fun `package and action allowlists are both enforced`() {
        assertFalse(
            AutonomousAccessibilityPolicy.evaluate(
                window = window,
                request = AutonomousAccessibilityRequest(
                    minuteOfDay = 15 * 60,
                    packageName = "com.example.other",
                    action = AutomationAction.SCROLL,
                    runCountToday = 0,
                    userConfirmed = true,
                ),
            ).allowed,
        )
        assertFalse(
            AutonomousAccessibilityPolicy.evaluate(
                window = window,
                request = AutonomousAccessibilityRequest(
                    minuteOfDay = 15 * 60,
                    packageName = "com.xingin.xhs",
                    action = AutomationAction.INPUT,
                    runCountToday = 0,
                    userConfirmed = true,
                ),
            ).allowed,
        )
    }

    @Test
    fun `daily cap and explicit user confirmation are required`() {
        assertFalse(
            AutonomousAccessibilityPolicy.evaluate(
                window = window,
                request = AutonomousAccessibilityRequest(
                    minuteOfDay = 15 * 60,
                    packageName = "com.xingin.xhs",
                    action = AutomationAction.SCROLL,
                    runCountToday = 6,
                    userConfirmed = true,
                ),
            ).allowed,
        )
        assertFalse(
            AutonomousAccessibilityPolicy.evaluate(
                window = window,
                request = AutonomousAccessibilityRequest(
                    minuteOfDay = 15 * 60,
                    packageName = "com.xingin.xhs",
                    action = AutomationAction.SCROLL,
                    runCountToday = 0,
                    userConfirmed = false,
                ),
            ).allowed,
        )
    }

    @Test
    fun `overnight window is supported`() {
        val overnight = window.copy(startMinuteOfDay = 22 * 60, endMinuteOfDay = 2 * 60)
        assertTrue(
            AutonomousAccessibilityPolicy.evaluate(
                window = overnight,
                request = AutonomousAccessibilityRequest(
                    minuteOfDay = 23 * 60,
                    packageName = "com.xingin.xhs",
                    action = AutomationAction.READ_SCREEN,
                    runCountToday = 0,
                    userConfirmed = true,
                ),
            ).allowed,
        )
        assertTrue(
            AutonomousAccessibilityPolicy.evaluate(
                window = overnight,
                request = AutonomousAccessibilityRequest(
                    minuteOfDay = 60,
                    packageName = "com.xingin.xhs",
                    action = AutomationAction.READ_SCREEN,
                    runCountToday = 0,
                    userConfirmed = true,
                ),
            ).allowed,
        )
        assertFalse(
            AutonomousAccessibilityPolicy.evaluate(
                window = overnight,
                request = AutonomousAccessibilityRequest(
                    minuteOfDay = 12 * 60,
                    packageName = "com.xingin.xhs",
                    action = AutomationAction.READ_SCREEN,
                    runCountToday = 0,
                    userConfirmed = true,
                ),
            ).allowed,
        )
    }
}
