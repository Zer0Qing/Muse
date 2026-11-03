package io.zer0.muse.tools

import io.zer0.ai.core.UsageTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 子 agent 的 token 预算账本（协作语义显式化 · 第一部分）。
 *
 * 这些规则此前**只在代码里**：委派链每轮都问模型要不要停，
 * 判据就是这里几条。写错一条的后果不是报错而是**悄悄多烧钱**或**悄悄提前截断**，
 * 用户只会觉得"最近怎么老答一半"，很难归因。所以逐条钉住。
 *
 * 两条特别值得说：
 *  - `usage == null` 时**不能**判耗尽：很多 Provider 不返回 usage，
 *    若把"没数据"当成"烧完了"，所有委派会在第一轮就被砍掉；
 *  - `trySpend` 失败时**不许留下预扣**：否则一次被拒会把后面的额度吃掉。
 */
class AgentTokenBudgetTest {

    @Test
    fun `上限为空或非正数时表示不限制`() {
        assertNull(AgentTokenBudget.of(null))
        assertNull("0 不该被当成\"只有 0 token\"而是\"不限制\"", AgentTokenBudget.of(0))
        assertNull(AgentTokenBudget.of(-5))
        assertNotNull(AgentTokenBudget.of(1))
    }

    @Test
    fun `子 agent 默认预算仍是文档里那个数`() {
        // 常量被改动会同时改变每条委派的烧钱上限；钉住它，改的人必须是有意识地改。
        assertEquals(60_000, AgentTokenBudget.DEFAULT_SUBAGENT_BUDGET)
    }

    @Test
    fun `预扣成功才记账，超限时一分都不扣`() {
        val budget = requireNotNull(AgentTokenBudget.of(100))

        assertTrue(budget.trySpend(40))
        assertEquals(40, budget.spentTokens)
        assertFalse("再要 61 就超了，必须拒", budget.trySpend(61))
        assertEquals("被拒的预扣不能留下痕迹", 40, budget.spentTokens)
        assertTrue("刚好用满不算超", budget.trySpend(60))
        assertTrue(budget.isExhausted)
        assertEquals(0, budget.remaining)
    }

    @Test
    fun `零与负数的预扣不改变账本`() {
        val budget = requireNotNull(AgentTokenBudget.of(50))

        assertTrue(budget.trySpend(0))
        assertTrue(budget.trySpend(-10))
        assertEquals(0, budget.spentTokens)
        assertFalse(budget.isExhausted)
    }

    @Test
    fun `没有 usage 数据时不能误判成耗尽`() {
        val budget = requireNotNull(AgentTokenBudget.of(10))

        assertTrue("Provider 没回 usage 时应放行，继续下一轮", budget.accumulate(null))
        assertEquals(0, budget.spentTokens)
    }

    @Test
    fun `累加用的是 prompt 加 completion 且恰好等于上限即耗尽`() {
        val budget = requireNotNull(AgentTokenBudget.of(100))

        assertTrue(budget.accumulate(UsageTokens(promptTokens = 60, completionTokens = 30)))
        assertEquals(90, budget.spentTokens)
        assertEquals(10, budget.remaining)

        assertFalse(budget.accumulate(UsageTokens(promptTokens = 5, completionTokens = 5)))
        assertTrue(budget.isExhausted)
        assertEquals("账本可以超发，但余额不能是负数", 0, budget.remaining)
    }
}
