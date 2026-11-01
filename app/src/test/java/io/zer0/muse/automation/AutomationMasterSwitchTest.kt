package io.zer0.muse.automation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import io.zer0.muse.automation.core.AutomationManager
import io.zer0.muse.automation.tools.AutomationTools
import io.zer0.muse.tools.ToolRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 自动化总闸。
 *
 * 用户需要一个「一次停掉所有动手能力」的刹车：关掉之后 screen_* / ui_agent /
 * automation_workflow 必须**快速失败**并说清去哪儿打开，而不是照样去点屏幕。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AutomationMasterSwitchTest {

    private fun registry(enabled: Boolean): ToolRegistry {
        val registry = ToolRegistry(ApplicationProvider.getApplicationContext<Context>())
        val manager = mockk<AutomationManager>(relaxed = true)
        AutomationTools(manager, automationEnabled = { enabled }).register(registry)
        return registry
    }

    @Test
    fun `screen action refuses to run when the master switch is off`() = runBlocking {
        val result = registry(enabled = false).executeFromJson("screen_tap", """{"x":10,"y":20}""")
        assertTrue("应提示总闸已关闭，实际: $result", result.contains("自动化总闸已关闭"))
        assertTrue("应告诉用户去哪儿打开", result.contains("设置"))
    }

    @Test
    fun `perception tool also refuses when the master switch is off`() = runBlocking {
        val result = registry(enabled = false).executeFromJson("screen_read", "{}")
        assertTrue("读屏也要被拦住，实际: $result", result.contains("自动化总闸已关闭"))
    }

    @Test
    fun `tools are not blocked when the master switch is on`() = runBlocking {
        val result = registry(enabled = true).executeFromJson("screen_read", "{}")
        assertFalse("总闸开着时不应出现关闭提示，实际: $result", result.contains("自动化总闸已关闭"))
    }

    @Test
    fun `permission status stays callable when the master switch is off`() = runBlocking {
        // 总闸关着时，用户更需要知道「缺哪层、该怎么开」—— 状态查询必须始终可用。
        val result = registry(enabled = false).executeFromJson("screen_permission_status", "{}")
        assertFalse("状态查询不该被总闸拦，实际: $result", result.contains("自动化总闸已关闭"))
        assertTrue("应返回权限状态正文", result.contains("UI 自动化权限状态"))
    }

    @Test
    fun `open settings stays callable when the master switch is off`() = runBlocking {
        // 这是「修复手段」：模型要能直接把用户送到设置页，否则就是死路。
        val result = registry(enabled = false).executeFromJson("screen_open_settings", """{"target":"accessibility"}""")
        assertFalse("打开设置页不该被总闸拦，实际: $result", result.contains("自动化总闸已关闭"))
    }

    @Test
    fun `off message tells the model which tool to call next`() = runBlocking {
        val result = registry(enabled = false).executeFromJson("screen_tap", """{"x":10,"y":20}""")
        assertTrue("应指名 screen_open_settings", result.contains("screen_open_settings"))
        assertTrue("应指出该工具不受总闸限制", result.contains("不受总闸"))
    }

    @Test
    fun `tap descriptions warn that each call is a real tap`() {
        // 幂等性提示要写进描述：模型看不到代码里的防重放逻辑，
        // 它唯一能读到的是描述。不写清楚，它就会在「结果不确定」时直接重试 → 第二次真实点击。
        val tools = registry(enabled = true).listTools().associateBy { it.name }
        for (name in listOf("screen_tap", "screen_tap_text")) {
            val description = tools.getValue(name).description
            assertTrue("$name 描述应说明每次调用都是真实点击", description.contains("每次调用都是一次真实点击"))
            assertTrue("$name 描述应提示先 screen_read 再决定是否重试", description.contains("screen_read"))
        }
    }
}
