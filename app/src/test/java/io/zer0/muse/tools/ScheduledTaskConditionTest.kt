package io.zer0.muse.tools

import io.zer0.muse.data.schedule.AutomationConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 定时任务条件参数构造的测试。
 *
 * 用户反馈「condition_type 实际只有 always 可靠」。核实后根因不是条件引擎没实现
 * （`ScheduledTaskRunner.evaluateCondition` 有 8 种），而是**工具创建路径只写
 * `{"type":"x"}`、不写参数**：
 *  - time_range 缺 startHour/endHour → 退化 0..23，恒真
 *  - contains 缺 keyword → 恒假
 * 所以模型以为设了条件，实际没设。这里钉住「参数不全就拒绝创建」。
 *
 * 纯 JVM 测试：只测 JSON 构造，不需要 Android 运行时。
 */
class ScheduledTaskConditionTest {

    private fun build(
        type: String,
        params: ScheduledTaskToolsImpl.Companion.ConditionParams =
            ScheduledTaskToolsImpl.Companion.ConditionParams(),
    ): String? = ScheduledTaskToolsImpl.buildConditionJson(conditionType = type, params = params)

    private fun params(
        startHour: String? = null,
        endHour: String? = null,
        keyword: String? = null,
        tag: String? = null,
        minLevel: String? = null,
    ) = ScheduledTaskToolsImpl.Companion.ConditionParams(startHour, endHour, keyword, tag, minLevel)

    private fun decode(json: String): AutomationConfig.Condition =
        io.zer0.common.AppJson.decodeFromString(AutomationConfig.Condition.serializer(), json)

    @Test
    fun `always writes an empty string for backward compatibility`() {
        assertEquals("", build(AutomationConfig.Condition.ALWAYS))
    }

    @Test
    fun `time range keeps both hours so it does not degrade to always true`() {
        val json = build(AutomationConfig.Condition.TIME_RANGE, params(startHour = "9", endHour = "18"))

        assertNotNull("time_range 带齐参数时必须能构造", json)
        val condition = decode(json!!)
        assertEquals(AutomationConfig.Condition.TIME_RANGE, condition.type)
        assertEquals("9", condition.config["startHour"].toString().trim('"'))
        assertEquals("18", condition.config["endHour"].toString().trim('"'))
    }

    @Test
    fun `time range without hours is rejected instead of silently becoming always`() {
        assertNull(build(AutomationConfig.Condition.TIME_RANGE))
        assertNull(build(AutomationConfig.Condition.TIME_RANGE, params(startHour = "9")))
        assertNull(build(AutomationConfig.Condition.TIME_RANGE, params(endHour = "18")))
    }

    @Test
    fun `time range rejects out of range hours`() {
        assertNull(build(AutomationConfig.Condition.TIME_RANGE, params(startHour = "24", endHour = "30")))
        assertNull(build(AutomationConfig.Condition.TIME_RANGE, params(startHour = "-1", endHour = "8")))
    }

    @Test
    fun `contains keeps the keyword so it does not become always false`() {
        val json = build(AutomationConfig.Condition.CONTAINS, params(keyword = "会议"))

        assertNotNull(json)
        assertEquals("会议", decode(json!!).config["keyword"].toString().trim('"'))
    }

    @Test
    fun `contains without keyword is rejected`() {
        assertNull(build(AutomationConfig.Condition.CONTAINS))
        assertNull(build(AutomationConfig.Condition.CONTAINS, params(keyword = "   ")))
    }

    @Test
    fun `quick note exists accepts keyword or tag but not neither`() {
        assertNotNull(build(AutomationConfig.Condition.QUICK_NOTE_EXISTS, params(keyword = "购物")))
        assertNotNull(build(AutomationConfig.Condition.QUICK_NOTE_EXISTS, params(tag = "工作")))
        assertNull("两个都空等于恒真,必须拒绝", build(AutomationConfig.Condition.QUICK_NOTE_EXISTS))
    }

    @Test
    fun `battery level requires a sane threshold`() {
        val json = build(AutomationConfig.Condition.BATTERY_LEVEL, params(minLevel = "30"))

        assertNotNull(json)
        assertEquals("30", decode(json!!).config["minLevel"].toString().trim('"'))
        assertNull(build(AutomationConfig.Condition.BATTERY_LEVEL))
        assertNull(build(AutomationConfig.Condition.BATTERY_LEVEL, params(minLevel = "0")))
        assertNull(build(AutomationConfig.Condition.BATTERY_LEVEL, params(minLevel = "101")))
    }

    @Test
    fun `network available and charging need no extra parameters`() {
        assertNotNull(build(AutomationConfig.Condition.NETWORK_AVAILABLE))
        assertNotNull(build(AutomationConfig.Condition.CHARGING))
    }

    @Test
    fun `unknown condition type is rejected rather than stored as an unusable task`() {
        assertNull(build("some_future_type"))
    }

    @Test
    fun `condition json round trips through the runner parser`() {
        val json = build(AutomationConfig.Condition.TIME_RANGE, params(startHour = "8", endHour = "22"))!!

        // Runner 侧就是这样解析的(conditionJson.toCondition())
        val parsed = with(AutomationConfig) { json.toCondition() }

        assertEquals(AutomationConfig.Condition.TIME_RANGE, parsed.type)
        assertTrue(parsed.config.containsKey("startHour"))
    }
}
