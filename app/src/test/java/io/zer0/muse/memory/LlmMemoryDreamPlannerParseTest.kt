package io.zer0.muse.memory

import io.zer0.ai.ChatService
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dream 计划解析的边界。
 *
 * 解析器是「模型输出 → 可落库数据」之间唯一的形状约束，
 * 它放宽一点，用户记忆就多一点被误写的可能。
 */
class LlmMemoryDreamPlannerParseTest {

    private val planner = LlmMemoryDreamPlanner(mockk<ChatService>(relaxed = true))

    @Test
    fun `markdown sections are parsed and lowercased`() {
        val text = """
            ## TODAY
            今天修好了搜索 401
            ## facts
            - 用户喜欢猫
        """.trimIndent()

        val sections = planner.parseSections(text)

        assertEquals(setOf("today", "facts"), sections.keys)
        assertEquals("今天修好了搜索 401", sections["today"])
    }

    @Test
    fun `json fallback works when markdown is absent`() {
        val text = """{"sections":{"week":"本周推进记忆计划"}}"""

        val sections = planner.parseSections(text)

        assertEquals(mapOf("week" to "本周推进记忆计划"), sections)
    }

    @Test
    fun `free text without structure yields empty plan`() {
        val sections = planner.parseSections("我觉得这段记忆挺好的，不用改。")

        assertTrue("结构不对时宁可不动记忆", sections.isEmpty())
    }

    @Test
    fun `blank section body is dropped`() {
        val text = """
            ## today

            ## facts
            有内容
        """.trimIndent()

        val sections = planner.parseSections(text)

        assertEquals(setOf("facts"), sections.keys)
    }
}
