package io.zer0.muse.automation

import io.mockk.mockk
import io.zer0.muse.automation.agent.UiAgentProtocol
import io.zer0.muse.automation.agent.UiAgentRunner
import io.zer0.muse.automation.core.AutomationAction
import io.zer0.muse.automation.core.AutomationManager
import io.zer0.muse.automation.core.AutonomousAccessibilityGrant
import io.zer0.muse.automation.core.AutonomousAccessibilityRuntimeSnapshot
import io.zer0.muse.automation.core.AutonomousAccessibilityTaskScope
import io.zer0.muse.automation.core.AutonomousAccessibilityWindow
import io.zer0.muse.automation.core.ScreenInfo
import io.zer0.muse.automation.core.UiNode
import io.zer0.muse.automation.vdisplay.VirtualDisplayHandoffRegistry
import io.zer0.muse.automation.vdisplay.VirtualDisplayInputCommand
import io.zer0.muse.tools.ToolApprovalState
import io.zer0.muse.tools.ToolChannelPreflight
import io.zer0.muse.tools.ToolRequirements
import io.zer0.muse.ui.chat.AutonomousAccessibilityGate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UiAutomationRedesignTest {

    @Before
    fun setUp() = VirtualDisplayHandoffRegistry.resetForTests()

    @After
    fun tearDown() = VirtualDisplayHandoffRegistry.resetForTests()

    @Test
    fun `master switch blocks ui and shell automation tools`() {
        val blockedTools = listOf("ui_agent", "ui_click", "screen_tap", "device_shell", "input_inject", "am_start")
        for (tool in blockedTools) {
            val result = ToolChannelPreflight.checkPreflightable(
                toolName = tool,
                availableChannels = setOf(
                    ToolRequirements.Channel.ACCESSIBILITY,
                    ToolRequirements.Channel.SHELL,
                    ToolRequirements.Channel.ROOT,
                ),
                automationEnabled = false,
            )
            assertTrue("$tool must be blocked when master switch is off", result is ToolChannelPreflight.Result.Blocked)
        }
    }

    @Test
    fun `tool requirements map root shell_only and app_settings accurately`() {
        val screenReq = ToolRequirements.of("screen_tap")
        assertTrue(ToolRequirements.Channel.ROOT in screenReq.anyOf)
        assertTrue(ToolRequirements.isUsable("screen_tap", setOf(ToolRequirements.Channel.ROOT)))

        for (tool in listOf("virtual_screen", "virtual_screen_input", "input_inject")) {
            val req = ToolRequirements.of(tool)
            assertFalse(
                "$tool requires shell/root, not a11y",
                ToolRequirements.isUsable(tool, setOf(ToolRequirements.Channel.ACCESSIBILITY)),
            )
            assertTrue("$tool usable with shell", ToolRequirements.isUsable(tool, setOf(ToolRequirements.Channel.SHELL)))
            assertFalse(req.isUnconditional)
        }

        assertTrue(ToolRequirements.of("app_settings").isUnconditional)
        assertTrue(ToolRequirements.isUsable("app_settings", emptySet()))
    }

    @Test
    fun `autonomous accessibility gate respects INPUT permission toggle`() = runBlocking {
        val msg = "打开 com.example.app 输入账号"
        val grant = AutonomousAccessibilityGrant(
            sessionId = "s1",
            packageName = "com.example.app",
            taskFingerprint = AutonomousAccessibilityTaskScope.fingerprint(msg),
            confirmedAtMillis = 100L,
        )
        val snapshotWithInput = AutonomousAccessibilityRuntimeSnapshot(
            window = AutonomousAccessibilityWindow(
                enabled = true,
                startMinuteOfDay = 0,
                endMinuteOfDay = 23 * 60 + 59,
                allowedPackages = setOf("com.example.app"),
                allowedActions = setOf(AutomationAction.READ_SCREEN, AutomationAction.INPUT),
                maxRunsPerDay = 5,
            ),
            grant = grant,
            currentPackage = "com.example.app",
            minuteOfDay = 12 * 60,
            dateKey = "2026-11-26",
            runCountToday = 0,
            latestUserMessage = msg,
        )
        val allowed = AutonomousAccessibilityGate.reserve(
            sessionId = "s1",
            toolName = "screen_input",
            pending = ToolApprovalState.Pending,
            snapshot = snapshotWithInput,
        )
        assertSame(ToolApprovalState.Auto, allowed.approval)

        val snapshotWithoutInput = snapshotWithInput.copy(
            window = snapshotWithInput.window.copy(allowedActions = setOf(AutomationAction.READ_SCREEN, AutomationAction.CLICK)),
        )
        val blocked = AutonomousAccessibilityGate.reserve(
            sessionId = "s1",
            toolName = "screen_input",
            pending = ToolApprovalState.Pending,
            snapshot = snapshotWithoutInput,
        )
        assertSame(ToolApprovalState.Pending, blocked.approval)
    }

    @Test
    fun `virtual display text command rejects unsafe shell chars and accepts clean text`() {
        assertNotNull(VirtualDisplayInputCommand.textRejectionReason("hello;ls"))
        assertNotNull(VirtualDisplayInputCommand.textRejectionReason("it's"))
        assertNull(VirtualDisplayInputCommand.textRejectionReason("hello_world 123"))
        assertNull(VirtualDisplayInputCommand.textRejectionReason("你好世界"))
    }

    @Test
    fun `virtual display handoff flow stays synchronized with mutations`() {
        assertTrue(VirtualDisplayHandoffRegistry.handoffsFlow.value.isEmpty())

        VirtualDisplayHandoffRegistry.offer(displayId = 9, packageName = "com.example.demo", now = 1_000L, ttlMs = 5_000L)
        assertEquals(listOf(9), VirtualDisplayHandoffRegistry.handoffsFlow.value.map { it.displayId })

        VirtualDisplayHandoffRegistry.take(displayId = 9, now = 2_000L)
        assertTrue(VirtualDisplayHandoffRegistry.handoffsFlow.value.isEmpty())

        VirtualDisplayHandoffRegistry.offer(displayId = 10, packageName = "com.example.demo", now = 2_000L, ttlMs = 1_000L)
        VirtualDisplayHandoffRegistry.pruneExpired(now = 4_000L)
        assertTrue(VirtualDisplayHandoffRegistry.handoffsFlow.value.isEmpty())
    }

    @Test
    fun `scaleActionForScreen maps downscaled image coords and preserves out of bounds`() {
        val runner = UiAgentRunner(mockk<AutomationManager>(relaxed = true), mockk(relaxed = true))
        val scaled = runner.scaleActionForScreen(
            action = UiAgentProtocol.Action.Tap(270, 600),
            imgW = 540,
            imgH = 1200,
            screenW = 1080,
            screenH = 2400,
        ) as UiAgentProtocol.Action.Tap
        assertEquals(540, scaled.x)
        assertEquals(1200, scaled.y)

        val oob = runner.scaleActionForScreen(
            action = UiAgentProtocol.Action.Tap(600, 600),
            imgW = 540,
            imgH = 1200,
            screenW = 1080,
            screenH = 2400,
        ) as UiAgentProtocol.Action.Tap
        assertTrue("Out-of-bounds VLM coordinates must remain >= screenW so boundsIssue catches them", oob.x >= 1080)
    }

    @Test
    fun `screen info includes view id suffix and evenly samples viewport bands`() {
        val idNode = UiNode(
            text = "Submit",
            viewIdResourceName = "com.example:id/submit_btn",
            boundsLeft = 10,
            boundsTop = 20,
            boundsRight = 210,
            boundsBottom = 80,
            isClickable = true,
        )
        assertTrue(idNode.toShortString().contains("#submit_btn"))

        val screen = ScreenInfo(
            screenWidth = 1080,
            screenHeight = 2400,
            nodes = (0 until 60).map { idx ->
                val y = idx * 40
                UiNode(
                    text = "Row$idx",
                    boundsLeft = 0,
                    boundsTop = y,
                    boundsRight = 500,
                    boundsBottom = y + 35,
                    isClickable = true,
                )
            },
        )
        val summary = screen.toSummary()
        assertTrue("Summary must retain early top row", summary.contains("Row0"))
        assertTrue("Summary must retain bottom viewport row rather than head-truncating", summary.contains("Row59"))
    }
}
