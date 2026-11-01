package io.zer0.muse.automation.vdisplay

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.zer0.muse.automation.core.AutomationManager
import io.zer0.muse.automation.core.ScreenInfo
import io.zer0.muse.automation.core.UiNode
import io.zer0.muse.automation.executors.ShellExecutor
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 幂等性回归：**虚拟屏语义点击一旦把点击命令发出去，失败也不得重放**。
 *
 * 前台路径已有 [io.zer0.muse.automation.core.AutomationManagerSemanticTapTest] 钉住同一契约，
 * 但虚拟屏是另一套实现（走 `exec(shellCommand)`），此前没有对应保护。
 *
 * 为什么重要：一次点击可能已经生效（比如点了「付款」），只是命令退出码非 0；
 * 如果这时重试，就是**第二次真实点击**。所以只能报 outcomeUnknown，交给上层决定是否 retry_unknown。
 */
class VirtualDisplaySemanticActionsReplayTest {

    private fun screen() = ScreenInfo(
        nodes = listOf(
            UiNode(
                text = "Settings",
                boundsLeft = 0,
                boundsTop = 0,
                boundsRight = 10,
                boundsBottom = 10,
                isClickable = true,
            ),
        ),
        screenWidth = 400,
        screenHeight = 800,
        source = "test",
    )

    @Test
    fun `failed virtual tap is not replayed and reports unknown outcome`() = runTest {
        val manager = mockk<AutomationManager>(relaxed = true)
        val displayManager = mockk<VirtualDisplayServerManager>(relaxed = true)
        coEvery { manager.readScreenOnDisplay(any()) } returns screen()
        // 点击命令已执行但退出码非 0 —— 可能已经点到了，绝不能重试
        coEvery { displayManager.exec(any()) } returns ShellExecutor.ExecDetail(1, "boom")

        val result = VirtualDisplaySemanticActions.tapText(
            manager = manager,
            displayManager = displayManager,
            displayId = 1,
            text = "Settings",
            maxSwipes = 2,
        )

        assertTrue("应报结果未知，实际: $result", result.outcomeUnknown)
        assertEquals("不得报成功", false, result.success)
        coVerify(exactly = 1) { displayManager.exec(any()) }
        // 只读一次屏（找控件）；点击失败后立刻返回，不再读第二次验证界面。
        coVerify(exactly = 1) { manager.readScreenOnDisplay(any()) }
    }

    @Test
    fun `successful virtual tap without verify text runs exactly once`() = runTest {
        val manager = mockk<AutomationManager>(relaxed = true)
        val displayManager = mockk<VirtualDisplayServerManager>(relaxed = true)
        coEvery { manager.readScreenOnDisplay(any()) } returns screen()
        coEvery { displayManager.exec(any()) } returns ShellExecutor.ExecDetail(0, "ok")

        val result = VirtualDisplaySemanticActions.tapText(
            manager = manager,
            displayManager = displayManager,
            displayId = 1,
            text = "Settings",
        )

        assertTrue("应成功，实际: $result", result.success)
        coVerify(exactly = 1) { displayManager.exec(any()) }
    }
}
