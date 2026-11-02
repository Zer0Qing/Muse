package io.zer0.muse.data.plugin

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.tools.script.SkillBridge
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 插件改外观（`app.theme`）。
 *
 * 只开放明暗模式。测试重点是**拒绝面**：模式拼错、未授权、通道缺失时都不能"看起来成功"。
 */
class PluginAppearanceBridgeTest {

    private val settings: SettingsRepository = mockk(relaxed = true)
    private val bridge = PluginAppearanceBridge(settings)

    @After
    fun tearDown() {
        PluginAppearanceChannels.resetForTests()
    }

    @Test
    fun `the three modes are accepted and written through`() = runTest {
        listOf("light", "dark", "system").forEach { mode ->
            assertTrue("$mode 应当被接受", bridge.setMode(mode) is PluginAppearanceBridge.Applied.Ok)
        }
        coVerify(exactly = 3) { settings.saveThemeMode(any()) }
    }

    @Test
    fun `mode parsing is case and whitespace tolerant`() = runTest {
        assertTrue(bridge.setMode("  DARK ") is PluginAppearanceBridge.Applied.Ok)
        coVerify { settings.saveThemeMode("dark") }
    }

    @Test
    fun `an unknown mode is refused and nothing is written`() = runTest {
        val result = bridge.setMode("sepia")

        assertTrue(result is PluginAppearanceBridge.Applied.Rejected)
        assertTrue(
            "要列出可用值: ${(result as PluginAppearanceBridge.Applied.Rejected).reason}",
            result.reason.contains("light") && result.reason.contains("system"),
        )
        coVerify(exactly = 0) { settings.saveThemeMode(any()) }
    }

    @Test
    fun `a storage failure is reported instead of pretending success`() = runTest {
        coEvery { settings.saveThemeMode(any()) } throws IllegalStateException("disk full")

        val result = bridge.setMode("dark")

        assertTrue(result is PluginAppearanceBridge.Applied.Rejected)
        assertTrue((result as PluginAppearanceBridge.Applied.Rejected).reason.contains("disk full"))
    }

    // ── 宿主 API 侧 ─────────────────────────────────────────────────────

    private fun request(mode: String) =
        """{"__bridge__":true,"action":"app_set_theme_mode","params":{"mode":"$mode"}}"""

    @Test
    fun `without the capability the action is refused`() = runTest {
        PluginAppearanceChannels.install(bridge)
        val api = PluginHostApi(writeConfig = { _, _, _ -> })

        val result = api.handle("p1", PluginManifest(id = "p1", name = "p1"), request("dark"), emptySet())

        assertTrue(result is SkillBridge.HandleResult.Failure)
        coVerify(exactly = 0) { settings.saveThemeMode(any()) }
    }

    @Test
    fun `a missing channel fails closed`() = runTest {
        val api = PluginHostApi(writeConfig = { _, _, _ -> })

        val result = api.handle(
            "p1",
            PluginManifest(id = "p1", name = "p1"),
            request("dark"),
            setOf(PluginHostApi.ACTION_APP_SET_THEME_MODE),
        )

        assertTrue(result is SkillBridge.HandleResult.Failure)
        assertTrue((result as SkillBridge.HandleResult.Failure).message.contains("未接入"))
    }

    @Test
    fun `with capability and channel the mode is applied`() = runTest {
        PluginAppearanceChannels.install(bridge)
        val api = PluginHostApi(writeConfig = { _, _, _ -> })

        val result = api.handle(
            "p1",
            PluginManifest(id = "p1", name = "p1"),
            request("light"),
            setOf(PluginHostApi.ACTION_APP_SET_THEME_MODE),
        )

        assertTrue("应当成功: $result", result is SkillBridge.HandleResult.Output)
        coVerify(exactly = 1) { settings.saveThemeMode("light") }
    }
}
