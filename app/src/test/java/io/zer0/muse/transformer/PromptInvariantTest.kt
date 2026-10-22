package io.zer0.muse.transformer

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 提示词回归锚点的测试。
 *
 * 覆盖助手自审两项「靠临场判断、不同轮结果不一致」的问题：
 * - 第 11 条：artifact 与 show_card 的边界老在摇摆，同一类内容不同轮给不同形式。
 * - 第 12 条：搜索是三级优先但没有「够不够用」的阈值，经常过度抓网页或拿摘要就作答。
 *
 * 这类问题的修法只能是提示词 —— 给出**可判定的顺序**而不是「凭感觉」。
 * 本测试钉住这些判据不被后续编辑删掉：它们不是文案，是行为约束。
 */
class PromptInvariantTest {

    private val invariants = SystemPromptAssembler.PROMPT_INVARIANTS

    @Test
    fun `invariant list is not empty`() {
        assertTrue("锚点表被清空了，提示词约束就失去了守护", invariants.isNotEmpty())
    }

    @Test
    fun `artifact vs show_card decision is based on what the user does with it`() {
        val joined = invariants.joinToString("\n")

        assertTrue("要按「拿走」判断 artifact", joined.contains("用户会把它复制走或保存吗"))
        assertTrue("要按「操作」判断 show_card", joined.contains("用户会点它、拖它、改它吗"))
    }

    @Test
    fun `duplicating the same content in both forms is forbidden`() {
        val joined = invariants.joinToString("\n")

        assertTrue(
            "同一内容出两份是用户实际抱怨的现象，必须显式禁止",
            joined.contains("不要**为同一份内容同时出卡片和 artifact"),
        )
    }

    @Test
    fun `search has both stop and continue criteria`() {
        val joined = invariants.joinToString("\n")

        assertTrue("要有「该停」的判据", joined.contains("已能直接回答用户的问题"))
        assertTrue("多来源一致就该停", joined.contains("多个来源对同一事实一致"))
        assertTrue("要有「该继续」的判据", joined.contains("关键数字/日期/名称仍缺失"))
    }

    @Test
    fun `summary alone is not treated as verified`() {
        val joined = invariants.joinToString("\n")

        assertTrue(
            "拿搜索摘要当已核实是第 12 条的另一半问题",
            joined.contains("不能拿摘要当已核实"),
        )
    }

    @Test
    fun `every invariant actually appears in the assembled prompt sources`() {
        // 防止「锚点表里写了但提示词正文没写」——那样测试绿了但行为没变。
        val source =
            java.io.File("src/main/java/io/zer0/muse/transformer/SystemPromptAssembler.kt")
                .takeIf { it.exists() }
                ?.readText()
        if (source == null) return // 不在模块根目录运行时跳过（CI 里会跑到）

        invariants.forEach { phrase ->
            // 锚点表自身会出现一次；正文里必须还有至少一次。
            val occurrences = Regex(Regex.escape(phrase)).findAll(source).count()
            assertTrue(
                "锚点「$phrase」只出现在锚点表里，提示词正文没写 —— 行为不会变",
                occurrences >= 2,
            )
        }
    }
}
