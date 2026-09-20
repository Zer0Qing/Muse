package io.zer0.muse.rag

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 全局重索引终态判定 —— 复核项的回归防线。
 *
 * 背景:此前 [ReindexAllWorker] 无论成功失败都只写日志并返回 Result.success(),
 * 用户点完「重新索引全部」在进度通知消失后看不到任何结果,全部文档失败也与
 * 全部成功无法区分。现在终态决定结果通知的文案与优先级,这里钉住判定规则。
 */
class ReindexOutcomeTest {

    @Test
    fun `all documents succeeded is success`() {
        assertEquals(ReindexOutcome.SUCCESS, reindexOutcomeOf(successCount = 5, total = 5))
    }

    @Test
    fun `partial failure is partial not success`() {
        assertEquals(ReindexOutcome.PARTIAL, reindexOutcomeOf(successCount = 4, total = 5))
        assertEquals(ReindexOutcome.PARTIAL, reindexOutcomeOf(successCount = 1, total = 9))
    }

    @Test
    fun `every document failed is failed not success`() {
        assertEquals(ReindexOutcome.FAILED, reindexOutcomeOf(successCount = 0, total = 3))
    }

    @Test
    fun `nothing to do counts as success`() {
        assertEquals(ReindexOutcome.SUCCESS, reindexOutcomeOf(successCount = 0, total = 0))
        assertEquals(ReindexOutcome.SUCCESS, reindexOutcomeOf(successCount = 0, total = -1))
    }

    @Test
    fun `unexpected extra successes do not downgrade to partial`() {
        assertEquals(ReindexOutcome.SUCCESS, reindexOutcomeOf(successCount = 6, total = 5))
    }

    @Test
    fun `no count combination collapses failed into success`() {
        // 穷举小范围组合:失败(0 成功且有文档)绝不能与「全部成功」同判
        for (total in 1..12) {
            for (success in 0..total) {
                val outcome = reindexOutcomeOf(success, total)
                if (success == 0) {
                    assertEquals("total=$total success=0 必须判失败", ReindexOutcome.FAILED, outcome)
                } else if (success < total) {
                    assertEquals("total=$total success=$success 必须判部分失败", ReindexOutcome.PARTIAL, outcome)
                } else {
                    assertEquals(ReindexOutcome.SUCCESS, outcome)
                }
            }
        }
    }
}
