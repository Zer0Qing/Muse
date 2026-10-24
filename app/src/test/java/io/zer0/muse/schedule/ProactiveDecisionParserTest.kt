package io.zer0.muse.schedule

import io.zer0.muse.data.ProactiveMessageConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用户反馈「一条主动消息都不发了」的核心回归：
 * 决策阶段 LLM 的 shouldSend 缺失 / JSON 解析失败 / 明确拒绝，三者必须可区分。
 *
 * 旧行为：`shouldSend: Boolean = false` 把「模型说不发」和「字段没写」压成同一个值，
 * 且不回落到任何旧逻辑 —— 模型一旦漏字段，用户就一条都收不到，日志里还看不出来。
 *
 * 这里锁住新行为：
 *  - 字段缺失 → shouldSend 保持 null（交由调用方按评分结果放行），日志码 missing
 *  - 解析失败 → shouldSend 保持 null，日志码 malformed（与缺失不同）
 *  - 截断但能认出 true → 宽松接受为 true（B-11 保留）
 *  - 明确 false → 原样 false，不被改写
 */
class ProactiveDecisionParserTest {

    // ── 字段缺失 ────────────────────────────────────────────────────

    /** JSON 合法但没有 shouldSend：不能当成 false。 */
    @Test
    fun `missing shouldSend stays null instead of defaulting to false`() {
        val result = ProactiveDecisionParser().parse("""{"reason":"没什么想说的","scenario":"greeting"}""")

        assertEquals(DecisionParseOutcome.OK, result.outcome)
        assertNull("字段缺失必须是 null，不能塌成 false", result.decision.shouldSend)
    }

    /** 字段缺失时日志码必须是 missing，不能和解析失败混在一起。 */
    @Test
    fun `missing shouldSend emits missing log code`() {
        val logs = mutableListOf<String>()
        ProactiveDecisionParser { logs += it }.parse("""{"reason":"x","nextIntervalMinutes":60}""")

        assertEquals(listOf("parse_missing_should_send"), logs)
    }

    /** 明确的 false 必须保留，不能被冒充成「缺失」。 */
    @Test
    fun `explicit false is preserved`() {
        val logs = mutableListOf<String>()
        val result = ProactiveDecisionParser { logs += it }
            .parse("""{"shouldSend":false,"reason":"用户在忙"}""")

        assertEquals(false, result.decision.shouldSend)
        assertEquals("用户在忙", result.decision.reason)
        assertTrue("明确 false 不应产生 missing/malformed 日志码", logs.isEmpty())
    }

    /** 明确的 true 原样保留。 */
    @Test
    fun `explicit true is preserved`() {
        val result = ProactiveDecisionParser().parse("""{"shouldSend":true,"reason":"该问候了"}""")

        assertEquals(true, result.decision.shouldSend)
        assertEquals("该问候了", result.decision.reason)
        assertEquals(DecisionParseOutcome.OK, result.outcome)
    }

    // ── 解析失败 ────────────────────────────────────────────────────

    /** 完全不是 JSON：shouldSend 留 null（没人能代表模型说不发）。 */
    @Test
    fun `malformed output stays null and does not silently become false`() {
        val logs = mutableListOf<String>()
        val result = ProactiveDecisionParser { logs += it }.parse("模型跑飞了，这里没有任何 JSON")

        assertEquals(DecisionParseOutcome.MALFORMED, result.outcome)
        assertNull(result.decision.shouldSend)
        assertEquals(listOf("parse_malformed"), logs)
    }

    /**
     * 关键：解析失败与字段缺失必须产生**不同**日志。
     * 用户的诉求原话是「不许只加日志而行为不变」—— 这里两者行为也不同：
     * 一个走 malformed 码，一个走 missing 码，调用方据此可以区分病灶。
     */
    @Test
    fun `malformed and missing produce different log codes`() {
        val malformedLogs = mutableListOf<String>()
        ProactiveDecisionParser { malformedLogs += it }.parse("完全不是 JSON")

        val missingLogs = mutableListOf<String>()
        ProactiveDecisionParser { missingLogs += it }.parse("""{"reason":"没有表述该不该发"}""")

        assertTrue(malformedLogs.isNotEmpty())
        assertTrue(missingLogs.isNotEmpty())
        assertNotEqualsCode(malformedLogs, missingLogs)
    }

    private fun assertNotEqualsCode(a: List<String>, b: List<String>) {
        assertTrue("解析失败的日志码与字段缺失的日志码必须不同，实际 $a vs $b", a != b)
    }

    /** 空串按解析失败处理。 */
    @Test
    fun `blank output is malformed`() {
        assertEquals(DecisionParseOutcome.MALFORMED, ProactiveDecisionParser().parse("").outcome)
    }

    // ── B-11 截断兼容 ──────────────────────────────────────────────

    /** 输出被截断但仍能认出 shouldSend=true → 宽松接受，不要漏发。 */
    @Test
    fun `truncated json with shouldSend true is leniently accepted`() {
        val logs = mutableListOf<String>()
        val result = ProactiveDecisionParser { logs += it }
            .parse("""{"shouldSend": true, "reason": "想问问你今天怎么样""")

        assertEquals(DecisionParseOutcome.TRUNCATED_LENIENT_TRUE, result.outcome)
        assertEquals(true, result.decision.shouldSend)
        assertEquals(listOf("parse_truncated_lenient_true"), logs)
    }

