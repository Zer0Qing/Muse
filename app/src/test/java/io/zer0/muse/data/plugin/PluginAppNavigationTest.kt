package io.zer0.muse.data.plugin

import io.zer0.muse.tools.script.SkillBridge
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 插件让应用跳转（`app.navigate`）。
 *
 * 两条边界：
 *  1. **白名单**：插件不能把用户弹到任意内部页面；
 *  2. **只投递不执行**：插件跑在后台协程里，没有界面可跳；没有前台界面时如实说"没送到"。
 */
class PluginAppNavigationTest {

    private fun ok(raw: String) = PluginAppNavigation.parse(raw) as PluginAppNavigation.Parsed.Ok

    private fun invalid(raw: String) = PluginAppNavigation.parse(raw) as PluginAppNavigation.Parsed.Invalid

    @Test
    fun `enum names and full uris normalise to the same target`() {
        assertEquals("muse://settings", ok("settings").uri)
        assertEquals("muse://settings", ok("muse://settings").uri)
        assertEquals("muse://scheduled-tasks", ok("scheduled-tasks").uri)
        assertEquals("muse://knowledge-bases", ok("muse://knowledge-bases").uri)
    }

    @Test
    fun `a session target keeps its id`() {
        assertEquals("muse://session/abc-123", ok("session:abc-123").uri)
        assertEquals("muse://session/abc-123", ok("muse://session/abc-123").uri)
    }

    @Test
    fun `an illegal id is refused instead of being spliced into the uri`() {
        // 斜杠与空格拼进 URI 就成了注入点
        assertTrue(invalid("session:../../etc/passwd").reason.contains("不合法"))
        assertTrue(invalid("session:a b").reason.contains("不合法"))
        assertTrue(invalid("session:${"x".repeat(65)}").reason.contains("不合法"))
    }

    @Test
    fun `an unknown target lists what is available`() {
        val reason = invalid("launch-missiles").reason
        assertTrue("要说清不支持: $reason", reason.contains("不支持"))
        assertTrue("要给出可用值: $reason", reason.contains("settings"))
    }

    @Test
    fun `an empty target is refused with a readable reason`() {
        assertTrue(invalid("").reason.isNotEmpty())
        assertTrue(invalid("   ").reason.isNotEmpty())
    }

    // ── 宿主 API ────────────────────────────────────────────────────────

    private fun hostApi() = PluginHostApi(writeConfig = { _, _, _ -> })

    private fun navigateRequest(target: String) =
        """{"__bridge__":true,"action":"app_navigate","params":{"target":"$target"}}"""

    @Test
    fun `without the capability the action is refused`() = runTest {
        val result = hostApi().handle(
            pluginId = "p1",
            manifest = PluginManifest(id = "p1", name = "p1"),
            valueJson = navigateRequest("settings"),
            allowedActions = emptySet(),
        )

        assertTrue("应当是失败: $result", result is SkillBridge.HandleResult.Failure)
        assertTrue(
            "理由要指向授权: ${(result as SkillBridge.HandleResult.Failure).message}",
            result.message.contains("授权") || result.message.contains("未获"),
        )
    }

    @Test
    fun `with the capability but no foreground UI it says so instead of pretending`() = runTest {
        val result = hostApi().handle(
            pluginId = "p1",
            manifest = PluginManifest(id = "p1", name = "p1"),
            valueJson = navigateRequest("settings"),
            allowedActions = setOf("app_navigate"),
        )

        assertTrue("应当是失败: $result", result is SkillBridge.HandleResult.Failure)
        assertTrue(
            "要说清是没有界面: ${(result as SkillBridge.HandleResult.Failure).message}",
            result.message.contains("界面"),
        )
    }

    @Test
    fun `an invalid target is refused before any delivery is attempted`() = runTest {
        val result = hostApi().handle(
            pluginId = "p1",
            manifest = PluginManifest(id = "p1", name = "p1"),
            valueJson = navigateRequest("../../secret"),
            allowedActions = setOf("app_navigate"),
        )

        assertTrue(result is SkillBridge.HandleResult.Failure)
        assertTrue(
            (result as SkillBridge.HandleResult.Failure).message.contains("不支持"),
        )
    }

    @Test
    fun `with a subscriber the request is actually delivered`() = runTest {
        val received = mutableListOf<String>()
        val job = backgroundScope.launch {
            AppNavigationBus.requests.collect { received += it }
        }
        // 等订阅真正建立（subscriptionCount 是判断"有没有人接"的依据）
        while (AppNavigationBus.subscriberCount == 0) yield()

        val delivered = AppNavigationBus.request("muse://settings")
        while (received.isEmpty()) yield()
        job.cancel()

        assertTrue("有订阅者时应当投递成功", delivered)
        assertEquals(listOf("muse://settings"), received)
    }
}
