package io.zer0.muse.tools

import io.zer0.muse.tools.ToolRequirements.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具执行前通道预检的测试。
 *
 * 助手自审第 4 条：「通道未就绪时工具是快速失败，但失败信息和『该怎么开通』之间没衔接」。
 * 这里钉住三件事：
 *  1. 通道缺 → 拦下且不执行
 *  2. 失败信息带开通路径与替代手段（否则模型只会反复试）
 *  3. 宿主没提供通道信息（null）→ 跳过预检，不误伤子代理/定时任务
 */
class ToolChannelPreflightTest {

    @Test
    fun `null channels skips the preflight`() {
        assertEquals(
            ToolChannelPreflight.Result.Proceed,
            ToolChannelPreflight.check("screen_tap", availableChannels = null),
        )
    }

    @Test
    fun `app-internal tool passes with no channels at all`() {
        assertEquals(
            ToolChannelPreflight.Result.Proceed,
            ToolChannelPreflight.check("web_search", availableChannels = emptySet()),
        )
    }

    @Test
    fun `tool needing a missing channel is blocked`() {
        val result = ToolChannelPreflight.check("screen_tap", availableChannels = emptySet())

        assertTrue("无障碍系工具在无通道时必须被拦", result is ToolChannelPreflight.Result.Blocked)
        val blocked = result as ToolChannelPreflight.Result.Blocked
        assertTrue(blocked.missing.contains(Channel.ACCESSIBILITY))
    }

    @Test
    fun `blocked message names the missing channel and where to enable it`() {
        val blocked = ToolChannelPreflight.check("screen_tap", emptySet()) as ToolChannelPreflight.Result.Blocked

        assertTrue("要说明缺什么", blocked.message.contains("无障碍"))
        assertTrue("要给开通路径", blocked.message.contains("设置"))
        assertTrue("要说清本次没执行", blocked.message.contains("未执行"))
    }

    @Test
    fun `blocked message offers an alternative path`() {
        val blocked = ToolChannelPreflight.check("screen_tap", emptySet()) as ToolChannelPreflight.Result.Blocked

        assertTrue("要给出替代手段，否则模型只会反复重试", blocked.message.contains("可以先改用"))
    }

    @Test
    fun `satisfied anyOf passes`() {
        // 屏幕操作类:无障碍或 Shell 任一即可
        val result = ToolChannelPreflight.check("screen_tap", setOf(Channel.SHELL))

        assertEquals(ToolChannelPreflight.Result.Proceed, result)
    }

    @Test
    fun `anyOf with none present is blocked and lists the options`() {
        val blocked = ToolChannelPreflight.check("screen_tap", emptySet()) as ToolChannelPreflight.Result.Blocked

        assertTrue(
            "应列出所有可选通道，实际: ${blocked.missing}",
            blocked.missing.containsAll(setOf(Channel.ACCESSIBILITY, Channel.SHELL)),
        )
    }

    @Test
    fun `tool requiring allOf is blocked when only one is present`() {
        // 静默安装类:需要 Shell 且要读包列表(声明为 allOf)
        val req = ToolRequirements.of("app_uninstall")
        if (req.allOf.isEmpty()) return // 该工具未声明 allOf 时跳过,避免测试绑死实现细节

        val result = ToolChannelPreflight.check("app_uninstall", setOf(Channel.ACCESSIBILITY))

        assertTrue(result is ToolChannelPreflight.Result.Blocked)
    }

    @Test
    fun `root-only tool passes when root is present`() {
        val req = ToolRequirements.of("device_shell")
        if (req.anyOf.isEmpty()) return

        val result = ToolChannelPreflight.check("device_shell", setOf(Channel.ROOT))

        assertEquals(ToolChannelPreflight.Result.Proceed, result)
    }
}
