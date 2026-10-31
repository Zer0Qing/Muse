package io.zer0.muse.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FindToolsToolTest {

    private fun def(name: String, desc: String) = ToolRegistry.ToolDef(
        name = name,
        description = desc,
        parameters = mapOf("x" to "param"),
        required = setOf("x"),
        // 夹具风险等级不是本测试的关注点（检索/分组不看它），但也不能随手写 SAFE：
        // 名字都是真实工具名或第三方工具名，用生产侧同一套推断给出诚实值。
        riskLevel = ToolPermissionResolver.riskLevelFor(name),
    )

    private val tools = listOf(
        def("browser_navigate", "打开浏览器访问网页 open a URL in browser"),
        def("browser_click", "点击网页元素 click an element on the page"),
        def("workspace_write", "写入工作区文件 write a file into the workspace"),
        def("set_alarm", "设置闹钟 set an alarm"),
        def("find_tools", "search the tool library"),
    )

    @Test
    fun `search matches by name and description`() {
        val byName = FindToolsTool.search("browser", tools).map { it.name }.toSet()
        assertTrue("名称命中应包含 browser_navigate", "browser_navigate" in byName)
        assertTrue("名称命中应包含 browser_click", "browser_click" in byName)
        assertTrue("不相关工具不应命中", "set_alarm" !in byName)

        val byDesc = FindToolsTool.search("工作区", tools).map { it.name }
        assertEquals(listOf("workspace_write"), byDesc)
    }

    @Test
    fun `search excludes the meta tool itself`() {
        val hits = FindToolsTool.search("tool library", tools).map { it.name }
        assertTrue("find_tools 不应出现在结果里", "find_tools" !in hits)
    }

    @Test
    fun `search caps result count`() {
        val many = (1..20).map { def("tool_$it", "demo tool number $it") }
        val hits = FindToolsTool.search("demo", many)
        assertTrue("结果应非空", hits.isNotEmpty())
        assertTrue("结果数应受上限约束", hits.size <= 8)
    }

    @Test
    fun `empty query returns nothing`() {
        assertTrue(FindToolsTool.search("   ", tools).isEmpty())
    }

    @Test
    fun `loaded registry is session scoped and idempotent`() {
        SessionToolLoadRegistry.markLoaded("s1", listOf("browser_navigate", "set_alarm"))
        SessionToolLoadRegistry.markLoaded("s1", listOf("browser_navigate"))
        assertEquals(
            setOf("browser_navigate", "set_alarm"),
            SessionToolLoadRegistry.loadedFor("s1"),
        )
        assertTrue("其他会话不受影响", SessionToolLoadRegistry.loadedFor("s2").isEmpty())
        SessionToolLoadRegistry.clear("s1")
        assertTrue(SessionToolLoadRegistry.loadedFor("s1").isEmpty())
    }

    @Test
    fun `empty query renders full capability list grouped by category`() {
        val tools = listOf(
            def("calculator", "简单计算器"),
            def("web_search", "联网搜索"),
            def("generate_image", "生成图片"),
            def("workspace_write", "写入工作区"),
            def("mcp_x__do", "外部工具"),
            def("find_tools", "search the tool library"),
        )
        val list = FindToolsTool.fullList(tools)
        assertTrue("应包含总数说明", list.contains("5 tools total"))
        assertTrue("核心工具应出现", "calculator" in list)
        assertTrue("可选工具应出现", "generate_image" in list)
        assertTrue("扩展分组应出现", "extension" in list)
        assertTrue("自身不应出现在清单里", "find_tools:" !in list)
    }
}
