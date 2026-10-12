package io.zer0.muse.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v2.6.0: show_card 工具参数解析 —— 内联卡片渲染的入口。
 *
 * 覆盖:正常 HTML/SVG、嵌套 data、转义 JSON 字符串、缺 code 回落。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ShowCardArgsTest {

    @Test
    fun `parses html code and inline data`() {
        val spec = parseShowCardArgs(
            """{"title":"t","code":"<div style=\"x\">hi</div>","data":"{\"values\":[1,2]}"}""",
        )
        assertTrue(spec != null)
        assertEquals("<div style=\"x\">hi</div>", spec!!.code)
        assertEquals("{\"values\":[1,2]}", spec.data)
        assertFalse(spec.isSvg)
    }

    @Test
    fun `detects svg code`() {
        val spec = parseShowCardArgs("""{"code":"<svg xmlns=\"...\"><circle/></svg>"}""")
        assertTrue(spec != null)
        assertTrue(spec!!.isSvg)
    }

    @Test
    fun `decodes escaped json string code`() {
        // 模型常把 HTML 按转义 JSON 字符串传入;JSONObject 解码后应是原文。
        val spec = parseShowCardArgs("""{"code":"<div class=\"a\">x</div>"}""")
        assertEquals("<div class=\"a\">x</div>", spec!!.code)
    }

    @Test
    fun `nested object data is kept as json text`() {
        val spec = parseShowCardArgs("""{"code":"<div/>","data":{"values":[1,2,3]}}""")
        assertTrue(spec != null)
        assertTrue(spec!!.data!!.contains("\"values\""))
    }

    @Test
    fun `missing code returns null`() {
        assertNull(parseShowCardArgs("""{"title":"only title"}"""))
        assertNull(parseShowCardArgs("""{"code":"   "}"""))
        assertNull(parseShowCardArgs(""))
        assertNull(parseShowCardArgs("not json"))
    }
}
