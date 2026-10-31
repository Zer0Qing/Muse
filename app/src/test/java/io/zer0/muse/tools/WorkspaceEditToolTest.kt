package io.zer0.muse.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.workspace.WorkspaceManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * workspace_edit 端到端测试：走 [WorkspaceTool.execute] → [WorkspaceManager] 的完整链路。
 *
 * 钉住四件事：三类前置拒绝各自的文案且文件未被修改；成功替换后文件其余部分逐字不变；
 * 跨行片段与文件头/尾边界；回执里带得上行级 diff。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WorkspaceEditToolTest {

    private lateinit var manager: WorkspaceManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        manager = WorkspaceManager(context)
    }

    /** 写一份测试文件。 */
    private fun seed(path: String, content: String) {
        val result = runBlocking { manager.writeFile(path, content) }
        assertTrue("写入夹具失败: $result", result is WorkspaceManager.OpResult.Success)
    }

    /** 读回文件全文。 */
    private fun contentOf(path: String): String =
        runBlocking {
            val result = manager.readFile(path)
            assertTrue("读取失败: $result", result is WorkspaceManager.ReadResult.Success)
            (result as WorkspaceManager.ReadResult.Success).content
        }

    /**
     * 一次 workspace_edit 调用。
     *
     * 故意走 [WorkspaceTool.execute] 而不是内部函数：这条链路才是模型实际走的那条。
     */
    private fun edit(path: String, oldString: String, newString: String): String =
        WorkspaceTool.execute(
            WorkspaceTool.NAME_EDIT,
            mapOf("path" to path, "old_string" to oldString, "new_string" to newString),
            manager,
        )

    @Test
    fun `片段找不到时拒绝且文件未被修改`() {
        seed("a.txt", "第一行\n第二行\n第三行\n")

        val receipt = edit("a.txt", "不存在的片段", "x")

        assertTrue(receipt, receipt.startsWith("Error:"))
        assertTrue(receipt, receipt.contains("找不到该片段"))
        assertEquals("第一行\n第二行\n第三行\n", contentOf("a.txt"))
    }

    @Test
    fun `片段出现多次时拒绝且文件未被修改`() {
        val original = "import a\n\nfun first() = 1\n\nfun second() = 1\n"
        seed("b.txt", original)

        val receipt = edit("b.txt", "= 1", "= 2")

        assertTrue(receipt, receipt.startsWith("Error:"))
        assertTrue(receipt, receipt.contains("出现 2 次"))
        assertEquals(original, contentOf("b.txt"))
    }

    @Test
    fun `新旧内容相同时拒绝且文件未被修改`() {
        seed("c.txt", "alpha\nbeta\n")

        val receipt = edit("c.txt", "beta", "beta")

        assertTrue(receipt, receipt.startsWith("Error:"))
        assertTrue(receipt, receipt.contains("新旧内容相同"))
        assertEquals("alpha\nbeta\n", contentOf("c.txt"))
    }

    @Test
    fun `正常替换后文件其余部分逐字不变`() {
        val original =
            "# 标题\n" +
                "\n" +
                "第一段：包含 emoji 🎯 与制表符\t的行。\n" +
                "  缩进两个空格的一行  \n" +
                "const value = 1;\n" +
                "const other = 2;\n" +
                "结尾没有空行"
        seed("d.txt", original)

        val receipt = edit("d.txt", "const value = 1;", "const value = 42;")

        assertTrue(receipt, receipt.startsWith("OK:"))
        assertEquals(original.replace("const value = 1;", "const value = 42;"), contentOf("d.txt"))
    }

    @Test
    fun `跨行片段整体替换`() {
        val original = "header\nfun a() {\n    return 1\n}\nfooter\n"
        seed("e.txt", original)

        val receipt = edit("e.txt", "fun a() {\n    return 1\n}", "fun a() {\n    return 2\n}")

        assertTrue(receipt, receipt.startsWith("OK:"))
        assertEquals("header\nfun a() {\n    return 2\n}\nfooter\n", contentOf("e.txt"))
    }

    @Test
    fun `片段位于文件头时替换成功`() {
        seed("f.txt", "旧首行\nb\nc\n")

        val receipt = edit("f.txt", "旧首行", "新首行")

        assertTrue(receipt, receipt.startsWith("OK:"))
        assertEquals("新首行\nb\nc\n", contentOf("f.txt"))
    }

    @Test
    fun `片段位于文件尾时替换成功`() {
        seed("g.txt", "a\nb\n旧尾行")

        val receipt = edit("g.txt", "旧尾行", "新尾行")

        assertTrue(receipt, receipt.startsWith("OK:"))
        assertEquals("a\nb\n新尾行", contentOf("g.txt"))
    }

    @Test
    fun `回执带行级 diff 指明改了哪几行`() {
        val original = (1..8).joinToString("\n") { "line$it" } + "\n"
        seed("h.txt", original)

        val receipt = edit("h.txt", "line4", "LINE4\n新增行")

        assertTrue(receipt, receipt.startsWith("OK: workspace_edit"))
        // 摘要：首个改动行 + 行数变化
        assertTrue(receipt, receipt.contains("首个改动行 4"))
        assertTrue(receipt, receipt.contains("原文 1 行 → 新内容 2 行"))
        // @@ 头部与增删行
        assertTrue(receipt, receipt.contains("@@ -1,7 +1,8 @@"))
        assertTrue(receipt, receipt.contains("\n-line4\n"))
        assertTrue(receipt, receipt.contains("\n+LINE4\n+新增行\n"))
        // 上下文行仍在，能定位改动位置
        assertTrue(receipt, receipt.contains("\n line3\n"))
        assertTrue(receipt, receipt.contains("\n line5\n"))
    }

    @Test
    fun `空 new_string 表示删除片段`() {
        seed("i.txt", "keep\nremove me\nkeep\n")

        val receipt = edit("i.txt", "remove me\n", "")

        assertTrue(receipt, receipt.startsWith("OK:"))
        assertEquals("keep\nkeep\n", contentOf("i.txt"))
    }

    @Test
    fun `find_tools 能用编辑类关键词搜到本工具`() {
        // 可发现性：工具发出去了还得能被找到。名称与描述里都要能命中常见的提问词。
        val defs = WorkspaceTool.toolDefs()
        listOf("编辑文件", "编辑", "替换", "修改", "workspace edit").forEach { query ->
            val names = FindToolsTool.search(query, defs).map { it.name }
            assertTrue("查询「$query」应能搜到 workspace_edit，实际: $names", WorkspaceTool.NAME_EDIT in names)
        }
    }

    @Test
    fun `路径穿越仍由工作区边界拦下`() {
        val receipt = edit("../escape.txt", "a", "b")

        assertTrue(receipt, receipt.startsWith("Error:"))
        assertTrue(receipt, receipt.contains(".."))
    }

    @Test
    fun `文件不存在时报错而不是新建`() {
        val receipt = edit("missing.txt", "a", "b")

        assertTrue(receipt, receipt.startsWith("Error:"))
        assertTrue(receipt, receipt.contains("不存在"))
        assertTrue(
            "不该凭空创建文件",
            runBlocking { manager.readFile("missing.txt") } is WorkspaceManager.ReadResult.Error,
        )
    }

    @Test
    fun `内容安全规则对 workspace_edit 也只检查路径`() {
        // 正文里带省略号与绝对路径写法不能触发路径规则
        val benign =
            mapOf(
                "path" to "notes/todo.md",
                "old_string" to "待办：等待 3 天...",
                "new_string" to "参见 /usr/local/bin 与 C:\\Windows",
            )
        assertNull(ContentSafetyRules.checkArgs(WorkspaceTool.NAME_EDIT, benign))

        assertEquals(
            "workspace-traversal",
            ContentSafetyRules.checkArgs(
                WorkspaceTool.NAME_EDIT,
                mapOf("path" to "../etc/passwd", "old_string" to "a", "new_string" to "b"),
            )?.ruleId,
        )
        assertEquals(
            "workspace-absolute-path",
            ContentSafetyRules.checkArgs(
                WorkspaceTool.NAME_EDIT,
                mapOf("path" to "/etc/passwd", "old_string" to "a", "new_string" to "b"),
            )?.ruleId,
        )
    }
}
