package io.zer0.muse.tools

import io.zer0.ai.core.ToolDefinition
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
    fun `simple tool request can disable repeated reasoning`() {
        assertTrue(ToolExposurePolicy.isSimpleToolRequest("帮我算一下 1+2"))
        assertTrue(ToolExposurePolicy.isSimpleToolRequest("调用 echo 记录一下"))
        assertFalse(ToolExposurePolicy.isSimpleToolRequest("请分析这个方案为什么失败"))
    }

    @Test
    fun `explicit no-tool request disables provider tool calls`() {
        assertTrue(ToolExposurePolicy.shouldDisableTools("列出所有工具，不要调用任何工具"))
        assertTrue(ToolExposurePolicy.shouldDisableTools("list all tools, do not call any tools"))
        assertFalse(ToolExposurePolicy.shouldRequireTool("列出所有工具，不要调用任何工具", allTools))
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

    /** 构造一个"工具很多"的列表,覆盖收窄逻辑的触发阈值。 */
    private val manyTools = allTools + listOf(
        tool("set_alarm"), tool("toggle_flashlight"), tool("quick_note_add"), tool("translate"),
        tool("get_weather"), tool("open_app"), tool("clipboard_read"), tool("search_memory"),
        tool("generate_video"), tool("parse_pdf"), tool("ping_host"), tool("notify"),
        tool("show_card"), tool("todo_write"), tool("share_text"), tool("set_timer"),
        tool("list_reminders"), tool("base64_encode"), tool("mcp_github__create_issue"), tool("mcp_files__list"),
    )

    @Test
    fun `simple request narrows tools to matched family`() {
        val filtered = ToolExposurePolicy.filterToolsForRequest("设个明天早上八点的闹钟", manyTools)
        val names = filtered.map { it.name }.toSet()
        assertTrue("闹钟工具应保留", "set_alarm" in names)
        assertTrue("核心工具应保留", "calculator" in names)
        assertTrue("MCP 工具应保留", "mcp_github__create_issue" in names)
        assertFalse("无关工具应被收窄", "generate_image" in names)
        assertFalse("无关工具应被收窄", "web_search" in names)
    }

    @Test
    fun `complex request keeps the full tool list`() {
        val filtered = ToolExposurePolicy.filterToolsForRequest("请分析这个方案为什么失败,并画一张图", manyTools)
        assertTrue(filtered.size == manyTools.size)
    }

    @Test
    fun `unmatched simple request keeps the full tool list`() {
        val filtered = ToolExposurePolicy.filterToolsForRequest("你好呀,今天心情不错", manyTools)
        assertTrue(filtered.size == manyTools.size)
    }

    @Test
    fun `greeting is not a direct tool request`() {
        // v2.0 回归:短句问候不应被当成工具意图,否则深度思考会被误降级而看不到思考过程
        assertFalse(ToolExposurePolicy.isDirectToolRequest("你好"))
        assertFalse(ToolExposurePolicy.isDirectToolRequest("你好", allTools))
        assertFalse(ToolExposurePolicy.isDirectToolRequest("今天心情不错", allTools))
    }

    @Test
    fun `explicit action is a direct tool request`() {
        assertTrue(ToolExposurePolicy.isDirectToolRequest("帮我搜索一下今天的新闻", allTools))
        assertTrue(ToolExposurePolicy.isDirectToolRequest("帮我设置提醒：明天早上八点叫我", allTools))
        assertTrue(
            ToolExposurePolicy.isDirectToolRequest(
                "帮我创建一个 issue",
                allTools + tool("mcp_github__create_issue"),
            ),
        )
        // 问句式 MCP 操作词不算动作请求
        assertFalse(
            ToolExposurePolicy.isDirectToolRequest(
                "什么是 MCP",
                allTools + tool("mcp_github__create_issue"),
            ),
        )
    }
}
