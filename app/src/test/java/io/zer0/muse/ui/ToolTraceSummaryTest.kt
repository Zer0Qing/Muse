package io.zer0.muse.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolTraceSummaryTest {

    @Test
    fun `groups records by tool name while preserving first-seen group order`() {
        val summaries = summarizeToolTrace(
            listOf(
                record("search", timestamp = 10, success = true, result = "first"),
                record("write", timestamp = 20, success = true, result = "saved"),
                record("search", timestamp = 30, success = true, result = "latest"),
            ),
        )

        assertEquals(listOf("search", "write"), summaries.map { it.toolName })
        assertEquals(2, summaries.first().totalCount)
        assertEquals(listOf("first", "latest"), summaries.first().records.map { it.result })
    }

    @Test
    fun `counts success failure and running records and expands attention groups`() {
        val summaries = summarizeToolTrace(
            listOf(
                record("search", timestamp = 10, success = true, result = "ok"),
                record("search", timestamp = 20, success = false, result = "error"),
                record("search", timestamp = 30, success = false, result = ""),
                record("write", timestamp = 40, success = true, result = "saved"),
            ),
        )

        val search = summaries.first()
        assertEquals(1, search.successCount)
        assertEquals(1, search.failureCount)
        assertEquals(1, search.runningCount)
        assertTrue(search.shouldExpandByDefault)
        // I18N 契约: 状态文案在 UI 走 stringResource;纯函数/无 res 时返回空串而非硬编码中文
        assertEquals("", search.latestSummary)

        val write = summaries.last()
        assertFalse(write.shouldExpandByDefault)
        assertEquals("saved", write.latestSummary)
    }

    @Test
    fun `latest summary uses newest timestamp and later input on timestamp ties`() {
        val summary = summarizeToolTrace(
            listOf(
                record("search", timestamp = 100, success = true, result = "old"),
                record("search", timestamp = 200, success = true, result = "first at latest time"),
                record("search", timestamp = 200, success = true, result = "second at latest time"),
            ),
        ).single()

        assertEquals("second at latest time", summary.latestRecord.result)
        assertEquals("second at latest time", summary.latestSummary)
    }

    @Test
    fun `normalizes latest result whitespace and truncates long previews`() {
        val result = (1..130).joinToString(" ") { "x" }
        val summary = summarizeToolTrace(
            listOf(record("search", timestamp = 1, success = true, result = "  a\n\tb  ")),
        ).single()
        val longSummary = summarizeToolTrace(
            listOf(record("search", timestamp = 1, success = true, result = result)),
        ).single().latestSummary

        assertEquals("a b", summary.latestSummary)
        assertEquals(121, longSummary.length)
        assertTrue(longSummary.endsWith("…"))
    }

    private fun record(
        toolName: String,
        timestamp: Long,
        success: Boolean,
        result: String,
    ) = ToolCallRecord(
        toolName = toolName,
        arguments = "{}",
        result = result,
        isSuccess = success,
        timestamp = timestamp,
    )
}
