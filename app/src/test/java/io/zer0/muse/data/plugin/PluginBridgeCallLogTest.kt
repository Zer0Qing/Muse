package io.zer0.muse.data.plugin

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 插件桥接调用的近期记录（插件计划 P4 调试台）。
 *
 * 作者最常卡住的地方就是"为什么被拒绝"，而这条信息此前只在日志文件里。
 */
class PluginBridgeCallLogTest {

    @Before
    fun setUp() = PluginBridgeCallLog.resetForTests()

    @After
    fun tearDown() = PluginBridgeCallLog.resetForTests()

    @Test
    fun `the newest call comes first`() {
        PluginBridgeCallLog.record("p1", "fs_read", allowed = true, detail = null, atMillis = 1)
        PluginBridgeCallLog.record("p1", "http_get", allowed = false, detail = "未授权", atMillis = 2)

        val recent = PluginBridgeCallLog.recent()

        assertEquals(listOf("http_get", "fs_read"), recent.map { it.action })
    }

    @Test
    fun `a denied call keeps its reason`() {
        PluginBridgeCallLog.record("p1", "tool_invoke", allowed = false, detail = "需要 host.tool.invoke 授权", atMillis = 1)

        val entry = PluginBridgeCallLog.recent().single()

        assertTrue(!entry.allowed)
        assertEquals("需要 host.tool.invoke 授权", entry.detail)
    }

    @Test
    fun `the log is capped so it cannot grow forever`() {
        repeat(PluginBridgeCallLog.MAX_ENTRIES + 25) { index ->
            PluginBridgeCallLog.record("p1", "act$index", allowed = true, detail = null, atMillis = index.toLong())
        }

        val all = PluginBridgeCallLog.recent(PluginBridgeCallLog.MAX_ENTRIES * 2)

        assertEquals(PluginBridgeCallLog.MAX_ENTRIES, all.size)
        assertEquals("最新的应保留", "act${PluginBridgeCallLog.MAX_ENTRIES + 24}", all.first().action)
    }

    @Test
    fun `a limit returns only the newest entries`() {
        repeat(5) { index -> PluginBridgeCallLog.record("p1", "a$index", true, null, index.toLong()) }

        assertEquals(listOf("a4", "a3"), PluginBridgeCallLog.recent(2).map { it.action })
    }

    @Test
    fun `an unknown action is still recorded`() {
        PluginBridgeCallLog.record("p1", null, allowed = false, detail = "坏请求", atMillis = 1)

        assertEquals("(未知)", PluginBridgeCallLog.recent().single().action)
    }

    @Test
    fun `the action name is extracted from the bridge request`() {
        assertEquals("fs_read", PluginBridgeCallLog.actionOf("""{"action":"fs_read","path":"a.txt"}"""))
        assertEquals("storage_get", PluginBridgeCallLog.actionOf("""{"action":"storage_get"}"""))
    }

    @Test
    fun `a malformed request yields no action instead of throwing`() {
        listOf("", "not json", "[]", """{"no_action":1}""", """{"action":123}""").forEach { raw ->
            assertNull("解析不出来就返回 null: $raw", PluginBridgeCallLog.actionOf(raw))
        }
    }
}
