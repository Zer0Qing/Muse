package io.zer0.muse.automation.agent

import io.mockk.mockk
import io.zer0.muse.automation.core.AutomationManager
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * AUTO 执行面的选择规则（对比清单 P1-03）。
 *
 * 这条规则决定"用户正在看的任务会不会被抽到后台"：
 * 目标应用已经在前台时，必须原地操作 —— 既不新建虚拟屏，也不重启应用。
 * 之前的实现一律先建虚拟屏，用户正在看的页面会被换成后台的一份副本。
 */
class UiAgentRunnerAutoSurfaceTest {

    private val runner = UiAgentRunner(
        manager = mockk<AutomationManager>(relaxed = true),
        vision = mockk(relaxed = true),
    )

    @Test
    fun `no target package means operate on whatever is in front of the user`() {
        assertEquals(
            UiAgentRunner.AutoSurfaceChoice.CURRENT_FOREGROUND,
            runner.chooseAutoSurface(targetPackage = null, currentForeground = "com.example.chat"),
        )
    }

    @Test
    fun `target already in the foreground is operated in place`() {
        assertEquals(
            "用户正看着目标应用时不能把任务抽到后台",
            UiAgentRunner.AutoSurfaceChoice.OPERATE_IN_PLACE,
            runner.chooseAutoSurface("com.example.shop", "com.example.shop"),
        )
    }

    @Test
    fun `target in the background gets a dedicated display`() {
        assertEquals(
            UiAgentRunner.AutoSurfaceChoice.DEDICATED_DISPLAY,
            runner.chooseAutoSurface("com.example.shop", "com.example.chat"),
        )
    }

    @Test
    fun `unknown foreground falls back to a dedicated display`() {
        // 读不到前台包名时不能假设"就是它"：宁可用虚拟屏，也不要赌一把抽走用户的界面
        assertEquals(
            UiAgentRunner.AutoSurfaceChoice.DEDICATED_DISPLAY,
            runner.chooseAutoSurface("com.example.shop", null),
        )
    }

    @Test
    fun `package comparison is exact, not prefix based`() {
        assertEquals(
            "com.example.shop2 不是 com.example.shop",
            UiAgentRunner.AutoSurfaceChoice.DEDICATED_DISPLAY,
            runner.chooseAutoSurface("com.example.shop", "com.example.shop2"),
        )
    }
}
