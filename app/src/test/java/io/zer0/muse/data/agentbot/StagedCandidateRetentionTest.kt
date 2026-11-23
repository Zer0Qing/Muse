package io.zer0.muse.data.agentbot

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 候选不能随事件保留期静默消失。
 *
 * 候选本体是文件，页面靠 `bot_events` 里那条带 `[staged=]` 的行才能找到它。
 * 保留策略如果只看时间删行，就出现最糟的形态：候选仍 PENDING，用户却看不到，
 * 而文件还好端端躺在磁盘上 —— 没有任何入口能把它取出来。
 *
 * 反过来，候选被应用/丢弃之后，那行引用就只剩噪音，必须能在保留期后被收走，
 * 否则每次工具产出都在事件表里留一条永不清除的行。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class StagedCandidateRetentionTest {

    private val day = 24L * 3600_000L
    private var now = START
    private lateinit var graph: AgentBotFlowTestGraph

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        // 注入时钟：保留期是 7 天，真等不可能，只能把时间拨过去。
        graph = AgentBotFlowTestGraph(context) { now }
    }

    @After
    fun tearDown() {
        graph.close()
    }

    /** 造一条已消费的"结果就绪"事件，payload 末尾带真实暂存 id。 */
    private suspend fun seedConsumedResult(): String {
        val stagedId = graph.staging.stage(stagedCandidate("notes.md"))
        graph.repository.appendEvent(
            botId = "assistant-a",
            originSessionId = "session-1",
            taskId = "task-a",
            runId = "",
            kind = BotEventKind.TASK_RESULT_READY,
            dedupeKey = "result-1",
            payloadRef = "做完了\n[staged=$stagedId]",
        )
        graph.repository.markConsumed(graph.db.botEventDao().getAll().single().eventId)
        return stagedId
    }

    @Test
    fun `a pending candidate survives the event retention window`() = runTest {
        val stagedId = seedConsumedResult()
        now += 30 * day

        graph.repository.pruneEvents()

        assertEquals("候选还在暂存区，引用事件不能被保留策略删掉", 1, graph.db.botEventDao().getAll().size)
        assertEquals(
            "页面必须还能从事件里读到这条候选",
            setOf(stagedId),
            graph.repository.observeScope("assistant-a", "session-1").first().candidateIds,
        )
    }

    @Test
    fun `a candidate reference is pruned once the staged file is gone`() = runTest {
        val stagedId = seedConsumedResult()
        // 候选被应用或丢弃：文件不在了，事件行只剩一条无用引用。
        graph.staging.discard(stagedId)
        now += 30 * day

        val pruned = graph.applier.pruneSettledStagedEvents()

        assertEquals("文件不在了，超过保留期就可以清理", 1, pruned)
        assertTrue("事件行应当被清掉", graph.db.botEventDao().getAll().isEmpty())
    }

    @Test
    fun `the settle hook keeps references whose candidate still exists`() = runTest {
        seedConsumedResult()
        now += 30 * day

        assertEquals(
            "文件还在时清理必须一条都不删，否则候选会从页面消失",
            0,
            graph.applier.pruneSettledStagedEvents(),
        )
        assertEquals(1, graph.db.botEventDao().getAll().size)
    }

    @Test
    fun `a staging read failure keeps every reference`() = runTest {
        seedConsumedResult()
        now += 30 * day
        // 暂存区读不出来时，"文件还在不在"是未知的 —— 未知就不能删。
        graph.stagingDir.deleteRecursively()
        graph.stagingDir.writeText("not a directory")

        assertEquals(0, graph.applier.pruneSettledStagedEvents())
        assertEquals("读不到暂存区时宁可多留，也不能误删候选索引", 1, graph.db.botEventDao().getAll().size)
    }

    private companion object {
        /** 固定起点。consumedAt 必须大于 0，否则 SQL 会把行当成"未消费"。 */
        const val START = 1_700_000_000_000L
    }
}
