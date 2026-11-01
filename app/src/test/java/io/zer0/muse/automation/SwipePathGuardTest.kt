package io.zer0.muse.automation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.zer0.muse.automation.core.AutomationManager
import io.zer0.muse.automation.tools.AutomationTools
import io.zer0.muse.tools.ToolRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `screen_swipe_path` 的降级保护。
 *
 * 旧行为：只有 Shell/Root 时，多段路径被**悄悄降级成首点→末点直线** ——
 * 图案解锁会变成一条直线，是「另一个手势」，比失败危险。现在必须明确拒绝。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SwipePathGuardTest {

    private fun registry(manager: AutomationManager): ToolRegistry {
        val registry = ToolRegistry(ApplicationProvider.getApplicationContext<Context>())
        AutomationTools(manager, automationEnabled = { true }).register(registry)
        return registry
    }

    @Test
    fun `multi point path is refused when only shell or root is available`() = runBlocking {
        val manager = mockk<AutomationManager>(relaxed = true)
        coEvery { manager.supportsMultiPointGesture() } returns false
        coEvery { manager.swipePath(any(), any()) } returns true

        val result = registry(manager).executeFromJson(
            "screen_swipe_path",
            """{"points":"10,10;50,200;90,400"}""",
        )

        assertTrue("应明确拒绝，实际: $result", result.contains("多段路径需要无障碍"))
        assertTrue("应给替代方案，实际: $result", result.contains("screen_swipe") || result.contains("无障碍"))
        coVerify(exactly = 0) { manager.swipePath(any(), any()) }
    }

    @Test
    fun `two point path still works on shell`() = runBlocking {
        val manager = mockk<AutomationManager>(relaxed = true)
        coEvery { manager.supportsMultiPointGesture() } returns false
        coEvery { manager.swipePath(any(), any()) } returns true

        val result = registry(manager).executeFromJson(
            "screen_swipe_path",
            """{"points":"10,10;90,400"}""",
        )

        assertTrue("两点路径应放行，实际: $result", result.contains("已执行"))
        coVerify(exactly = 1) { manager.swipePath(any(), any()) }
    }

    @Test
    fun `absurd point count is rejected before execution`() = runBlocking {
        val manager = mockk<AutomationManager>(relaxed = true)
        coEvery { manager.supportsMultiPointGesture() } returns true
        val points = (1..20).joinToString(";") { "$it,$it" }

        val result = registry(manager).executeFromJson(
            "screen_swipe_path",
            """{"points":"$points"}""",
        )

        assertTrue("应拒绝过多路径点，实际: $result", result.contains("最多"))
        coVerify(exactly = 0) { manager.swipePath(any(), any()) }
    }
}
