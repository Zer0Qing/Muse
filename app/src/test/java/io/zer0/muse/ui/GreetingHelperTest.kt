package io.zer0.muse.ui

import io.zer0.memory.fact.FactEntity
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
class GreetingHelperTest {

    private fun fact(text: String, time: String? = null) = FactEntity(
        id = 1L,
        fact = text,
        time = time,
        createdAt = "2026-08-07T12:00:00",
    )

    @Test
    fun `时间段问候语边界稳定`() {
        assertEquals("早上好", GreetingHelper.getTimeGreeting(5))
        assertEquals("早上好", GreetingHelper.getTimeGreeting(10))
        assertEquals("中午好", GreetingHelper.getTimeGreeting(11))
        assertEquals("中午好", GreetingHelper.getTimeGreeting(13))
        assertEquals("下午好", GreetingHelper.getTimeGreeting(14))
        assertEquals("下午好", GreetingHelper.getTimeGreeting(17))
        assertEquals("晚上好", GreetingHelper.getTimeGreeting(18))
        assertEquals("晚上好", GreetingHelper.getTimeGreeting(22))
        assertEquals("深夜了", GreetingHelper.getTimeGreeting(23))
        assertEquals("深夜了", GreetingHelper.getTimeGreeting(0))
    }

    @Test
    fun `记忆有明天考试时问候语优先用考试提醒`() {
        val today = LocalDate.of(2026, 8, 7)
        val tomorrow = today.plusDays(1).toString()
        val facts = listOf(fact("用户明天要参加英语四级考试", time = tomorrow))

        val greeting = GreetingHelper.buildGreeting(facts, hour = 20, date = today)

        // 个性化优先:包含考试,不出现节气/节日
        assertTrue("应包含考试提醒: $greeting", greeting.contains("考试"))
        assertTrue("不应再显示节气: $greeting", !greeting.contains("立秋"))
        assertTrue("长度应受控: $greeting", greeting.length <= 40)
        // 第二人称:展示给用户的内容不应出现"用户"
        assertTrue("应使用第二人称: $greeting", !greeting.contains("用户"))
    }

    @Test
    fun `记忆有航班时生成出行提醒`() {
        val today = LocalDate.of(2026, 8, 7)
        val tomorrow = today.plusDays(1).toString()
        val facts = listOf(fact("明天上午的航班去上海出差", time = tomorrow))

        val hint = GreetingHelper.getMemoryHint(facts, today)

        assertNotNull(hint)
        assertTrue("应包含航班: $hint", hint!!.contains("航班"))
    }

    @Test
    fun `无近期事项时节气节日随机且只选一个`() {
        val today = LocalDate.of(2026, 8, 7) // 明天 8.8 立秋
        val greeting = GreetingHelper.buildGreeting(emptyList(), hour = 20, date = today)

        assertTrue("应带时间问候: $greeting", greeting.startsWith("晚上好"))
        // 只允许一个后缀(随机选节气或节日,不能同时拼两个)
        val extras = greeting.removePrefix("晚上好，")
        assertTrue("后缀应只有一条: $greeting", !extras.contains("，"))
    }

    @Test
    fun `无记忆无节气节日时只有问候`() {
        val today = LocalDate.of(2026, 8, 10) // 无节气无节日
        val greeting = GreetingHelper.buildGreeting(emptyList(), hour = 9, date = today)
        assertEquals("早上好", greeting)
    }

    @Test
    fun `生日在明天时提示生日`() {
        val today = LocalDate.of(2026, 8, 7)
        val facts = listOf(fact("用户的生日是8月8日"))
        val hint = GreetingHelper.getMemoryHint(facts, today)
        assertNotNull(hint)
        assertTrue("应提示生日: $hint", hint!!.contains("生日"))
    }

    @Test
    fun `3天外的事件不提示`() {
        val today = LocalDate.of(2026, 8, 7)
        val far = today.plusDays(5).toString()
        val facts = listOf(fact("用户要参加考试", time = far))
        assertNull(GreetingHelper.getMemoryHint(facts, today))
    }

    @Test
    fun `最近的每日总结会跟在时间问候后`() {
        val today = LocalDate.of(2026, 8, 10)
        val greeting = GreetingHelper.buildGreeting(
            facts = emptyList(),
            hour = 9,
            date = today,
            dailySummary = "今天你完成了接口排查，也记下了明天的会议。",
            dailySummaryDate = today.toString(),
        )

        assertTrue("应保留时间问候: $greeting", greeting.startsWith("早上好，"))
        assertTrue("应带上每日总结事项: $greeting", greeting.contains("完成了接口排查"))
    }

    @Test
    fun `每日总结和近期提示遵守首页单行预算`() {
        val today = LocalDate.of(2026, 8, 10)
        val summary = GreetingHelper.getDailySummaryHint(
            "今天我们完成了接口排查并记录了多个待办事项还讨论了后续安排。",
            today.toString(),
            today,
        )
        assertNotNull(summary)
        assertTrue(summary!!.length <= GreetingHelper.DAILY_SUMMARY_HINT_MAX_LENGTH)

        val hint = GreetingHelper.getMemoryHint(
            listOf(
                fact(
                    "用户明天要参加英语四级考试并准备相关资料",
                    time = today.plusDays(1).toString(),
                ),
            ),
            today,
        )
        assertNotNull(hint)
        assertTrue(hint!!.length <= GreetingHelper.PERSONALIZED_HINT_MAX_LENGTH)
    }

    @Test
    fun `超过一天的每日总结不应继续显示`() {
        val today = LocalDate.of(2026, 8, 10)
        val greeting = GreetingHelper.buildGreeting(
            facts = emptyList(),
            hour = 9,
            date = today,
            dailySummary = "这是前天的旧总结",
            dailySummaryDate = today.minusDays(2).toString(),
        )

        assertEquals("早上好", greeting)
    }
}
