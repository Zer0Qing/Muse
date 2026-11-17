package io.zer0.memory.dream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dream 计划生成的校验边界。
 *
 * 这里的每条断言都对应一次「模型输出被直接写进记忆」的风险：
 * 未知 section、超长正文、空替换、伪造 fingerprint 都必须被挡在发布之前。
 */
class MemoryDreamPlannerTest {

    private val snapshot = mapOf(
        "facts" to "用户偏好简洁回复",
        "today" to "今天在改供应商配置",
        "week" to "本周在推进记忆执行计划",
        "longterm" to "长期在做 Muse",
    )

    @Test
    fun `valid plan is accepted and keeps section allowlist`() {
        val plan = MemoryDreamPlan(
            sections = mapOf("facts" to "用户偏好简洁回复，讨厌客套"),
        )

        val result = MemoryDreamPlanValidator.validate(plan, snapshot)

        assertTrue(result is DreamPlanResult.Planned)
        assertEquals(
            mapOf("facts" to "用户偏好简洁回复，讨厌客套"),
            (result as DreamPlanResult.Planned).sections,
        )
    }

    @Test
    fun `unknown section key is rejected instead of silently written`() {
        val plan = MemoryDreamPlan(sections = mapOf("profile" to "不该被写入"))

        val result = MemoryDreamPlanValidator.validate(plan, snapshot)

        assertTrue(result is DreamPlanResult.Rejected)
        assertTrue((result as DreamPlanResult.Rejected).reason.contains("profile"))
    }

    @Test
    fun `empty plan is rejected so existing sections are not wiped`() {
        val result = MemoryDreamPlanValidator.validate(MemoryDreamPlan(sections = emptyMap()), snapshot)

        assertTrue(result is DreamPlanResult.Empty)
    }

    @Test
    fun `blank replacement is rejected`() {
        val plan = MemoryDreamPlan(sections = mapOf("today" to "   "))

        val result = MemoryDreamPlanValidator.validate(plan, snapshot)

        assertTrue(result is DreamPlanResult.Rejected)
    }

    @Test
    fun `oversized section is rejected`() {
        val plan = MemoryDreamPlan(sections = mapOf("longterm" to "x".repeat(900)))

        val result = MemoryDreamPlanValidator.validate(plan, snapshot)

        assertTrue(result is DreamPlanResult.Rejected)
        assertTrue((result as DreamPlanResult.Rejected).reason.contains("longterm"))
    }

    @Test
    fun `unchanged section is dropped to keep the write minimal`() {
        val plan = MemoryDreamPlan(
            sections = mapOf(
                "facts" to "用户偏好简洁回复",
                "today" to "今天在改供应商配置，顺带修了搜索",
            ),
        )

        val result = MemoryDreamPlanValidator.validate(plan, snapshot)

        assertTrue(result is DreamPlanResult.Planned)
        assertEquals(
            setOf("today"),
            (result as DreamPlanResult.Planned).sections.keys,
        )
    }
}
