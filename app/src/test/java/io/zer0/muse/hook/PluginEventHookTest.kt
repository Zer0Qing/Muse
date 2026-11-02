package io.zer0.muse.hook

import io.mockk.mockk
import io.mockk.verify
import io.zer0.muse.data.plugin.PluginActivationBridge
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具调用事件转给插件（插件计划 P3b）。
 *
 * 只投工具名、不投参数：参数里常有用户内容，需要它的插件该走有独立授权的通道。
 */
class PluginEventHookTest {

    private val bridge: PluginActivationBridge = mockk(relaxed = true)
    private val hook = PluginEventHook(bridge)

    @Test
    fun `a tool call is forwarded to the plugin bridge`() = runBlocking {
        val action = hook.onToolCallRequested("screen_read", mapOf("x" to 1))

        assertEquals("事件 hook 不能改变调用本身", ToolCallAction.Allow, action)
        verify(exactly = 1) { bridge.dispatchToolCall("screen_read") }
    }

    @Test
    fun `the hook runs late so blocked calls are not announced`() {
        assertTrue("优先级要低，排在拦截类 hook 之后: ${hook.priority}", hook.priority <= 10)
    }

    @Test
    fun `the hook has a stable id`() {
        assertEquals("plugin_event_hook", hook.id)
        assertTrue(hook.enabled)
    }
}
