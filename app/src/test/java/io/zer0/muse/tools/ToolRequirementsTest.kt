package io.zer0.muse.tools

import io.zer0.muse.tools.ToolRequirements.Channel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.6.7: 工具可用性判定 —— tool_status 的地基。
 *
 * 助手自审的核心抱怨是「不知道哪个工具真能用」。这张测试确保：
 *  - 应用内工具在任何权限下都可用（不能让模型连记忆/卡片都不敢用）；
 *  - 需要通道的工具在通道缺失时被正确判为不可用（避免「我帮你点一下」然后失败）；
 *  - anyOf 语义（无障碍或 Shell 任一即可）与 allOf 语义（必须同时满足）不混淆。
 */
class ToolRequirementsTest {

    @Test
    fun `app-internal tools are usable without any channel`() {
        val none = emptySet<Channel>()
        // 这些是模型最常用的一批，缺权限时也必须可用
        listOf("show_card", "save_memory", "search_memory", "todo_write", "calculator", "find_tools")
            .forEach { tool ->
                assertTrue("$tool 不应依赖设备通道", ToolRequirements.of(tool).isUnconditional)
                assertTrue("$tool 应可用", ToolRequirements.isUsable(tool, none))
            }
    }

    @Test
    fun `screen tools require accessibility or shell`() {
        val req = ToolRequirements.of("screen_tap")
        assertTrue("应依赖屏幕通道", req.anyOf.contains(Channel.ACCESSIBILITY))
        assertTrue("Shell 也应可用", req.anyOf.contains(Channel.SHELL))

        assertFalse("全无通道时不可用", ToolRequirements.isUsable("screen_tap", emptySet()))
        assertTrue("有无障碍即可", ToolRequirements.isUsable("screen_tap", setOf(Channel.ACCESSIBILITY)))
        assertTrue("有 Shell 也可", ToolRequirements.isUsable("screen_tap", setOf(Channel.SHELL)))
    }

    @Test
    fun `shell-only tools reject accessibility alone`() {
        // device_shell 是无障碍做不到的：只开无障碍不能放行。
        assertFalse(
            "无障碍不能替代 Shell",
            ToolRequirements.isUsable("device_shell", setOf(Channel.ACCESSIBILITY)),
        )
        assertTrue(
            "Shell 就绪即可",
            ToolRequirements.isUsable("device_shell", setOf(Channel.SHELL)),
        )
        assertTrue(
            "Root 也覆盖 Shell",
            ToolRequirements.isUsable("device_shell", setOf(Channel.ROOT)),
        )
    }

    @Test
    fun `single runtime permissions gate their own tools`() {
        val none = emptySet<Channel>()
        assertFalse(ToolRequirements.isUsable("get_recent_notifications", none))
        assertTrue(
            ToolRequirements.isUsable("get_recent_notifications", setOf(Channel.NOTIFICATION_LISTENER)),
        )
        assertFalse(ToolRequirements.isUsable("screen_time", setOf(Channel.ACCESSIBILITY)))
        assertTrue(ToolRequirements.isUsable("screen_time", setOf(Channel.USAGE_STATS)))
    }

    @Test
    fun `unknown tool defaults to unconditional`() {
        // 新增工具忘记登记时，宁可判为可用（模型会试一次），
        // 也不要误判为不可用（模型会永久放弃一个其实能用的工具）。
        assertTrue(ToolRequirements.of("some_new_tool_never_registered").isUnconditional)
    }
}
