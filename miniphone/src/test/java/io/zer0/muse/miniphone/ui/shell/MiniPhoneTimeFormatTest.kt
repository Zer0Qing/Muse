package io.zer0.muse.miniphone.ui.shell

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 时间文本：相对时间（朋友圈、评论、消息列表共用）与状态栏、聊天分隔这两处另有口径的格式。
 *
 * 这些句子已经进资源（含单复数），日期部分交给平台格式化器，所以测试跑在 Robolectric 下、
 * 按默认 en-US 断言——中文界面走的是同一套代码，只是取的是另一份资源。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MiniPhoneTimeFormatTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val zone: ZoneId = ZoneId.systemDefault()

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    private val now = at(2026, 10, 29, 21, 51)

    @Test
    fun `没有时间戳就留白`() {
        assertEquals("", relativeTimeOf(context, epochMs = 0L, now = now))
        assertEquals("", MiniPhoneTimeFormat.separator(context, epochMs = 0L, now = now))
    }

    @Test
    fun `一分钟内都算刚刚`() {
        assertEquals("Just now", relativeTimeOf(context, epochMs = now - 30_000L, now = now))
        assertEquals("差几秒也算刚刚", "Just now", relativeTimeOf(context, epochMs = now - 59_999L, now = now))
    }

    @Test
    fun `分钟数走单复数`() {
        assertEquals("1 minute ago", relativeTimeOf(context, epochMs = now - 60_000L, now = now))
        assertEquals("12 minutes ago", relativeTimeOf(context, epochMs = now - 12L * 60_000L, now = now))
        assertEquals("59 minutes ago", relativeTimeOf(context, epochMs = now - 59L * 60_000L, now = now))
    }

    @Test
    fun `同一天按小时数`() {
        assertEquals("3 hours ago", relativeTimeOf(context, epochMs = at(2026, 10, 29, 18, 0), now = now))
        assertEquals("跨过整点也按小时算", "11 hours ago", relativeTimeOf(context, epochMs = at(2026, 10, 29, 10, 0), now = now))
    }

    @Test
    fun `昨天带上当时的时刻`() {
        assertEquals("Yesterday 21:30", relativeTimeOf(context, epochMs = at(2026, 10, 28, 21, 30), now = now))
    }

    @Test
    fun `更早只给日期与时刻且日期部分由平台格式化`() {
        val text = relativeTimeOf(context, epochMs = at(2026, 10, 12, 9, 5), now = now)
        assertTrue("该带上时刻，实际：$text", text.contains("09:05"))
        assertFalse("不该再写昨天", text.contains("Yesterday"))
        assertTrue("该有日期部分，实际：$text", text.removeSuffix("09:05").isNotBlank())
    }

    @Test
    fun `跨年把年份交给格式化器`() {
        val text = relativeTimeOf(context, epochMs = at(2025, 10, 12, 9, 5), now = now)
        assertTrue("跨年要能看出年份，实际：$text", text.contains("2025"))
    }

    @Test
    fun `分隔行同一天只给时刻`() {
        assertEquals("21:30", MiniPhoneTimeFormat.separator(context, at(2026, 10, 29, 21, 30), now = now))
        assertEquals("Yesterday 21:30", MiniPhoneTimeFormat.separator(context, at(2026, 10, 28, 21, 30), now = now))
        val older = MiniPhoneTimeFormat.separator(context, at(2026, 10, 12, 9, 5), now = now)
        assertTrue("分隔行也要有日期，实际：$older", older.contains("09:05") && older.removeSuffix(" 09:05").isNotBlank())
    }
}
