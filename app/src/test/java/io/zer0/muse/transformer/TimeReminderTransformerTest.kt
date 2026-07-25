package io.zer0.muse.transformer

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * TimeReminderTransformer 单元测试。
 *
 * 纯函数测试,无外部依赖。
 */
class TimeReminderTransformerTest {

    private val transformer = TimeReminderTransformer()

    @Test
    fun `enabled by default inserts time message at head`() = runTest {
        val messages = listOf(
            UIMessage(role = MessageRole.USER, content = "现在几点？"),
        )
        val context = TransformContext()
        val result = transformer.transform(messages, context)

        assertEquals(2, result.size)
        assertEquals(MessageRole.SYSTEM, result[0].role)
        assertTrue(result[0].content.contains("当前时间"))
        assertTrue(result[0].content.contains("时区"))
        assertEquals(messages[0], result[1])
    }

    @Test
    fun `disabled when time_reminder_enabled is false returns original`() = runTest {
        val messages = listOf(
            UIMessage(role = MessageRole.USER, content = "现在几点？"),
        )
        val context = TransformContext(extras = mapOf("time_reminder_enabled" to false))
        val result = transformer.transform(messages, context)

        assertEquals(1, result.size)
        assertEquals(messages, result)
    }

    @Test
    fun `uses specified timezone when provided`() = runTest {
        val messages = listOf(
            UIMessage(role = MessageRole.USER, content = "现在几点？"),
        )
        val context = TransformContext(
            extras = mapOf("time_reminder_timezone" to "America/New_York"),
        )
        val result = transformer.transform(messages, context)

        assertEquals(2, result.size)
        assertTrue(result[0].content.contains("America/New_York"))
        assertTrue(result[0].content.contains("-04:00") || result[0].content.contains("-05:00") || 
            result[0].content.contains("-0"))
    }

    @Test
    fun `time format contains expected components`() = runTest {
        val msg = UIMessage(role = MessageRole.USER, content = "test")
        val result = transformer.transform(listOf(msg), TransformContext())
        val timeContent = result[0].content

        // Should contain date components
        assertTrue(timeContent.contains("-") || timeContent.contains("年"))
        // 应包含中文星期
        assertTrue(timeContent.contains("星期") || timeContent.contains("周") || timeContent.contains("礼拜"))
        // Should contain timezone info
        assertTrue(timeContent.contains("时区"))
    }

    @Test
    fun `time format includes timezone offset`() = runTest {
        val msg = UIMessage(role = MessageRole.USER, content = "test")
        val result = transformer.transform(listOf(msg), TransformContext())
        val timeContent = result[0].content

        // Should have timezone offset like +08:00
        val hasOffset = Regex("[+-]\\d{2}:\\d{2}").containsMatchIn(timeContent)
        assertTrue("Expected timezone offset in: $timeContent", hasOffset)
    }

    @Test
    fun `multiple messages - time only prepended once`() = runTest {
        val messages = listOf(
            UIMessage(role = MessageRole.SYSTEM, content = "你是一个助手"),
            UIMessage(role = MessageRole.USER, content = "你好"),
            UIMessage(role = MessageRole.ASSISTANT, content = "你好！"),
        )
        val result = transformer.transform(messages, TransformContext())

        assertEquals(4, result.size)
        assertEquals(MessageRole.SYSTEM, result[0].role)
        assertTrue(result[0].content.contains("当前时间"))
        // 原始消息按顺序保留
        assertEquals(messages[0], result[1])
        assertEquals(messages[1], result[2])
        assertEquals(messages[2], result[3])
    }
}
