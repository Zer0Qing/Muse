package io.zer0.memory.deep

import androidx.test.core.app.ApplicationProvider
import io.zer0.ai.core.Model
import io.zer0.memory.fact.FactDbProvider
import io.zer0.memory.llm.MemoryLlmClient
import io.zer0.memory.summary.SessionSummaryManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Phase 2.3: [DeepMemoryProcessor.parseFactExtractionResult] 单元测试。
 *
 * 验证 LLM 输出容错解析逻辑:
 *  - 纯 JSON 数组 → 正确解析
 *  - 带 ```json 围栏 → 自动剥离
 *  - 带 thinking 块 → 自动剥离
 *  - JSON 嵌入文本 → 用括号深度状态机提取
 *  - 空字符串/无 JSON → 返回空列表(不抛异常)
 *  - 字段缺失 → 用默认值
 *  - importance 越界 → coerceIn(0, 2)
 *
 * 注意: 此测试只验证 [parseFactExtractionResult] 的字符串清洗 + JSON 解析逻辑,不调用 LLM。
 * 用 Robolectric 提供 Context 以构造 FactDbProvider(本测试不调用 FactDb 任何方法)。
 */
@RunWith(RobolectricTestRunner::class)
class DeepMemoryProcessorParseTest {

    private lateinit var processor: DeepMemoryProcessor

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val factDbProvider = FactDbProvider(context)
        // Phase 2.3: 修正 fake —— MemoryLlmClient 接口只有 callText, 不存在 complete/streamChat
        val fakeLlmClient = object : MemoryLlmClient {
            override suspend fun callText(
                systemPrompt: String,
                userContent: String,
                model: Model?,
                temperature: Float,
                maxTokens: Int,
                timeoutMs: Long,
            ): String = error("本测试不应调用 LLM")
        }
        processor = DeepMemoryProcessor(factDbProvider, fakeLlmClient)
    }

    @Test
    fun `empty input returns empty list`() {
        assertTrue(processor.parseFactExtractionResult("", sampleSummary(), "zh-CN").isEmpty())
    }

    @Test
    fun `blank input returns empty list`() {
        assertTrue(processor.parseFactExtractionResult("   \n\t  ", sampleSummary(), "zh-CN").isEmpty())
    }

    @Test
    fun `pure json array is parsed`() {
        val raw = """[{"fact":"用户喜欢深色模式","tags":["preference"],"importance":1,"category":"preference"}]"""
        val result = processor.parseFactExtractionResult(raw, sampleSummary(), "zh-CN")

        assertEquals(1, result.size)
        assertEquals("用户喜欢深色模式", result[0].fact)
        assertEquals(listOf("preference"), result[0].tags)
        assertEquals(1, result[0].importance)
        assertEquals("preference", result[0].category)
    }

    @Test
    fun `json fence is stripped`() {
        val raw = """
            Here is the extracted facts:
            ```json
            [{"fact":"生日: 1990-05-20","tags":["identity"],"importance":2}]
            ```
        """.trimIndent()
        val result = processor.parseFactExtractionResult(raw, sampleSummary(), "zh-CN")

        assertEquals(1, result.size)
        assertEquals("生日: 1990-05-20", result[0].fact)
        assertEquals(2, result[0].importance)
    }

    @Test
    fun `thinking block is stripped`() {
        val raw = """
            <thinking>
            Let me analyze the conversation...
            </thinking>
            [{"fact":"用户提及偏好","tags":[],"importance":0}]
        """.trimIndent()
        val result = processor.parseFactExtractionResult(raw, sampleSummary(), "zh-CN")

        assertEquals(1, result.size)
        assertEquals("用户提及偏好", result[0].fact)
    }

    @Test
    fun `json embedded in text is extracted via bracket depth state machine`() {
        val raw = """
            分析完成,提取到以下事实:
            Result: [{"fact":"用户喜欢 Kotlin","tags":["preference"],"importance":1}]
            Done.
        """.trimIndent()
        val result = processor.parseFactExtractionResult(raw, sampleSummary(), "zh-CN")

        assertEquals(1, result.size)
        assertEquals("用户喜欢 Kotlin", result[0].fact)
    }

    @Test
    fun `invalid json returns empty list without throwing`() {
        val raw = """```json
            [{ this is not valid json ]
            ```"""
        val result = processor.parseFactExtractionResult(raw, sampleSummary(), "zh-CN")

        assertTrue("JSON 解析失败应返回空列表而非抛异常", result.isEmpty())
    }

    @Test
    fun `missing fields use defaults`() {
        val raw = """[{"fact":"最小化字段"}]"""
        val result = processor.parseFactExtractionResult(raw, sampleSummary(), "zh-CN")

        assertEquals(1, result.size)
        val fact = result[0]
        assertEquals("最小化字段", fact.fact)
        assertEquals(emptyList<String>(), fact.tags)
        assertEquals(0, fact.importance)
        assertEquals("general", fact.category)
        assertEquals("inferred", fact.source)
        assertEquals(0.7f, fact.confidence, 0.001f)
    }

    @Test
    fun `user_explicit source gets higher default confidence`() {
        val raw = """[{"fact":"用户明确告知","source":"user_explicit"}]"""
        val result = processor.parseFactExtractionResult(raw, sampleSummary(), "zh-CN")

        assertEquals(1, result.size)
        assertEquals(1.0f, result[0].confidence, 0.001f)
    }

    @Test
    fun `importance is clamped to 0-2 range`() {
        val raw = """[
            {"fact":"过低 importance","importance":-5},
            {"fact":"过高 importance","importance":99}
        ]"""
        val result = processor.parseFactExtractionResult(raw, sampleSummary(), "zh-CN")

        assertEquals(2, result.size)
        assertEquals("负值应 coerce 到 0", 0, result[0].importance)
        assertEquals("超过 2 应 coerce 到 2", 2, result[1].importance)
    }

    @Test
    fun `sessionId is propagated from summary`() {
        val raw = """[{"fact":"测试 sessionId 传递"}]"""
        val summary = sampleSummary(sessionId = "session-xyz-123")
        val result = processor.parseFactExtractionResult(raw, summary, "zh-CN")

        assertEquals(1, result.size)
        assertEquals("sessionId 应从 summary 透传到 Fact", "session-xyz-123", result[0].sessionId)
    }

    @Test
    fun `multiple facts are parsed in order`() {
        val raw = """[
            {"fact":"事实 A","importance":0},
            {"fact":"事实 B","importance":1},
            {"fact":"事实 C","importance":2,"category":"identity"}
        ]"""
        val result = processor.parseFactExtractionResult(raw, sampleSummary(), "zh-CN")

        assertEquals(3, result.size)
        assertEquals("事实 A", result[0].fact)
        assertEquals("事实 B", result[1].fact)
        assertEquals("事实 C", result[2].fact)
        assertEquals("general", result[0].category)
        assertEquals("identity", result[2].category)
    }

    // ── Phase 2.3.7 补充:更细粒度的边界 case ─────────────────────────────

    @Test
    fun `confidence is clamped to 0-1 range when explicitly provided`() {
        // confidence 显式提供但越界时,应被 coerceIn(0f, 1f) 截断
        val raw = """[
            {"fact":"过高 confidence","confidence":1.5},
            {"fact":"过低 confidence","confidence":-0.3},
            {"fact":"正常 confidence","confidence":0.85}
        ]"""
        val result = processor.parseFactExtractionResult(raw, sampleSummary(), "zh-CN")

        assertEquals(3, result.size)
        assertEquals("1.5 应截到 1.0", 1.0f, result[0].confidence, 0.001f)
        assertEquals("-0.3 应截到 0.0", 0.0f, result[1].confidence, 0.001f)
        assertEquals("0.85 应保留原值", 0.85f, result[2].confidence, 0.001f)
    }

    @Test
    fun `blank category falls back to general`() {
        // category 显式提供空字符串时,应回退到默认 "general"
        val raw = """[{"fact":"空 category","category":""}]"""
        val result = processor.parseFactExtractionResult(raw, sampleSummary(), "zh-CN")

        assertEquals(1, result.size)
        assertEquals("空字符串 category 应回退到 general", "general", result[0].category)
    }

    @Test
    fun `blank source falls back to inferred`() {
        // source 显式提供空字符串时,应回退到默认 "inferred"
        val raw = """[{"fact":"空 source","source":""}]"""
        val result = processor.parseFactExtractionResult(raw, sampleSummary(), "zh-CN")

        assertEquals(1, result.size)
        assertEquals("空字符串 source 应回退到 inferred", "inferred", result[0].source)
        // 空 source 不应触发 user_explicit 的 1.0 confidence,而是 0.7
        assertEquals("空 source 应回退到 inferred 默认 confidence", 0.7f, result[0].confidence, 0.001f)
    }

    @Test
    fun `tags default to empty list when not provided`() {
        // tags 字段缺失时应为空列表(DTO 默认值)
        val raw = """[{"fact":"无 tags 字段"}]"""
        val result = processor.parseFactExtractionResult(raw, sampleSummary(), "zh-CN")

        assertEquals(1, result.size)
        assertEquals("缺失 tags 字段应为空列表", emptyList<String>(), result[0].tags)
    }

    @Test
    fun `expiresAt and lastConfirmedAt are propagated when provided`() {
        // 这两个可空字段提供时应原样透传
        val raw = """[{
            "fact":"时间敏感事实",
            "expiresAt":"2027-12-31T23:59:59Z",
            "lastConfirmedAt":"2026-07-28T10:00:00Z"
        }]"""
        val result = processor.parseFactExtractionResult(raw, sampleSummary(), "zh-CN")

        assertEquals(1, result.size)
        assertEquals("2027-12-31T23:59:59Z", result[0].expiresAt)
        assertEquals("2026-07-28T10:00:00Z", result[0].lastConfirmedAt)
    }

    @Test
    fun `text with no opening bracket returns empty list`() {
        // 无 [ 字符时 findJsonArrayCandidate 返回 null
        val raw = """这是一段完全没有 JSON 数组的文本,只有普通文字。"""
        val result = processor.parseFactExtractionResult(raw, sampleSummary(), "zh-CN")

        assertTrue("无 [ 字符的文本应返回空列表", result.isEmpty())
    }

    @Test
    fun `unterminated json array returns empty list`() {
        // 括号深度状态机扫描到结尾仍未闭合 → 返回 null → 空列表
        val raw = """分析结果:[{"fact":"未闭合的 JSON","tags":["test"]"""
        val result = processor.parseFactExtractionResult(raw, sampleSummary(), "zh-CN")

        assertTrue("未闭合的 JSON 数组应返回空列表", result.isEmpty())
    }

    @Test
    fun `brackets inside json strings do not affect depth tracking`() {
        // JSON 字符串内的 [ ] 不应影响括号深度状态机
        val raw = """结果:[{"fact":"包含 [ 和 ] 字符的字符串","tags":["a[b]c"]}] 完成"""
        val result = processor.parseFactExtractionResult(raw, sampleSummary(), "zh-CN")

        assertEquals(1, result.size)
        assertEquals("包含 [ 和 ] 字符的字符串", result[0].fact)
        assertEquals(listOf("a[b]c"), result[0].tags)
    }

    @Test
    fun `escaped quotes inside json strings are handled`() {
        // JSON 字符串内含转义双引号 \" 时,findJsonArrayCandidate 状态机
        // 应正确处理转义,不因 \" 错误地切换 inString 状态导致括号深度计算错误
        // 注:raw 不以 [ 开头,强制走 findJsonArrayCandidate 路径
        val raw = """提取结果:[{"fact":"用户说\"你好\"","tags":[]}] 结束"""
        val result = processor.parseFactExtractionResult(raw, sampleSummary(), "zh-CN")

        assertEquals(1, result.size)
        assertEquals("用户说\"你好\"", result[0].fact)
    }

    @Test
    fun `thinking tag without closing tag is stripped gracefully`() {
        // <thinking> 未闭合时,正则不匹配,但流程不应崩溃
        val raw = """<thinking>这段思考没有闭合标签
[{"fact":"仍然能提取的事实","importance":0}]"""
        val result = processor.parseFactExtractionResult(raw, sampleSummary(), "zh-CN")

        // 未闭合的 <thinking> 不被正则匹配,但后续 findJsonArrayCandidate 仍能提取 JSON
        assertEquals(
            "未闭合 thinking 标签时,JSON 数组仍应通过状态机提取",
            1,
            result.size,
        )
        assertEquals("仍然能提取的事实", result[0].fact)
    }

    private fun sampleSummary(sessionId: String = "test-session"): SessionSummaryManager.SummaryData {
        return SessionSummaryManager.SummaryData(
            sessionId = sessionId,
            createdAt = "2026-07-28T10:00:00Z",
            updatedAt = "2026-07-28T11:00:00Z",
            summary = "用户讨论了偏好设置",
            messageCount = 10,
            sourceTimeRange = null,
        )
    }
}
