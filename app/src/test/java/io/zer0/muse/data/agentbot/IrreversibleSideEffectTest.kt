package io.zer0.muse.data.agentbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V23：副作用已发生时必须如实汇报。
 *
 * 有些操作不能暂存 —— 发了消息、调了外部接口、改了数据库。这类操作一旦发生，
 * 新约束只能前向生效，**不能假装撤销**，也不能对用户隐瞒"其实已经做了"。
 */
class IrreversibleSideEffectTest {

    private val worker = BotReviewWorker();

    @Test
    fun `side effects are reported even when everything else looks fine`() {
        val decision = worker.review(
            criteria = "只改错字",
            claimedSummary = "改完了，还顺手发了一条消息",
            producedArtifacts = listOf("notes.md"),
            verifiedChecks = listOf("diff 核对通过"),
            irreversibleSideEffects = listOf("已向外部接口发送请求"),
        );

        assertEquals(
            "做了但没全做完，判 PARTIAL 而不是 ACCEPT",
            ReviewDisposition.PARTIAL,
            decision.disposition,
        );
        assertTrue(
            "必须明确写出哪些已经发生",
            decision.findings.any { it.contains("已发生且无法回滚") },
        );
    }

    @Test
    fun `side effects are reported before hash checks`() {
        // 顺序刻意的：一件事已经做了一半时，用户最需要知道"哪些已经做了"，
        // 而不是"目标哈希对不对"。
        val decision = worker.review(
            criteria = "x",
            claimedSummary = "x",
            producedArtifacts = listOf("notes.md"),
            verifiedChecks = emptyList(),
            expectedBaseHash = "hash-v1",
            actualBaseHash = "hash-v2",
            irreversibleSideEffects = listOf("已发送通知"),
        );

        assertTrue(
            "副作用优先于基线漂移汇报",
            decision.findings.any { it.contains("已发生且无法回滚") },
        );
        assertFalse(
            "基线漂移那条不该盖过副作用",
            decision.findings.any { it.contains("基线 hash 不匹配") },
        );
    }

    @Test
    fun `side effect without artifacts needs the user`() {
        val decision = worker.review(
            criteria = "x",
            claimedSummary = "发了消息但没产出文件",
            producedArtifacts = emptyList(),
            verifiedChecks = emptyList(),
            irreversibleSideEffects = listOf("已发送通知"),
        );

        assertEquals(
            "既没产物又有副作用，必须让用户决定怎么处理",
            ReviewDisposition.NEEDS_USER,
            decision.disposition,
        );
    }

    @Test
    fun `no side effects keeps the normal path`() {
        val decision = worker.review(
            criteria = "只改错字",
            claimedSummary = "改完了",
            producedArtifacts = listOf("notes.md"),
            verifiedChecks = listOf("diff 通过"),
        );

        assertEquals(ReviewDisposition.ACCEPT, decision.disposition);
    }

    @Test
    fun `partial maps to the partial transition`() {
        val decision = worker.review(
            criteria = "x",
            claimedSummary = "x",
            producedArtifacts = listOf("a.md"),
            verifiedChecks = listOf("check"),
            irreversibleSideEffects = listOf("已发送"),
        );
        assertEquals(
            BotTaskStatusMachine.Event.REVIEW_PARTIAL,
            worker.transitionFor(decision),
        );
    }
}
