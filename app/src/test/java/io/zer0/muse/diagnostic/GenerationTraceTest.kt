package io.zer0.muse.diagnostic

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.zer0.common.AppJson
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * GenerationTrace 的核心行为验证：
 *  - 环形缓冲容量上限
 *  - sessionId 哈希脱敏（不含原文）
 *  - JSONL 落盘格式合法
 *  - 任何字段不含正文（红线）
 */
@RunWith(RobolectricTestRunner::class)
class GenerationTraceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun summary(
        sessionId: String = "s1",
        modelId: String = "test-model",
        outcome: String = "completed",
        contentChars: Int = 50,
        reasoningChars: Int = 0,
    ): GenerationTrace.GenerationSummary = GenerationTrace.GenerationSummary(
        sessionId = sessionId,
        modelId = modelId,
        providerType = "openai",
        outcome = outcome,
        elapsedMs = 1000,
        ttftMs = 100,
        contentChars = contentChars,
        reasoningChars = reasoningChars,
        toolCallCount = 0,
        round = 1,
        uiFlushCount = 3,
    )

    @Before
    fun setUp() {
        GenerationTrace.init(context)
        GenerationTrace.clearForTest()
    }

    @After
    fun tearDown() {
        GenerationTrace.clearForTest()
    }

    @Test
    fun `ring buffer caps at RING_CAPACITY`() {
        repeat(GenerationTrace.RING_CAPACITY + 5) { i ->
            GenerationTrace.record(summary(sessionId = "session-$i"))
        }
        assertEquals(GenerationTrace.RING_CAPACITY, GenerationTrace.snapshot().size)
    }

    @Test
    fun `sessionId is hashed not stored raw`() {
        val rawSessionId = "very-secret-session-id-12345"
        GenerationTrace.record(summary(sessionId = rawSessionId))
        val rec = GenerationTrace.snapshot().single()
        assertEquals(8, rec.sessionHash.length)
        assertTrue(rec.sessionHash.all { it in '0'..'9' || it in 'a'..'f' })
        assertTrue(rec.sessionHash != rawSessionId)
    }

    @Test
    fun `record fields contain no message content`() {
        GenerationTrace.record(summary(modelId = "gpt-4", outcome = "failed", reasoningChars = 10))
        val json = AppJson.encodeToString(GenerationTrace.TraceRecord.serializer(), GenerationTrace.snapshot().single())
        val obj = AppJson.parseToJsonElement(json).jsonObject
        // 红线断言：序列化结果里没有任何正文/prompt/content 字段
        val forbiddenKeys = setOf("content", "prompt", "message", "text", "body", "apikey")
        obj.keys.forEach { key ->
            assertTrue("字段 '$key' 不允许出现在 trace 记录中", key.lowercase() !in forbiddenKeys)
        }
    }

    @Test
    fun `trace file is appended on record`() {
        GenerationTrace.record(summary())
        val file = GenerationTrace.traceFileOrNull()
        assertTrue("trace 文件应已落盘", file != null && file.length() > 0)
        file!!.readLines().forEach { line ->
            if (line.isBlank()) return@forEach
            val parsed = AppJson.parseToJsonElement(line).jsonObject
            assertTrue(parsed.containsKey("outcome"))
            assertTrue(parsed.containsKey("sessionHash"))
        }
    }

    @Test
    fun `noteStreamEnd classification is captured`() {
        GenerationTrace.noteStreamEnd("closed_no_finish", null)
        GenerationTrace.record(summary())
        val rec = GenerationTrace.snapshot().single()
        assertEquals("closed_no_finish", rec.streamEndKind)
        assertEquals("", rec.finishReason)
    }
}
