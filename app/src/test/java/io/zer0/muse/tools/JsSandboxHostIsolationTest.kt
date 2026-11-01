package io.zer0.muse.tools

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path as NioPath

class JsSandboxHostIsolationTest {

    @Test
    fun `execution without plugin config clears the previous host config`() {
        val script = JsSandbox.buildHostInjectionScript(
            scopeKey = null,
            pluginConfigJson = null,
        )

        assertTrue(script.contains("window.__musePluginConfig = null;"))
        assertTrue(script.contains("window.__musePluginId = null;"))
    }

    @Test
    fun `plugin config and id are injected for the current execution only`() {
        val script = JsSandbox.buildHostInjectionScript(
            scopeKey = "plugin-a",
            pluginConfigJson = """{"token":"value"}""",
        )

        assertTrue(script.contains("""window.__musePluginConfig = {"token":"value"};"""))
        assertTrue(script.contains("""window.__musePluginId = "plugin-a";"""))
    }

    @Test
    fun `execution token is injected and never left over from a previous run`() {
        val withToken = JsSandbox.buildHostInjectionScript(
            scopeKey = "plugin-a",
            pluginConfigJson = null,
            executionToken = "tok-123",
        )
        val withoutToken = JsSandbox.buildHostInjectionScript(
            scopeKey = null,
            pluginConfigJson = null,
        )

        assertTrue("""应注入 token，实际: $withToken""", withToken.contains("""window.__museExecToken = "tok-123";"""))
        assertTrue("非插件执行必须把 token 复位成 null", withoutToken.contains("window.__museExecToken = null;"))
    }

    @Test
    fun `network shim forwards the token so kotlin can charge it to the plugin`() {
        // 结构测试：垫片不带 token 的话，Kotlin 侧就分不清是谁在联网，授权门形同虚设。
        val source = sandboxSource()
        assertTrue("fetch 垫片必须回传 token", source.contains("token: window.__museExecToken"))
        assertTrue("缺 token 必须被拒", source.contains("缺少有效的宿主调用凭证"))
    }

    private fun sandboxSource(): String {
        val candidates = listOf(
            NioPath.of("src/main/java/io/zer0/muse/tools/JsSandbox.kt"),
            NioPath.of("app/src/main/java/io/zer0/muse/tools/JsSandbox.kt"),
        )
        val file = candidates.firstOrNull(Files::exists) ?: error("JsSandbox.kt not found")
        return Files.newBufferedReader(file).use { it.readText() }
    }
}
