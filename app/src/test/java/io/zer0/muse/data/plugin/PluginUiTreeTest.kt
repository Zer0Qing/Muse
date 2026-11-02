package io.zer0.muse.data.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 声明式界面树的解析与上限回归。
 *
 * 在意两点：**接受该接受的**（作者手写 JSON，宽松一点点都很爽），
 * 以及**拒得明白**（超限/拼错时必须说清是哪一条，否则作者只能来回猜）。
 */
class PluginUiTreeTest {

    private val sample = """
        {
          "type": "column",
          "children": [
            { "type": "heading", "text": "今日概览" },
            { "type": "row", "children": [
                { "type": "stat", "label": "完成", "value": "12" },
                { "type": "stat", "label": "阻塞", "value": "3", "hint": "需人工" }
            ] },
            { "type": "progress", "value": 12, "max": 15, "label": "进度" },
            { "type": "divider" },
            { "type": "list", "items": ["买牛奶", "写周报"] },
            { "type": "assetImage", "path": "assets/chart.png", "alt": "趋势图" },
            { "type": "code", "text": "npm run build", "language": "bash" }
          ]
        }
    """.trimIndent()

    @Test
    fun `a well formed tree parses into typed nodes`() {
        val parsed = PluginUiTree.parse(sample)

        assertTrue("应解析成功: ${parsed.reason}", parsed.success)
        val column = parsed.root as? PluginUiTree.Node.Column
        assertNotNull(column)
        assertEquals("首节点是标题", PluginUiTree.Node.Heading::class, column?.children?.first()?.let { it::class })
        assertTrue("节点数应被统计: ${parsed.nodes}", parsed.nodes >= 10)
    }

    @Test
    fun `unknown node type is rejected with the supported list`() {
        val parsed = PluginUiTree.parse("""{ "type": "sparkline", "points": [1,2] }""")

        assertFalse("未知类型必须拒", parsed.success)
        assertTrue("要列出支持的类型: ${parsed.reason}", parsed.reason?.contains("支持的类型") == true)
    }

    @Test
    fun `unknown key is rejected instead of silently dropping content`() {
        // 作者把 "text" 拼成 "tetx" 时，静默忽略会渲染出一块空白而作者不知道原因。
        val parsed = PluginUiTree.parse("""{ "type": "heading", "tetx": "标题" }""")

        assertFalse("拼错键名必须拒: ${parsed.reason}", parsed.success)
        assertTrue("要给出位置: ${parsed.reason}", parsed.reason?.contains("位置") == true)
    }

    @Test
    fun `depth beyond the limit is refused`() {
        val depth = PluginUiTree.MAX_DEPTH + 6
        val nested = buildString {
            repeat(depth) { append("""{"type":"column","children":[""") }
            append("""{"type":"text","text":"最深"}""")
            repeat(depth) { append("]}") }
        }

        val parsed = PluginUiTree.parse(nested)

        assertFalse("超深度必须整份拒", parsed.success)
        assertTrue("要说清深度上限: ${parsed.reason}", parsed.reason?.contains("嵌套深度") == true)
    }

    @Test
    fun `node count beyond the limit is refused`() {
        val children = (1..(PluginUiTree.MAX_NODES + 50)).joinToString(",") {
            """{"type":"text","text":"第 $it 条"}"""
        }

        val parsed = PluginUiTree.parse("""{"type":"column","children":[$children]}""")

        assertFalse("节点数超上限必须拒", parsed.success)
        assertTrue("要说清节点上限: ${parsed.reason}", parsed.reason?.contains("节点数") == true)
    }

    @Test
    fun `oversized single text is refused rather than truncated`() {
        val huge = "字".repeat(PluginUiTree.MAX_TEXT_CHARS + 1)

        val parsed = PluginUiTree.parse("""{"type":"text","text":"$huge"}""")

        assertFalse("超长文本要拒而不是截半", parsed.success)
        assertTrue("要说清文本上限: ${parsed.reason}", parsed.reason?.contains("文本超过") == true)
    }

    @Test
    fun `oversized source is refused before parsing`() {
        val source = "{" + " ".repeat(PluginUiTree.MAX_SOURCE_CHARS + 10) + "}"

        val parsed = PluginUiTree.parse(source)

        assertTrue("要说清文件字符上限: ${parsed.reason}", parsed.reason?.contains("界面文件") == true)
    }

    @Test
    fun `non finite progress value is refused`() {
        // JSON 没有 NaN，但 1e400 会解成 Infinity，渲染成进度条时会算出负宽度。
        val parsed = PluginUiTree.parse("""{"type":"progress","value":1e400}""")

        assertFalse("无穷值必须拒: ${parsed.reason}", parsed.success)
    }

    @Test
    fun `blank asset path is refused`() {
        val parsed = PluginUiTree.parse("""{"type":"assetImage","path":"  "}""")

        assertFalse("空路径要拒", parsed.success)
        assertTrue("要说清是哪个字段: ${parsed.reason}", parsed.reason?.contains("assetImage.path") == true)
    }

    @Test
    fun `every supported type name actually round-trips`() {
        // 文案里承诺支持哪些类型，就必须真能解析：漏一个就是"文档撒谎"。
        val probes = mapOf(
            "column" to """{"type":"column","children":[]}""",
            "row" to """{"type":"row","children":[]}""",
            "divider" to """{"type":"divider"}""",
            "spacer" to """{"type":"spacer","height":8}""",
            "heading" to """{"type":"heading","text":"h"}""",
            "text" to """{"type":"text","text":"t"}""",
            "caption" to """{"type":"caption","text":"c"}""",
            "badge" to """{"type":"badge","text":"b"}""",
            "stat" to """{"type":"stat","label":"l","value":"v"}""",
            "kv" to """{"type":"kv","key":"k","value":"v"}""",
            "list" to """{"type":"list","items":["a"]}""",
            "progress" to """{"type":"progress","value":1.0}""",
            "assetImage" to """{"type":"assetImage","path":"assets/a.png"}""",
            "code" to """{"type":"code","text":"x"}""",
        )

        val unsupported = PluginUiTree.SUPPORTED_TYPES - probes.keys
        assertTrue("SUPPORTED_TYPES 里有类型没被覆盖: $unsupported", unsupported.isEmpty())
        probes.forEach { (type, source) ->
            assertTrue("类型 $type 应能解析: ${PluginUiTree.parse(source).reason}", PluginUiTree.parse(source).success)
        }
    }

    @Test
    fun `limits are stated in the message so authors can fix them`() {
        val parsed = PluginUiTree.parse("""{"type":"text","text":"${"字".repeat(PluginUiTree.MAX_TEXT_CHARS + 1)}"}""")

        assertTrue(
            "拒绝信息要带上限数字，方便作者照着改: ${parsed.reason}",
            parsed.reason?.contains(PluginUiTree.MAX_TEXT_CHARS.toString()) == true,
        )
    }

    @Test
    fun `empty children list is accepted`() {
        val parsed = PluginUiTree.parse("""{"type":"column","children":[]}""")

        assertTrue("空容器不该被当成错误: ${parsed.reason}", parsed.success)
        assertEquals(1, parsed.nodes)
    }
}
