package io.zer0.muse.transformer

import io.zer0.muse.data.assistant.AssistantEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 可委托助手简介的测试。
 *
 * 助手自审第 13 条：「多 Agent 委托里可委托的助手，简介几乎一样，我很难判断该找谁」。
 * 根因：简介取 `systemPrompt.take(80)`，而多数助手的人设开头是同一套通用话术
 * （「你是一个…」/「You are a…」），截出来自然全都雷同。
 *
 * 这里钉住：优先用结构化字段（人格定义 / 标签），回退时剥掉通用开场白。
 */
class DelegateBriefTest {

    private fun assistant(name: String = "a", systemPrompt: String = "", personaDefinition: String = "", tagsJson: String = "[]") =
        AssistantEntity(
            id = name,
            name = name,
            systemPrompt = systemPrompt,
            personaDefinition = personaDefinition,
            tagsJson = tagsJson,
        )

    private fun brief(a: AssistantEntity) = SystemPromptAssembler.delegateBriefOf(a)

    @Test
    fun `persona definition wins over system prompt`() {
        val a = assistant(
            systemPrompt = "你是一个乐于助人的通用助手，擅长各种任务。",
            personaDefinition = "专攻数据可视化，擅长把复杂指标画成易懂的图",
        )

        assertTrue("应优先采用人格定义", brief(a).contains("数据可视化"))
    }

    @Test
    fun `tags are used when persona is empty`() {
        val a = assistant(
            systemPrompt = "你是一个乐于助人的通用助手。",
            tagsJson = """["写作","润色","文案"]""",
        )

        assertEquals("写作、润色、文案", brief(a))
    }

    @Test
    fun `generic opening is stripped from system prompt fallback`() {
        val a = assistant(systemPrompt = "你是一个资深法律顾问，擅长合同审查与风险提示。")

        val out = brief(a)

        assertTrue("通用开场白应被剥掉，实际: $out", !out.startsWith("你是一个"))
        assertTrue("应保留有区分度的部分", out.contains("法律顾问"))
    }

    @Test
    fun `english generic opening is stripped too`() {
        val a = assistant(systemPrompt = "You are a senior backend engineer who reviews Go services.")

        val out = brief(a)

        assertTrue("英文通用开场白也应剥掉，实际: $out", !out.startsWith("You are"))
        assertTrue(out.contains("backend engineer"))
    }

    @Test
    fun `two assistants with identical generic openings still get different briefs`() {
        // 这就是用户报的「简介几乎一样」的场景
        val a = assistant("x", systemPrompt = "你是一个 AI 助手。擅长数据分析与报表。")
        val b = assistant("y", systemPrompt = "你是一个 AI 助手。擅长小说创作与世界观设定。")

        assertNotEquals("简介必须能区分这两个助手", brief(a), brief(b))
        assertTrue(brief(a).contains("数据分析"))
        assertTrue(brief(b).contains("小说创作"))
    }

    @Test
    fun `brief is truncated to the configured budget`() {
        val a = assistant(personaDefinition = "长".repeat(200))

        assertTrue(brief(a).length <= SystemPromptAssembler.BRIEF_MAX_CHARS)
    }

    @Test
    fun `newlines and extra whitespace collapse to one line`() {
        val a = assistant(personaDefinition = "第一行\n\n  第二行   第三行")

        val out = brief(a)

        assertTrue("不能有换行", !out.contains("\n"))
        assertTrue("连续空白应收敛", !out.contains("  "))
    }

    @Test
    fun `empty everything yields an empty brief`() {
        assertEquals("", brief(assistant()))
    }

    @Test
    fun `malformed tags json does not crash`() {
        val a = assistant(systemPrompt = "你是一个通用助手。", tagsJson = "not-json")

        assertTrue(brief(a).isNotEmpty())
    }

    @Test
    fun `tags are capped so the line does not grow unbounded`() {
        val a = assistant(tagsJson = """["a","b","c","d","e","f","g"]""")

        val out = brief(a)

        assertEquals("a、b、c、d", out)
    }
}
