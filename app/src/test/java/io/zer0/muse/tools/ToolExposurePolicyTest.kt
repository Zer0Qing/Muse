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
            applyIntentFilter = true,
        )

        assertEquals(setOf("calculator", "echo", "get_current_time"), selected.map { it.name }.toSet())
    }

    @Test
    fun `explicit request to test all tools keeps full authorized set`() {
        val all = allTools + (1..30).map { tool("extra_$it") }
        val selected = ToolExposurePolicy.select(
            tools = all,
            userText = "请测试所有工具并逐个报告结果",
        )
        assertEquals("明确测试全部工具时不得裁剪", all.map { it.name }.toSet(), selected.map { it.name }.toSet())
    }

    @Test
    fun `web request exposes web family without unrelated side effects`() {
        val selected = ToolExposurePolicy.select(
            tools = allTools + (1..30).map { tool("extra_$it") },
            userText = "帮我搜索一下今天的新闻",
            applyIntentFilter = true,
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

    @Test
    fun `F11 multi intent message merges all matched families without dropping tools`() {
        // 一条消息同时含"搜索"(web 族)与"发短信"(phone 族)意图,
        // select 必须返回并集(web + phone 工具都暴露),不得只保留第一个命中族。
        val selected = ToolExposurePolicy.select(
            tools = allTools + (1..30).map { tool("extra_$it") },
            userText = "先搜索一下今天的新闻,再给张三发条短信",
            applyIntentFilter = true,
        )
        val names = selected.map { it.name }.toSet()

        // web 族
        assertTrue("web_search" in names)
        assertTrue("web_fetch" in names)
        // phone 族
        assertTrue("send_sms" in names)
        // 无副作用公共工具始终保留
        assertTrue("calculator" in names)
        // 未命中族(agent/media)不应泄漏
        assertFalse("delegate_agent" in names)
        assertFalse("generate_image" in names)
    }
}
