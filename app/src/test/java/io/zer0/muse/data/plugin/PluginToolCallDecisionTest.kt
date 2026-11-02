package io.zer0.muse.data.plugin

import io.mockk.coEvery
import io.mockk.mockk
import io.zer0.muse.hook.PluginToolDecisionHook
import io.zer0.muse.hook.ToolCallAction
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 决策型钩子：插件能否拦下工具调用（插件计划 P3b）。
 *
 * 判据是**保守**的：只有明确写 block 且带原因才算阻止；
 * 其它任何情况一律放行 —— 一个解析不出来的回答不该让用户的操作凭空失败。
 */
class PluginToolCallDecisionTest {

    @Test
    fun `a block with a reason is honored`() {
        assertEquals(
            "这个网站不在白名单里",
            PluginToolCallDecision.blockReason("""{"action":"block","reason":"这个网站不在白名单里"}"""),
        )
    }

    @Test
    fun `an explicit allow passes`() {
        assertNull(PluginToolCallDecision.blockReason("""{"action":"allow"}"""))
    }

    @Test
    fun `a block without a reason is ignored`() {
        // 没有原因的阻止只会让用户看到"被拦了"，等于什么都没说
        assertNull(PluginToolCallDecision.blockReason("""{"action":"block"}"""))
        assertNull(PluginToolCallDecision.blockReason("""{"action":"block","reason":"   "}"""))
    }

    @Test
    fun `garbage answers pass instead of blocking everything`() {
        listOf(null, "", "   ", "not json", "[]", "42", """{"action":"maybe"}""").forEach { raw ->
            assertNull("解析不出来就放行: $raw", PluginToolCallDecision.blockReason(raw))
        }
    }

    @Test
    fun `a very long reason is truncated`() {
        val long = "原".repeat(PluginToolCallDecision.MAX_REASON_CHARS + 50)

        val reason = PluginToolCallDecision.blockReason("""{"action":"block","reason":"$long"}""")

        assertEquals(PluginToolCallDecision.MAX_REASON_CHARS, reason?.length)
    }

    @Test
    fun `the action word is case insensitive`() {
        assertEquals(
            "x",
            PluginToolCallDecision.blockReason("""{"action":" BLOCK ","reason":"x"}"""),
        )
    }

    // ── 钩子 ────────────────────────────────────────────────────────────

    @Test
    fun `the hook blocks when the bridge returns a reason`() = runBlocking {
        val bridge: PluginActivationBridge = mockk(relaxed = true)
        coEvery { bridge.decideToolCall(any(), any()) } returns "不允许"
        val hook = PluginToolDecisionHook(bridge)

        val action = hook.onToolCallRequested("web_search", mapOf("query" to "x"))

        assertTrue("应拦下: $action", action is ToolCallAction.Block)
        assertEquals("不允许", (action as ToolCallAction.Block).reason)
    }

    @Test
    fun `the hook allows when the bridge says nothing`() = runBlocking {
        val bridge: PluginActivationBridge = mockk(relaxed = true)
        coEvery { bridge.decideToolCall(any(), any()) } returns null
        val hook = PluginToolDecisionHook(bridge)

        assertEquals(ToolCallAction.Allow, hook.onToolCallRequested("screen_read", emptyMap()))
    }

    @Test
    fun `arguments are passed to the plugin as a json object of strings`() {
        val hook = PluginToolDecisionHook(mockk(relaxed = true))

        val json = hook.argsToJson(mapOf("url" to "http://x", "n" to 3))

        assertTrue(json.contains("\"url\""))
        assertTrue(json.contains("http://x"))
        assertTrue(json.contains("\"3\""))
    }

    @Test
    fun `the hook runs early so it can block before anything else`() {
        val hook = PluginToolDecisionHook(mockk(relaxed = true))

        assertTrue("优先级要高: ${hook.priority}", hook.priority >= 50)
    }
}
