package io.zer0.ai.openai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 针对 [ToolCallArgsAccumulator] 的单测。
 *
 * 复现 DeepSeek V4 等模型流式返回同一 tool call 时把 arguments 拆成多个完整 JSON 对象
 * （{"limit":20}{"query":"..."}）的问题，验证累积器合并字段而非拼接字符串。
 */
class ToolCallArgsAccumulatorTest {

    @Test
    fun `string fragments accumulate into valid json`() {
        val acc = ToolCallArgsAccumulator()
        acc.append("{\"lim")
        acc.append("it\":20}")
        assertEquals("""{"limit":20}""", acc.current())
        assertTrue(acc.isValidJson())
    }

    @Test
    fun `concatenated json objects merge fields instead of string concat`() {
        val acc = ToolCallArgsAccumulator()
        acc.append("""{"limit":20}""")
        acc.append("""{"query":"天气"}""")
        val merged = acc.current()
        // 必须是单个合法 JSON 对象，两个字段都在
        assertTrue("got: $merged", acc.isValidJson())
        assertTrue(merged.contains("\"limit\""))
        assertTrue(merged.contains("\"query\""))
        assertFalse(merged.contains("}{"))
    }

    @Test
    fun `three concatenated objects all merge`() {
        val acc = ToolCallArgsAccumulator()
        acc.append("""{"a":1}""")
        acc.append("""{"b":2}""")
        acc.append("""{"c":3}""")
        assertTrue(acc.isValidJson())
        val raw = acc.current()
        assertTrue(raw.contains("\"a\""))
        assertTrue(raw.contains("\"b\""))
        assertTrue(raw.contains("\"c\""))
    }

    @Test
    fun `partial fragment then completed object merges`() {
        // 首片是未闭合片段，次片补齐剩余（正常字符串增量，切在引号外）
        val acc = ToolCallArgsAccumulator()
        acc.append("""{"query":"""")
        acc.append("""北京天气"}""")
        assertEquals("""{"query":"北京天气"}""", acc.current())
        assertTrue(acc.isValidJson())
    }

    @Test
    fun `truncated final fragment reports invalid but keeps prefix`() {
        // 最后一片被截断（{"query":"xxx 缺右括号），isValidJson=false
        val acc = ToolCallArgsAccumulator()
        acc.append("""{"query":"北京天气""")
        assertFalse(acc.isValidJson())
        // 不抛异常，保留前缀供上层判断
        assertTrue(acc.current().startsWith("{"))
    }

    @Test
    fun `later object field overrides earlier same key`() {
        val acc = ToolCallArgsAccumulator()
        acc.append("""{"query":"旧","limit":5}""")
        acc.append("""{"query":"新"}""")
        val raw = acc.current()
        assertTrue(acc.isValidJson())
        // query 以后到的为准
        assertTrue(raw.contains("\"query\":\"新\""))
        assertTrue(raw.contains("\"limit\""))
    }

    @Test
    fun `nested object value survives merge`() {
        val acc = ToolCallArgsAccumulator()
        acc.append("""{"opts":{"a":1}}""")
        acc.append("""{"query":"x"}""")
        assertTrue(acc.isValidJson())
        assertTrue(acc.current().contains("\"opts\""))
        assertTrue(acc.current().contains("\"query\""))
    }

    @Test
    fun `empty and blank appends are no-ops`() {
        val acc = ToolCallArgsAccumulator()
        acc.append("")
        acc.append("   ")
        assertEquals("", acc.current())
        // 空累积器不算合法 JSON，但也不抛
        assertFalse(acc.isValidJson())
    }

    @Test
    fun `single complete object passes through`() {
        val acc = ToolCallArgsAccumulator()
        acc.append("""{"query":"天气","max_results":5}""")
        assertTrue(acc.isValidJson())
        assertEquals("""{"query":"天气","max_results":5}""", acc.current())
    }

    @Test
    fun `hasMergedObjects true after concatenated object fragments`() {
        val acc = ToolCallArgsAccumulator()
        // 正常字符串增量阶段不应标记合并
        acc.append("""{"query":""")
        assertFalse(acc.hasMergedObjects())
        acc.append("""x"}""")
        assertFalse(acc.hasMergedObjects())
        // 两个完整对象分片才标记
        val acc2 = ToolCallArgsAccumulator()
        acc2.append("""{"limit":5}""")
        acc2.append("""{"query":"x"}""")
        assertTrue(acc2.hasMergedObjects())
    }
}
