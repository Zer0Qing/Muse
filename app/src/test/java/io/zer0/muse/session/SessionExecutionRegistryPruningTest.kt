package io.zer0.muse.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3: SessionExecutionRegistry 活跃/历史分离与终态裁剪测试。
 *
 * 覆盖:
 *  - 终态迁移到历史表后 snapshot/state 语义不变;
 *  - TTL 过期裁剪(注入时钟,确定性);
 *  - 容量上限按 finishedAt 由旧到新淘汰;
 *  - 活跃记录永不裁剪;
 *  - register/start/finish/fail/markCancelled 既有语义保持(重复终态、取消后晚到)。
 *
 * 运行: ./gradlew :app:testDebugUnitTest --tests "*SessionExecutionRegistryPruningTest*"
 */
class SessionExecutionRegistryPruningTest {

    private var now = 1_000L

    private fun identity(generationId: String) = GenerationIdentity(
        sessionId = "session-1",
        turnId = "turn-1",
        generationId = generationId,
        streamId = "stream-$generationId",
    )

    private fun registry(
        retentionMs: Long = 60_000L,
        maxHistory: Int = 256,
    ) = SessionExecutionRegistry(
        historyRetentionMs = retentionMs,
        maxHistoryRecords = maxHistory,
        nowMs = { now },
    )

    @Test
    fun `terminal records move to history while snapshot and state semantics stay intact`() {
        val registry = registry()
        val id = registry.register(identity("g1"), ExecutionKind.TOOL)
        assertTrue(registry.start(id))
        now = 2_000L

        assertTrue(registry.finish(id))

        // 活跃表已清空,历史表保留终态记录(诊断兼容)
        assertTrue(registry.activeSnapshot("session-1").isEmpty())
        assertEquals(1, registry.historySnapshot("session-1").size)
        assertEquals(1, registry.snapshot("session-1").size)
        assertEquals(ExecutionState.COMPLETED, registry.state(id))
        assertFalse(registry.isCurrent(identity("g1")))
        // 终态记录的 startedAt/finishedAt 来自注入时钟
        val record = registry.historySnapshot().single()
        assertEquals(1_000L, record.startedAt)
        assertEquals(2_000L, record.finishedAt)
    }

    @Test
    fun `repeated terminal transitions on same id stay idempotent`() {
        val registry = registry()
        val id = registry.register(identity("g1"), ExecutionKind.TOOL)
        registry.start(id)
        assertTrue(registry.finish(id))
        // 终态历史中再次 finish/fail 保持幂等,状态不变
        assertTrue(registry.finish(id))
        assertTrue(registry.fail(id))
        assertTrue(registry.markCancelled(id))
        assertEquals(ExecutionState.COMPLETED, registry.state(id))
        assertEquals(1, registry.historySnapshot().size)
    }

    @Test
    fun `late completion after cancel request remains cancelled in history`() {
        val registry = registry()
        val id = registry.register(identity("g1"), ExecutionKind.TOOL)
        registry.start(id)
        assertTrue(registry.requestCancel(id, "user_stop"))
        now = 5_000L

        assertTrue(registry.finish(id))

        assertEquals(ExecutionState.CANCELLED, registry.state(id))
        assertTrue(registry.activeSnapshot().isEmpty())
        assertEquals(1, registry.historySnapshot().size)
    }

    @Test
    fun `expired history is pruned by ttl`() {
        val registry = registry(retentionMs = 1_000L)
        val id = registry.register(identity("g1"), ExecutionKind.TOOL)
        registry.start(id)
        assertTrue(registry.finish(id))

        // 未过期:不裁剪
        now = 1_500L
        assertEquals(0, registry.prune())
        assertEquals(ExecutionState.COMPLETED, registry.state(id))

        // 超过 TTL:裁剪后 state/snapshot 不再可见
        now = 3_000L
        assertEquals(1, registry.prune())
        assertNull(registry.state(id))
        assertTrue(registry.snapshot().isEmpty())
    }

    @Test
    fun `history capacity evicts oldest finished records`() {
        val registry = registry(retentionMs = 1_000_000L, maxHistory = 2)
        val ids = mutableListOf<String>()
        listOf("g1", "g2", "g3").forEachIndexed { index, generation ->
            now = 1_000L + index * 1_000L
            val id = registry.register(identity(generation), ExecutionKind.LLM)
            registry.start(id)
            assertTrue(registry.finish(id))
            ids += id
        }

        // 每次终态迁移自动裁剪:容量 2,最旧的 g1 已被淘汰
        assertEquals(2, registry.historySnapshot().size)
        assertNull(registry.state(ids[0]))
        assertEquals(ExecutionState.COMPLETED, registry.state(ids[1]))
        assertEquals(ExecutionState.COMPLETED, registry.state(ids[2]))
        assertEquals(0, registry.prune())
        assertEquals(2, registry.historySnapshot().size)
    }

    @Test
    fun `active records are never pruned even with zero retention`() {
        val registry = registry(retentionMs = 0L, maxHistory = 1)
        val id = registry.register(identity("g1"), ExecutionKind.BROWSER)
        registry.start(id)
        // 另一个终态记录触发自动裁剪,但活跃记录必须保留
        val finished = registry.register(identity("g2"), ExecutionKind.TOOL)
        registry.finish(finished)

        assertEquals(0, registry.prune())
        assertEquals(ExecutionState.RUNNING, registry.state(id))
        assertEquals(1, registry.activeSnapshot().size)
    }

    @Test
    fun `terminal transition auto prunes expired history`() {
        val registry = registry(retentionMs = 100L)
        val first = registry.register(identity("g1"), ExecutionKind.TOOL)
        registry.start(first)
        assertTrue(registry.finish(first))

        now += 1_000L
        val second = registry.register(identity("g2"), ExecutionKind.LLM)
        registry.start(second)
        assertTrue(registry.finish(second))

        // 第一次 finish 的记录已被自动裁剪,只留最近一条
        assertNull(registry.state(first))
        assertEquals(ExecutionState.COMPLETED, registry.state(second))
        assertEquals(1, registry.historySnapshot().size)
    }

    @Test
    fun `session cancellation only scans active records`() {
        val registry = registry()
        val finished = registry.register(identity("g1"), ExecutionKind.TOOL)
        registry.start(finished)
        assertTrue(registry.finish(finished))

        val activeId = registry.register(identity("g1"), ExecutionKind.TOOL)
        registry.start(activeId)

        assertEquals(listOf(activeId), registry.requestCancelForSession("session-1"))
        assertEquals(ExecutionState.COMPLETED, registry.state(finished))
        assertEquals(ExecutionState.CANCEL_REQUESTED, registry.state(activeId))
        assertFalse(registry.requestCancel("does-not-exist"))
    }

    @Test
    fun `prune on unknown retention keeps fresh records`() {
        val registry = registry(retentionMs = 10_000L)
        val id = registry.register(identity("g1"), ExecutionKind.MEDIA)
        registry.start(id)
        assertTrue(registry.fail(id))

        now += 5_000L
        assertEquals(0, registry.prune())
        assertEquals(ExecutionState.FAILED, registry.state(id))
    }
}
