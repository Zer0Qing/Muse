package io.zer0.muse.tools

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具重试预算说明的测试。
 *
 * 助手自审第 15 条：「失败没有重试预算的可视化，我一次失败后就很保守，
 * 其实有些是可以安全重试的」。
 *
 * 这里钉住三种情形：还有机会 / 已用尽 / 本来就不该重试。
 */
class ToolRetryBudgetTest {

    @Test
    fun `first retryable failure says how many chances remain`() {
        val line = ToolRetryBudget.describe("web_fetch", attempt = 1, retryable = true)

        assertTrue("要说清第几次", line.contains("第 1 次失败"))
        assertTrue("要给出剩余机会", line.contains("还剩 1 次重试机会"))
    }

    @Test
    fun `exhausted budget tells the model to stop calling the same tool`() {
        val line = ToolRetryBudget.describe("web_fetch", attempt = 2, retryable = true)

        assertTrue(line.contains("重试机会已用尽"))
        assertTrue("要明确叫停", line.contains("不要再调用同一工具"))
    }

    @Test
    fun `non-retryable failure says so regardless of remaining budget`() {
        val line = ToolRetryBudget.describe("screen_tap", attempt = 1, retryable = false)

        assertTrue("不可重试时不能给出剩余机会", !line.contains("还剩"))
        assertTrue(line.contains("本次失败不可重试"))
    }

    @Test
    fun `tool name appears so the model knows which budget this is`() {
        val line = ToolRetryBudget.describe("generate_image", attempt = 1, retryable = true)

        assertTrue(line.contains("generate_image"))
    }

    @Test
    fun `attempt beyond the budget is clamped instead of going negative`() {
        val line = ToolRetryBudget.describe("x", attempt = 9, retryable = true)

        assertTrue("不能出现负数", !line.contains("-"))
        assertTrue(line.contains("已用尽"))
    }

    @Test
    fun `budget matches the prompt rule of one correction`() {
        // 提示词写的是「失败只按错误信息修正一次」= 首次失败后再给 1 次
        assertTrue("上限必须与提示词口径一致", ToolRetryBudget.MAX_RETRIES == 1)
    }
}
