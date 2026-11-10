package io.zer0.muse.ui.chat

import io.zer0.muse.automation.core.AutomationAction
import io.zer0.muse.automation.core.AutonomousAccessibilityGrant
import io.zer0.muse.automation.core.AutonomousAccessibilityRuntimeSnapshot
import io.zer0.muse.automation.core.AutonomousAccessibilityTaskScope
import io.zer0.muse.automation.core.AutonomousAccessibilityWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class AutonomousAccessibilityGateTest {
    private val task = "打开 com.xingin.xhs 浏览小红书"
    private val session = "s1"
    private val packageName = "com.xingin.xhs"
    private val nowMinute = 15 * 60
    private val today = "2026-11-03"
    private val grant =
        AutonomousAccessibilityGrant(
            sessionId = session,
            packageName = packageName,
            taskFingerprint = AutonomousAccessibilityTaskScope.fingerprint(task),
            confirmedAtMillis = 1,
        )
    private val snapshot =
        AutonomousAccessibilityRuntimeSnapshot(
            window = AutonomousAccessibilityWindow(
                enabled = true,
                startMinuteOfDay = 14 * 60,
                endMinuteOfDay = 17 * 60,
                allowedPackages = setOf(packageName),
                allowedActions = setOf(AutomationAction.READ_SCREEN, AutomationAction.SCROLL),
                maxRunsPerDay = 3,
            ),
            grant = grant,
            currentPackage = packageName,
            minuteOfDay = nowMinute,
            dateKey = today,
            runCountToday = 0,
            latestUserMessage = task,
        )

    @Test
    fun `complete task grant and runtime state upgrade pending screen tool`() = kotlinx.coroutines.runBlocking {
        val result =
            AutonomousAccessibilityGate.reserve(
                sessionId = session,
                toolName = "screen_swipe",
                pending = io.zer0.muse.tools.ToolApprovalState.Pending,
                snapshot = snapshot,
            )

        assertEquals(io.zer0.muse.tools.ToolApprovalState.Auto, result.approval)
    }

    @Test
    fun `wrong session package or message fingerprint leaves the card pending`() = kotlinx.coroutines.runBlocking {
        val wrongSession =
            AutonomousAccessibilityGate.reserve(
                "other",
                "screen_swipe",
                io.zer0.muse.tools.ToolApprovalState.Pending,
                snapshot,
            )
        val wrongPackage =
            AutonomousAccessibilityGate.reserve(
                session,
                "screen_swipe",
                io.zer0.muse.tools.ToolApprovalState.Pending,
                snapshot.copy(currentPackage = "com.example.other"),
            )
        val wrongTask =
            AutonomousAccessibilityGate.reserve(
                session,
                "screen_swipe",
                io.zer0.muse.tools.ToolApprovalState.Pending,
                snapshot.copy(latestUserMessage = "删除 com.xingin.xhs 小红书"),
            )

        assertSame(io.zer0.muse.tools.ToolApprovalState.Pending, wrongSession.approval)
        assertSame(io.zer0.muse.tools.ToolApprovalState.Pending, wrongPackage.approval)
        assertSame(io.zer0.muse.tools.ToolApprovalState.Pending, wrongTask.approval)
    }

    @Test
    fun `missing grant cannot widen a plan setting into silence`() = kotlinx.coroutines.runBlocking {
        val result =
            AutonomousAccessibilityGate.reserve(
                session,
                "screen_swipe",
                io.zer0.muse.tools.ToolApprovalState.Pending,
                snapshot.copy(grant = null),
            )

        assertSame(io.zer0.muse.tools.ToolApprovalState.Pending, result.approval)
    }

    @Test
    fun `existing deny and non-screen tools are never widened`() = kotlinx.coroutines.runBlocking {
        val deny = io.zer0.muse.tools.ToolApprovalState.Denied("user disabled")
        val denied =
            AutonomousAccessibilityGate.reserve(session, "screen_swipe", deny, snapshot)
        val nonScreen =
            AutonomousAccessibilityGate.reserve(
                session,
                "web_search",
                io.zer0.muse.tools.ToolApprovalState.Pending,
                snapshot,
            )

        assertSame(deny, denied.approval)
        assertSame(io.zer0.muse.tools.ToolApprovalState.Pending, nonScreen.approval)
    }
}
