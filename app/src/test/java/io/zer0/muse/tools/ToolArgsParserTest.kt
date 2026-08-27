package io.zer0.muse.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolArgsParserTest {

    @Test
    fun `normal json parses all fields`() {
        val args = ToolArgsParser.parse("""{"query":"天气","max_results":5}""", "web_search")
        assertEquals("天气", args["query"])
        assertEquals("5", args["max_results"])
    }

    @Test
    fun `nested object value is serialized to string`() {
        val args = ToolArgsParser.parse("""{"opts":{"a":1},"query":"x"}""")
        assertEquals("x", args["query"])
        // 嵌套对象序列化为 JSON 字符串（非空即可，执行层按字符串消费）
        assertTrue(args["opts"]?.contains("a") == true)
    }

    @Test
    fun `array value is serialized to string`() {
        val args = ToolArgsParser.parse("""{"tags":["a","b"]}""")
        assertEquals("""["a","b"]""", args["tags"])
    }

    @Test
    fun `blank input returns empty map`() {
        assertTrue(ToolArgsParser.parse(null).isEmpty())
        assertTrue(ToolArgsParser.parse("").isEmpty())
        assertTrue(ToolArgsParser.parse("   ").isEmpty())
    }

    @Test
    fun `non-object input returns empty map`() {
        // 字符串/数组/裸值都不是对象，返回空 map（不抛异常）
        assertTrue(ToolArgsParser.parse(""""hello"""").isEmpty())
        assertTrue(ToolArgsParser.parse("[1,2,3]").isEmpty())
    }

    @Test
    fun `truncated unclosed json returns empty map instead of dropping fields`() {
        // 未闭合片段：{"query":"xxx 缺右括号
        // 旧的拆段合并会静默返回部分字段，现在严格解析失败返回空 map，
        // 让 execWebSearch 返回"缺少 query"提示，触发 LLM 重新生成完整参数。
        val args = ToolArgsParser.parse("""{"query":"北京天气""")
        assertTrue("truncated JSON must not return partial fields, got $args", args.isEmpty())
    }

    @Test
    fun `concatenated json objects are not silently merged`() {
        // {"limit":20}{"query":"x"} 这种拼接串，严格解析返回空 map。
        // 拼接的根因已在 OpenAIProvider.ToolCallArgsAccumulator 修复，
        // 到达这里的 arguments 应该已是单个对象；这里只做防御性验证。
        val args = ToolArgsParser.parse("""{"limit":20}{"query":"x"}""")
        assertTrue(args.isEmpty())
    }

    @Test
    fun `boolean and null values handled`() {
        val args = ToolArgsParser.parse("""{"flag":true,"missing":null}""")
        assertEquals("true", args["flag"])
        // JsonNull.content 是字符串 "null"
        assertEquals("null", args["missing"])
    }
}
