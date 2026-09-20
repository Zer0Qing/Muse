package io.zer0.muse.ui.chat

import io.mockk.mockk
import io.zer0.muse.tools.ToolRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the production shapes for tool terminal states.
 *
 * The orchestrator synthesises bracketed markers (`[超时]`, `[中断]`) and hooks/MCP
 * bridges return structured `{"error": ...}` payloads; the previous bare-word prefix
 * list matched none of them, so timeouts were reported as successes.
 */
class ToolResultSuccessTest {

    private fun coordinator() = ChatTaskCardCoordinator(
        accessor = mockk(relaxed = true),
        toolRegistry = mockk<ToolRegistry>(relaxed = true),
    )

    @Test
    fun bracketedTerminalStatesAreFailures() {
        val coordinator = coordinator()

        assertFalse(coordinator.isToolResultSuccess("[超时] 工具 web_search 120 秒未响应,已终止"))
        assertFalse(coordinator.isToolResultSuccess("[中断] 工具 read_file 执行被取消"))
        assertFalse(coordinator.isToolResultSuccess("[工具输出已截断: 共 40000 字符]"))
    }

    @Test
    fun structuredErrorPayloadsAreFailures() {
        val coordinator = coordinator()

        assertFalse(coordinator.isToolResultSuccess("""{"error": "Tool denied by user"}"""))
        assertFalse(coordinator.isToolResultSuccess("""{"error":"hook blocked"}"""))
    }

    @Test
    fun legacyPlainTextFailuresStillFail() {
        val coordinator = coordinator()

        assertFalse(coordinator.isToolResultSuccess("error: unknown tool"))
        assertFalse(coordinator.isToolResultSuccess("工具执行异常: timeout"))
        assertFalse(coordinator.isToolResultSuccess("文件不存在: /tmp/a.txt"))
    }

    @Test
    fun normalResultsStaySuccessful() {
        val coordinator = coordinator()

        assertTrue(coordinator.isToolResultSuccess("搜索到 3 条结果"))
        assertTrue(coordinator.isToolResultSuccess("""{"items": [1, 2, 3]}"""))
        assertTrue(coordinator.isToolResultSuccess(""))
    }
}
