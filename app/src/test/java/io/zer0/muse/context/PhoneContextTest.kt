package io.zer0.muse.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手机上下文的统一信封（对比清单 P2-02）。
 *
 * 核心是**三种"没数据"必须分开说**：没授权 / 设备不支持 / 确实没有。
 * 混成一句"没有"时，用户不知道该去开权限，还是本来就没这事。
 */
class PhoneContextTest {

    private fun envelope(
        source: PhoneContext.Source = PhoneContext.Source.CALENDAR,
        availability: PhoneContext.Availability = PhoneContext.Availability.AVAILABLE,
        windowDays: Int = 1,
        truncated: Boolean = false,
        itemCount: Int = 0,
        note: String? = null,
    ) = PhoneContext.Envelope(
        source = source,
        availability = availability,
        capturedAtMillis = 1_700_000_000_000L,
        windowDays = windowDays,
        itemCount = itemCount,
        truncated = truncated,
        note = note,
    )

    @Test
    fun `an available source lists its items under a header`() {
        val text = PhoneContext.render(envelope(), listOf("10:00 站会", "15:00 评审"))

        assertTrue(text.contains("日历"))
        assertTrue(text.contains("可用"))
        assertTrue(text.contains("最近 1 天"))
        assertTrue(text.contains("站会"))
    }

    @Test
    fun `granted but empty says there is genuinely nothing`() {
        val text = PhoneContext.render(envelope(), emptyList())

        assertTrue("要说清是真没有: $text", text.contains("确实没有"))
        assertFalse("不能暗示是权限问题: $text", text.contains("权限"))
    }

    @Test
    fun `denied is reported as a permission problem with a next step`() {
        val text = PhoneContext.render(envelope(availability = PhoneContext.Availability.DENIED), emptyList())

        assertTrue(text.contains("未授权"))
        assertTrue("要给出下一步: $text", text.contains("授权"))
        assertFalse("不能把没权限说成没数据: $text", text.contains("确实没有"))
    }

    @Test
    fun `unavailable is reported as a device limitation`() {
        val text = PhoneContext.render(envelope(availability = PhoneContext.Availability.UNAVAILABLE), emptyList())

        assertTrue(text.contains("不可用"))
        assertTrue("要说清开权限也没用: $text", text.contains("开权限也没用"))
    }

    @Test
    fun `truncation is stated with the total count`() {
        val text = PhoneContext.render(envelope(truncated = true, itemCount = 80), listOf("a", "b"))

        assertTrue(text.contains("已截断"))
        assertTrue(text.contains("80"))
    }

    @Test
    fun `the header carries the capture time and a note when present`() {
        val text = PhoneContext.render(envelope(note = "需要通知使用权"), emptyList())

        assertTrue(text.contains("1700000000000"))
        assertTrue(text.contains("需要通知使用权"))
    }

    // ── 分发器 ──────────────────────────────────────────────────────────

    @Test
    fun `a provider result is wrapped with the window and time`() {
        val text = PhoneContextDispatcher.describe(
            sources = listOf(PhoneContext.Source.APP_USAGE),
            days = 7,
            nowMillis = 1_700_000_000_000L,
            providers = mapOf(
                PhoneContext.Source.APP_USAGE to PhoneContextDispatcher.Provider {
                    PhoneContextDispatcher.Loaded.available(listOf("com.a | 前台 30 分钟"))
                },
            ),
        )

        assertTrue(text.contains("应用使用"))
        assertTrue(text.contains("最近 7 天"))
        assertTrue(text.contains("com.a"))
    }

    @Test
    fun `a source without a provider is reported as not wired instead of silently skipped`() {
        val text = PhoneContextDispatcher.describe(
            sources = listOf(PhoneContext.Source.CONTACTS),
            days = 1,
            nowMillis = 1_700_000_000_000L,
            providers = emptyMap(),
        )

        assertTrue("不能装作没有数据: $text", text.contains("还没有接入"))
        assertTrue(text.contains("不可用"))
    }

    @Test
    fun `multiple sources are rendered separately`() {
        val text = PhoneContextDispatcher.describe(
            sources = listOf(PhoneContext.Source.CALENDAR, PhoneContext.Source.NOTIFICATIONS),
            days = 1,
            nowMillis = 1_700_000_000_000L,
            providers = mapOf(
                PhoneContext.Source.CALENDAR to PhoneContextDispatcher.Provider {
                    PhoneContextDispatcher.Loaded.denied("需要日历读取权限")
                },
            ),
        )

        assertTrue(text.contains("日历"))
        assertTrue(text.contains("通知"))
        assertTrue("两个来源之间要分开: $text", text.contains("\n\n"))
    }

    @Test
    fun `zero days means the current moment not a window`() {
        val text = PhoneContextDispatcher.describe(
            sources = listOf(PhoneContext.Source.CALENDAR),
            days = 0,
            nowMillis = 1_700_000_000_000L,
            providers = mapOf(
                PhoneContext.Source.CALENDAR to PhoneContextDispatcher.Provider {
                    PhoneContextDispatcher.Loaded.available(emptyList())
                },
            ),
        )

        assertTrue(text.contains("当前时刻"))
        assertEquals(1, text.lines().count { it.startsWith("---") })
    }
}
