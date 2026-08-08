package io.zer0.muse.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * v1.0.72: 节气计算测试(寿星公式按年份计算)。
 *
 * 背景:旧实现固定日期表(立秋=8/8),但 2026 立秋实际在 8/7,
 * 导致用户反馈"立秋是昨天的事"。新实现用寿星公式逐年计算。
 *
 * 验证已知年份的真实节气日期:
 *  - 2026 立秋 = 8/7(用户反馈的 bug 场景)
 *  - 2026 处暑 = 8/23
 *  - 2026 春分 = 3/20
 *  - 2026 立春 = 2/4
 *  - 普通日期返回 null
 *  - 节气前一天提示"明天是X"
 */
class SolarTermTest {

    @Test
    fun `2026 autumn start is aug 7 not aug 8`() {
        // 回归: 用户反馈"立秋是昨天的事"(旧表写 8/8)
        assertEquals("今天是立秋", GreetingHelper.getSolarTerm(LocalDate.of(2026, 8, 7)))
        // 8/8 不再是立秋
        assertNull(GreetingHelper.getSolarTerm(LocalDate.of(2026, 8, 8)))
    }

    @Test
    fun `2026 other autumn terms`() {
        assertEquals("今天是处暑", GreetingHelper.getSolarTerm(LocalDate.of(2026, 8, 23)))
    }

    @Test
    fun `2026 spring terms`() {
        assertEquals("今天是立春", GreetingHelper.getSolarTerm(LocalDate.of(2026, 2, 4)))
        assertEquals("今天是春分", GreetingHelper.getSolarTerm(LocalDate.of(2026, 3, 20)))
    }

    @Test
    fun `day before term announces tomorrow`() {
        assertEquals("明天是立秋", GreetingHelper.getSolarTerm(LocalDate.of(2026, 8, 6)))
    }

    @Test
    fun `ordinary day returns null`() {
        assertNull(GreetingHelper.getSolarTerm(LocalDate.of(2026, 8, 10)))
        assertNull(GreetingHelper.getSolarTerm(LocalDate.of(2026, 12, 25)))
    }

    @Test
    fun `2027 autumn start varies by year`() {
        // 2027 立秋 = 8/8(年份不同日期不同,验证非固定表)
        assertEquals("今天是立秋", GreetingHelper.getSolarTerm(LocalDate.of(2027, 8, 8)))
    }
}
