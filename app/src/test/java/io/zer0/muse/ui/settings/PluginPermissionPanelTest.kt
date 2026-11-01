package io.zer0.muse.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.zer0.muse.data.plugin.PluginCapabilityGateway
import io.zer0.muse.data.plugin.PluginPermissionTier
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.nio.file.Files

/**
 * 权限面板回归（P0-3）。
 *
 * 只测两件真正决定"闸门会不会伤人"的事：
 *  1. 面板上关掉一个已声明能力，执行侧**立刻**被拒（同一份账本，不需重启/重装）；
 *  2. "本次会话允许"这个逃生口能救急 —— 但**绝不落盘**，重启就回到未授权。
 *
 * 第 2 条专门针对**清单未声明**的能力：逃生口的存在理由就是"老插件忘了声明但以前能用"。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PluginPermissionPanelTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun newGateway(): PluginCapabilityGateway = PluginCapabilityGateway(
        Files.createTempDirectory("plugin-panel").resolve("permissions.json").toFile(),
    ) { 1_000L }

    @Test
    fun `toggling a declared capability off denies the next call`() {
        val gateway = newGateway()
        val declared = listOf("network")
        assertTrue(
            "前置条件：首见按声明放行",
            gateway.check("p1", declared, "http_get") == PluginCapabilityGateway.Decision.Allow,
        )

        composeTestRule.setContent {
            MaterialTheme {
                PluginPermissionPanel(
                    gateway = gateway,
                    pluginId = "p1",
                    declaredCapabilities = declared,
                )
            }
        }

        composeTestRule.onNodeWithText("network").assertExists()
        composeTestRule.onAllNodes(isToggleable()).onFirst().assertIsOn().performClick()

        val after = gateway.check("p1", declared, "http_get")
        assertTrue("面板关掉后应立即被拒，实际: $after", after is PluginCapabilityGateway.Decision.Deny)
    }

    @Test
    fun `escape hatch works now but never persists`() {
        val file = Files.createTempDirectory("plugin-panel-session").resolve("permissions.json").toFile()
        val gateway = PluginCapabilityGateway(file) { 1_000L }
        // 面板上点"本次会话允许"：这个能力插件根本没声明，正是存量插件的场景
        gateway.grant(
            pluginId = "p2",
            capability = "resource.write",
            allowed = true,
            declared = emptyList(),
            tier = PluginPermissionTier.SESSION,
        )

        val allowedNow = gateway.check("p2", emptyList(), "fs_write")
        val afterRestart = PluginCapabilityGateway(file) { 1_000L }.check("p2", emptyList(), "fs_write")

        assertTrue("会话档当场应放行，实际: $allowedNow", allowedNow == PluginCapabilityGateway.Decision.Allow)
        assertTrue("但不得落盘，重启后应回到未授权，实际: $afterRestart", afterRestart is PluginCapabilityGateway.Decision.Deny)
    }

    @Test
    fun `python row stays off until the user turns it on`() {
        val gateway = newGateway()
        gateway.seedFromDeclaration("p9", listOf("network"))

        composeTestRule.setContent {
            MaterialTheme {
                PluginPermissionPanel(
                    gateway = gateway,
                    pluginId = "p9",
                    declaredCapabilities = listOf("network"),
                    runtime = "python",
                )
            }
        }

        // Python 行排在能力行之前，所以第一个开关就是它
        val pythonSwitch = composeTestRule.onAllNodes(isToggleable()).onFirst()
        assertFalse("Python 档默认必须是关的", gateway.isApproved("p9", PluginCapabilityGateway.RUNTIME_PYTHON))
        pythonSwitch.performClick()

        assertTrue(
            "面板上打开后应真的生效",
            gateway.isApproved("p9", PluginCapabilityGateway.RUNTIME_PYTHON),
        )
    }
}
