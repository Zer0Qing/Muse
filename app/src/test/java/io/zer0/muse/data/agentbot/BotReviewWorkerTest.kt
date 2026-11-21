package io.zer0.muse.data.agentbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内部审核器的判定规则。
 *
 * 这个类不碰数据库 —— 审核是纯判断，最容易因为"顺手放宽一点"而失效，
 * 而它失效的后果是把"我以为我做完了"当成"已经做完"报给用户。
 */
class BotReviewWorkerTest {

    private val worker = BotReviewWorker()

    @Test
    fun `text only result is not a completed task`() {
        val decision = worker.review(
            criteria = "只改错字，不改语气",
            claimedSummary = "我已经改好了",
            producedArtifacts = emptyList(),
            verifiedChecks = emptyList(),
        )
        assertFalse("只返回一段文字不算完成任务", decision.accepted)
        assertEquals(ReviewDisposition.REWORK, decision.disposition)
        assertTrue(decision.findings.any { it.contains("产物") })
    }

    @Test
    fun `criteria without any verification goes back for rework once`() {
        val decision = worker.review(
            criteria = "只改错字",
            claimedSummary = "改完了",
            producedArtifacts = listOf("workspace/notes.md"),
            verifiedChecks = emptyList(),
            attempt = 1,
        )
        assertEquals(ReviewDisposition.REWORK, decision.disposition)
    }

    @Test
    fun `second attempt without verification is reported as partial not success`() {
        val decision = worker.review(
            criteria = "只改错字",
            claimedSummary = "改完了",
            producedArtifacts = listOf("workspace/notes.md"),
            verifiedChecks = emptyList(),
            attempt = BotReviewWorker.MAX_AUTO_REWORK,
        )
        assertEquals(
            "第二次仍没有校验记录时，必须如实报未验项，不能算成功",
            ReviewDisposition.PARTIAL,
            decision.disposition,
        )
        assertTrue(decision.findings.any { it.contains("未验项") })
    }

    @Test
    fun `artifact plus verification is accepted`() {
        val decision = worker.review(
            criteria = "只改错字",
            claimedSummary = "改完了",
            producedArtifacts = listOf("workspace/notes.md"),
            verifiedChecks = listOf("diff 核对：只改了 3 处错字"),
        )
        assertTrue(decision.accepted)
        assertEquals(ReviewDisposition.ACCEPT, decision.disposition)
    }

    @Test
    fun `base hash drift blocks publication`() {
        val decision = worker.review(
            criteria = "只改错字",
            claimedSummary = "改完了",
            producedArtifacts = listOf("workspace/notes.md"),
            verifiedChecks = listOf("diff 核对通过"),
            expectedBaseHash = "hash-v1",
            actualBaseHash = "hash-v2",
        )
        assertEquals(
            "目标在任务执行期间被改过，本轮产物不能发布",
            ReviewDisposition.UNKNOWN,
            decision.disposition,
        )
    }

    @Test
    fun `empty summary with artifact still accepts but records it`() {
        val decision = worker.review(
            criteria = "",
            claimedSummary = "",
            producedArtifacts = listOf("workspace/out.md"),
            verifiedChecks = emptyList(),
        )
        assertTrue(decision.accepted)
        assertTrue("自述为空要记一笔", decision.findings.any { it.contains("未给出说明") })
    }

    @Test
    fun `rework limit gives up instead of looping forever`() {
        val decision = worker.review(
            criteria = "x",
            claimedSummary = "x",
            producedArtifacts = emptyList(),
            verifiedChecks = emptyList(),
            attempt = BotReviewWorker.MAX_AUTO_REWORK,
        )
        assertEquals(
            "超过返工上限要交给用户决定，不能无限烧预算",
            ReviewDisposition.NEEDS_USER,
            decision.disposition,
        )
    }

    @Test
    fun `every disposition maps to a task transition`() {
        ReviewDisposition.all.forEach { disposition ->
            val event = worker.transitionFor(BotReviewWorker.Decision(disposition))
            assertNotNull("结论 " + disposition + " 必须有对应转移，否则会被静默丢弃", event)
        }
    }

    @Test
    fun `accept maps to REVIEW_ACCEPT not to START`() {
        assertEquals(
            BotTaskStatusMachine.Event.REVIEW_ACCEPT,
            worker.transitionFor(BotReviewWorker.Decision(ReviewDisposition.ACCEPT)),
        )
    }

    @Test
    fun `unknown disposition falls back to unknown transition`() {
        assertEquals(
            BotTaskStatusMachine.Event.MARK_UNKNOWN,
            worker.transitionFor(BotReviewWorker.Decision("SOMETHING_NEW")),
        )
    }
}