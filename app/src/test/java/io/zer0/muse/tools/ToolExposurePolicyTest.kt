package io.zer0.muse.tools

import io.zer0.ai.core.ToolDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolExposurePolicyTest {

    private fun tool(name: String) = ToolDefinition(
        name = name,
        description = name,
        parametersJsonSchema = """{"type":"object","properties":{}}""",
    )

    private val allTools = listOf(
        tool("calculator"),
        tool("echo"),
        tool("get_current_time"),
        tool("web_search"),
        tool("web_fetch"),
        tool("send_sms"),
        tool("delegate_agent"),
        tool("generate_image"),
    )

    @Test
    fun `generic request keeps only small safe baseline`() {
        val selected = ToolExposurePolicy.select(
            tools = allTools + (1..30).map { tool("extra_$it") },
            userText = "帮我想一个标题",
        )

        assertEquals(setOf("calculator", "echo", "get_current_time"), selected.map { it.name }.toSet())
    }

    @Test
    fun `web request exposes web family without unrelated side effects`() {
        val selected = ToolExposurePolicy.select(
            tools = allTools + (1..30).map { tool("extra_$it") },
            userText = "帮我搜索一下今天的新闻",
        )
        val names = selected.map { it.name }.toSet()

        assertTrue("web_search" in names)
        assertTrue("web_fetch" in names)
        assertFalse("send_sms" in names)
        assertFalse("delegate_agent" in names)
    }

    @Test
    fun `small configured tool set is preserved`() {
        val selected = ToolExposurePolicy.select(
            tools = listOf(tool("custom_tool")),
            userText = "普通聊天",
        )

        assertEquals(listOf("custom_tool"), selected.map { it.name })
    }

    @Test
    fun `simple tool request can disable repeated reasoning`() {
        assertTrue(ToolExposurePolicy.isSimpleToolRequest("帮我算一下 1+2"))
        assertTrue(ToolExposurePolicy.isSimpleToolRequest("调用 echo 记录一下"))
        assertFalse(ToolExposurePolicy.isSimpleToolRequest("请分析这个方案为什么失败"))
    }

    @Test
    fun `explicit action requires tool choice`() {
        assertTrue(
            ToolExposurePolicy.shouldRequireTool(
                "帮我搜索一下今天的新闻",
                allTools,
            ),
        )
        assertTrue(
            ToolExposurePolicy.shouldRequireTool(
                "echo hello",
                allTools,
            ),
        )
        assertFalse(
            ToolExposurePolicy.shouldRequireTool(
                "今天心情不错",
                allTools,
            ),
        )
    }

    @Test
    fun `assistant bound MCP tools survive unrelated keyword filtering`() {
        val mcp = tool("mcp_github__create_issue")
        val selected = ToolExposurePolicy.select(
            tools = allTools + mcp + (1..30).map { tool("extra_$it") },
            userText = "帮我创建一个 issue",
            alwaysExposeNames = setOf(mcp.name),
        )

        assertTrue(mcp.name in selected.map { it.name })
    }

    @Test
    fun `MCP action can require a tool without explicitly saying MCP`() {
        assertTrue(
            ToolExposurePolicy.shouldRequireTool(
                "帮我创建一个 issue",
                allTools + tool("mcp_github__create_issue"),
            ),
        )
        assertTrue(
            ToolExposurePolicy.shouldRequireTool(
                "帮我查找这个 issue",
                allTools + tool("mcp_github__search_issues"),
            ),
        )
    }
}