    /** 截断但 shouldSend=false → 不能因为宽松策略变成发送。 */
    @Test
    fun `truncated json with shouldSend false does not become send`() {
        val result = ProactiveDecisionParser()
            .parse("""{"shouldSend": false, "reason": "用户在忙""")

        assertEquals(DecisionParseOutcome.MALFORMED, result.outcome)
        assertNull(result.decision.shouldSend)
    }

    // ── 与 markdown / think 标签共存 ───────────────────────────────

    /** 被 ```json 包裹也要能解析。 */
    @Test
    fun `markdown fenced json is parsed`() {
        val raw = """
            ```json
            {"shouldSend": true, "reason": "该问了", "scenario": "reminder"}
            ```
        """.trimIndent()
        val result = ProactiveDecisionParser().parse(raw)

        assertEquals(true, result.decision.shouldSend)
        assertEquals("reminder", result.decision.scenario)
    }

    // ── nextIntervalMinutes 缺失（第 3 项的解析侧） ─────────────────

    /** aiPaced 档下模型漏写 nextIntervalMinutes：解析不报错，但值必须是 0（交由 runner 记日志）。 */
    @Test
    fun `missing nextIntervalMinutes decodes as zero`() {
        val result = ProactiveDecisionParser().parse("""{"shouldSend":true,"reason":"x"}""")
        assertEquals(0, result.decision.nextIntervalMinutes)
    }

    /** 模型给了非法负值：原样保留，由 resolveBaseIntervalMs 归类为 INVALID。 */
    @Test
    fun `negative nextIntervalMinutes is preserved for later classification`() {
        val result = ProactiveDecisionParser().parse("""{"shouldSend":true,"nextIntervalMinutes":-30}""")
        assertEquals(-30, result.decision.nextIntervalMinutes)
        assertNotNull(result.decision.shouldSend)
    }

    // ── 间隔来源分类（第 3 项 runner 侧） ───────────────────────────

    private val fixedIntervalMs = ProactiveMessageConfig().intervalMinutes * 60_000L

    @Test
    fun `aiPaced off uses fixed interval`() {
        val r = ProactiveMessageRunner.resolveBaseIntervalMs(
            aiPaced = false,
            nextIntervalMinutes = 30,
            fixedBaseIntervalMs = fixedIntervalMs,
        )
        assertEquals(IntervalSource.FIXED_NOT_PACED, r.source)
        assertEquals(fixedIntervalMs, r.baseIntervalMs)
    }

    @Test
    fun `aiPaced on with valid value uses model interval`() {
        val r = ProactiveMessageRunner.resolveBaseIntervalMs(
            aiPaced = true,
            nextIntervalMinutes = 30,
            fixedBaseIntervalMs = fixedIntervalMs,
        )
        assertEquals(IntervalSource.AI_PACED, r.source)
        assertEquals(30 * 60_000L, r.baseIntervalMs)
        assertEquals(30, r.requestedMinutes)
    }

    /** 模型漏写字段：回落固定间隔（设计意图），但来源必须标成 MISSING 以便日志可见。 */
    @Test
    fun `aiPaced on with missing value falls back and is labelled`() {
        val r = ProactiveMessageRunner.resolveBaseIntervalMs(
            aiPaced = true,
            nextIntervalMinutes = 0,
            fixedBaseIntervalMs = fixedIntervalMs,
        )
        assertEquals(IntervalSource.FIXED_MISSING_FROM_MODEL, r.source)
        assertEquals("回落应使用配置的固定间隔", fixedIntervalMs, r.baseIntervalMs)
    }

    /** 模型给了非法负值：同样是回落，但归类为 INVALID，与 MISSING 分开。 */
    @Test
    fun `aiPaced on with invalid value is labelled differently from missing`() {
        val invalid = ProactiveMessageRunner.resolveBaseIntervalMs(true, -10, fixedIntervalMs)
        val missing = ProactiveMessageRunner.resolveBaseIntervalMs(true, 0, fixedIntervalMs)

        assertEquals(IntervalSource.FIXED_INVALID_FROM_MODEL, invalid.source)
        assertEquals(IntervalSource.FIXED_MISSING_FROM_MODEL, missing.source)
        assertEquals(fixedIntervalMs, invalid.baseIntervalMs)
        assertEquals(fixedIntervalMs, missing.baseIntervalMs)
    }

    /** 超出上限要钳到 24h，不能让模型把间隔设成一整年。 */
    @Test
    fun `out of range interval is clamped`() {
        val huge = ProactiveMessageRunner.resolveBaseIntervalMs(true, 999_999, fixedIntervalMs)
        assertEquals(
            ProactiveMessageConfig.MAX_AI_INTERVAL_MINUTES * 60_000L,
            huge.baseIntervalMs,
        )

        val tiny = ProactiveMessageRunner.resolveBaseIntervalMs(true, 1, fixedIntervalMs)
        assertEquals(
            ProactiveMessageConfig.MIN_AI_INTERVAL_MINUTES * 60_000L,
            tiny.baseIntervalMs,
        )
    }
}
