package io.zer0.muse.proactive

import io.zer0.muse.data.assistant.AgentDirective
import io.zer0.muse.data.assistant.DirectivePolarity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用户约定注入提示词的规则（整改计划 第 4 条 ③）。
 *
 * 这三条判据都是"用户会直接感觉到"的行为，不是代码风格：
 * 谁压过谁、超量时谁知道没生效、没配置时提示词不许被改动。
 */
class AgentDirectivesPromptTest {

    private fun d(
        text: String,
        polarity: DirectivePolarity = DirectivePolarity.SHOULD,
        enabled: Boolean = true,
        position: Int = 0,
    ) = AgentDirective(
        id = "$polarity-$position-$text",
        agentId = "a1",
        polarity = polarity,
        text = text,
        position = position,
        enabled = enabled,
    )

    @Test
    fun `没有约定时不产出任何文本块`() {
        val r = AgentDirectivesPrompt.build(emptyList(), totalBudgetChars = 600)
        assertEquals("空清单必须一字不加（否则上线就等于改了所有人的提示词）", "", r.block)
        assertTrue(r.omitted.isEmpty())
    }

    @Test
    fun `不希望排在前面且冲突时以它为准`() {
        val r = AgentDirectivesPrompt.build(
            listOf(
                // 两条内容刻意互为子串：上一版我断言 `indexOf("叫我宝贝")` 在
                // `indexOf("别叫我宝贝")` 之后，而它其实是后者的子串，测试因此恒成立。
                // 现在断言**段落标题**的顺序，才真的测到"谁先谁后"。
                d("叫我宝贝", DirectivePolarity.SHOULD, position = 0),
                d("别叫我宝贝", DirectivePolarity.MUST_NOT, position = 0),
            ),
            totalBudgetChars = 600,
        )
        val forbiddenHeader = r.block.indexOf("绝对不要：")
        val desiredHeader = r.block.indexOf("尽量做到：")
        assertTrue("必须有「绝对不要」段", forbiddenHeader >= 0)
        assertTrue("必须有「尽量做到」段", desiredHeader >= 0)
        assertTrue("禁止段必须先出现（模型先读到不能做的事）", forbiddenHeader < desiredHeader)
        assertTrue("要写明冲突时以哪边为准", r.block.contains("以「绝对不要」为准"))
    }

    @Test
    fun `超预算的条目被交回而不是静默丢掉`() {
        // 预算 130：一条 150 字装不下（必须被点名交回），但后面那条短的**不能被它挤掉**——
        // 我第一版把长度写成 120，两条其实都装得下，测的是一句永远成立的空话。
        val big = d("x".repeat(150), DirectivePolarity.MUST_NOT)
        val small = d("别用英文回我", DirectivePolarity.MUST_NOT)
        val r = AgentDirectivesPrompt.build(listOf(big, small), totalBudgetChars = 130)
        assertTrue("小条目仍该生效（不能因为前一条太长就一起挤掉）", r.block.contains(small.text))
        assertEquals("超量的那一条要被点名交回", listOf(big.text), r.omitted.map { it.text })
    }

    @Test
    fun `停用的约定不进提示词`() {
        val r = AgentDirectivesPrompt.build(
            listOf(d("早上提醒我", position = 0), d("晚上别吵", enabled = false, position = 1)),
            totalBudgetChars = 600,
        )
        assertTrue(r.block.contains("早上提醒我"))
        assertTrue("勾掉的就是不生效", !r.block.contains("晚上别吵"))
        assertTrue("停用条目不算超量", r.omitted.isEmpty())
    }

    @Test
    fun `预算为 0 或负数时整段不加而不是崩`() {
        assertEquals("", AgentDirectivesPrompt.build(listOf(d("任意一条")), totalBudgetChars = 0).block)
        assertEquals("", AgentDirectivesPrompt.build(listOf(d("任意一条")), totalBudgetChars = -5).block)
    }

    @Test
    fun `只有希望项时不要出现空的禁止段`() {
        val r = AgentDirectivesPrompt.build(listOf(d("早上提醒我吃早饭")), totalBudgetChars = 600)
        assertTrue("不该有「绝对不要：」空段", !r.block.contains("绝对不要："))
        assertTrue("要有「尽量做到：」", r.block.contains("尽量做到："))
    }
}
