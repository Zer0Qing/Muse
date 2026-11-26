package io.zer0.memory.deep

import androidx.test.core.app.ApplicationProvider
import io.zer0.ai.core.Model
import io.zer0.common.ProcessWriteGate
import io.zer0.memory.fact.FactDbProvider
import io.zer0.memory.llm.MemoryLlmClient
import io.zer0.memory.runtime.DefaultMemoryWriteGate
import io.zer0.memory.runtime.MemoryScopeKey
import io.zer0.memory.runtime.MemoryWriteFence
import io.zer0.memory.runtime.MemoryWriteGate
import io.zer0.memory.summary.SessionSummaryDao
import io.zer0.memory.summary.SessionSummaryEntity
import io.zer0.memory.summary.SessionSummaryManager
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 深度记忆的写闸行为。
 *
 * 锁死的契约:
 *  - 入口被闸挡下时不发起模型调用;
 *  - 模型调用期间恢复窗口完整开合过(返回时 restoring 已复位)→ 结果不落库、
 *    session 保持 dirty(不 markProcessed),下一轮从同一 snapshot 重抽;
 *  - 进入发布阶段前才失效的栅栏要孤立已 stage 的 generation,而不是让它留在库里;
 *  - 未接闸时行为与接入前一致(照常写入并标记已处理)。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DeepMemoryProcessorWriteGateTest {

    // 每个测试方法一份独立空间:Robolectric 的 filesDir 可能跨测试/跨运行保留,
    // 用唯一空间键保证断言只看到本次写入的事实。
    private val uniqueSuffix = java.util.UUID.randomUUID().toString().take(8)
    private val sessionId = "session-deep-gate-$uniqueSuffix"
    private val spaceId = "space_deep_gate_$uniqueSuffix"
    private lateinit var provider: FactDbProvider

    private val factJson =
        """[{"fact":"用户喜欢吃苹果","tags":["偏好"],"importance":1,"category":"preference","source":"user_explicit"}]"""

    @After
    fun tearDown() {
        ProcessWriteGate.end()
        if (::provider.isInitialized) provider.releaseAll()
    }

    private fun newProvider(): FactDbProvider {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        return FactDbProvider(context).also { provider = it }
    }

    private suspend fun factCount(): Int =
        provider.getFactStore("default").getByScopeAndSpace("main", spaceId).size

    private fun dirtyEntity() = SessionSummaryEntity(
        sessionId = sessionId,
        createdAt = "2026-11-20T10:00:00Z",
        updatedAt = "2026-11-20T10:00:00Z",
        summary = "### 重要事实\n- 用户喜欢吃苹果",
        snapshot = "",
        assistantId = "",
        spaceId = spaceId,
        summaryRevision = 3,
        sourceFingerprint = "fp-1",
    )

    private fun manager(dao: SessionSummaryDao, llm: MemoryLlmClient) = SessionSummaryManager(dao, llm)

    @Test
    fun `without a gate facts are published and the session is marked processed`() = runTest {
        newProvider()
        val dao = FakeSummaryDao(dirtyEntity())
        val llm = CountingLlm(factJson)
        val processor = DeepMemoryProcessor(provider, llm)

        val added = processor.processSession(sessionId, manager(dao, llm), null, "zh-CN")

        assertEquals("无闸时保持旧行为:照常写入", 1, added)
        assertEquals("无闸时照常标记已处理", 1, dao.markProcessedCalls)
        assertEquals(1, factCount())
    }

    @Test
    fun `blocked entry skips the model call`() = runTest {
        newProvider()
        val dao = FakeSummaryDao(dirtyEntity())
        val llm = CountingLlm(factJson)
        val gate = object : MemoryWriteGate {
            override fun isBlocked(scope: MemoryScopeKey): Boolean = true
            override suspend fun <T> withWritesPaused(scope: MemoryScopeKey, block: suspend () -> T): T = block()
        }
        val processor = DeepMemoryProcessor(provider, llm, gate)

        val added = processor.processSession(sessionId, manager(dao, llm), null, "zh-CN")

        assertEquals(0, added)
        assertEquals("恢复窗口内不得调模型", 0, llm.calls)
        assertEquals(0, dao.markProcessedCalls)
    }

    @Test
    fun `restore window during the model call keeps the session dirty and writes nothing`() = runTest {
        newProvider()
        val dao = FakeSummaryDao(dirtyEntity())
        // 模型调用期间恢复窗口完整开合:返回时 restoring 已经是 false,只有入口栅栏能作证。
        val llm = CountingLlm(factJson) {
            ProcessWriteGate.begin()
            ProcessWriteGate.end()
        }
        val processor = DeepMemoryProcessor(provider, llm, DefaultMemoryWriteGate())

        val added = processor.processSession(sessionId, manager(dao, llm), null, "zh-CN")

        assertEquals("过期栅栏的结果不得认作已抽取", 0, added)
        assertEquals("被挡下就不能 markProcessed(否则该 session 永久跳过)", 0, dao.markProcessedCalls)
        assertEquals("被挡下不得写入任何事实", 0, factCount())
        assertEquals("应真的调过模型(不是入口就跳过)", 1, llm.calls)
    }

    @Test
    fun `fence expiring before publish orphans the staged generation`() = runTest {
        newProvider()
        val dao = FakeSummaryDao(dirtyEntity())
        val llm = CountingLlm(factJson)
        val gate = SecondCheckBlockingGate()
        val processor = DeepMemoryProcessor(provider, llm, gate)
        val store = provider.getFactStore("default")

        val added = processor.processSession(sessionId, manager(dao, llm), null, "zh-CN")

        assertEquals(0, added)
        assertEquals(2, gate.blocksWriteCalls)
        assertEquals("发布前被挡:不得标记已处理", 0, dao.markProcessedCalls)
        assertEquals("已 stage 的行仍在表里", 1, factCount())
        assertTrue(
            "已 stage 的 generation 必须被孤立(可由对账清理)",
            store.reconcileOrphanedProjections() >= 1,
        )
    }

    @Test
    fun `dirty batch threads the entry fence into the per-session write check`() = runTest {
        newProvider()
        val dao = FakeSummaryDao(dirtyEntity())
        val llm = CountingLlm(factJson)
        // 只在拿到入口栅栏时才拦:用来分辨批次到底有没有把 fence 传进来。
        val gate = object : MemoryWriteGate {
            override fun isBlocked(scope: MemoryScopeKey): Boolean = false
            override suspend fun <T> withWritesPaused(scope: MemoryScopeKey, block: suspend () -> T): T = block()
            override fun fence(): MemoryWriteFence = MemoryWriteFence(7)
            override fun blocksWrite(scope: MemoryScopeKey, fence: MemoryWriteFence?): Boolean = fence != null
        }
        val processor = DeepMemoryProcessor(provider, llm, gate)

        val result = processor.processDirtySessions(manager(dao, llm), null, "zh-CN")

        assertEquals("未携带入口栅栏的写入必须被拦下", 0, result.factsAdded)
        assertEquals(0, factCount())
        assertEquals(0, dao.markProcessedCalls)
    }

    /** 第一次 blocksWrite(模型返回后)放行、第二次(发布前)拦截。 */
    private class SecondCheckBlockingGate : MemoryWriteGate {
        var blocksWriteCalls = 0

        override fun isBlocked(scope: MemoryScopeKey): Boolean = false

        override suspend fun <T> withWritesPaused(scope: MemoryScopeKey, block: suspend () -> T): T = block()

        override fun fence(): MemoryWriteFence = MemoryWriteFence(42)

        override fun blocksWrite(scope: MemoryScopeKey, fence: MemoryWriteFence?): Boolean {
            blocksWriteCalls++
            return blocksWriteCalls >= 2
        }
    }

    /** 记录调用次数的 fake LLM,可在返回前插入动作(模拟恢复窗口)。 */
    private class CountingLlm(
        private val response: String,
        private val during: () -> Unit = {},
    ) : MemoryLlmClient {
        var calls = 0

        override suspend fun callText(
            systemPrompt: String,
            userContent: String,
            model: Model?,
            temperature: Float,
            maxTokens: Int,
            timeoutMs: Long,
        ): String {
            calls++
            during()
            return response
        }
    }

    /** 单行摘要的内存 DAO:只实现深度记忆路径会碰到的方法。 */
    private class FakeSummaryDao(private var entity: SessionSummaryEntity) : SessionSummaryDao {
        var markProcessedCalls = 0

        override suspend fun upsert(entity: SessionSummaryEntity) {
            this.entity = entity
        }

        override suspend fun get(sessionId: String): SessionSummaryEntity? = entity

        override suspend fun getAll(): List<SessionSummaryEntity> = listOf(entity)

        override suspend fun getInRange(
            startISO: String,
            endISO: String,
            since: String?,
            assistantId: String?,
            spaceId: String?,
        ): List<SessionSummaryEntity> = listOf(entity)

        override suspend fun getDirty(): List<SessionSummaryEntity> = listOf(entity)

        override suspend fun markProcessed(sessionId: String, now: String) {
            markProcessedCalls++
            entity = entity.copy(snapshot = entity.summary, snapshotAt = now)
        }

        override suspend fun revisionOf(sessionId: String): Long? = entity.summaryRevision

        override suspend fun bumpRevisionIfCurrent(
            sessionId: String,
            expectedRevision: Long,
            sourceFingerprint: String,
        ): Int = 0

        override suspend fun markProcessedIfCurrent(sessionId: String, expectedRevision: Long, now: String): Int {
            if (entity.summaryRevision != expectedRevision) return 0
            markProcessedCalls++
            entity = entity.copy(snapshot = entity.summary, snapshotAt = now)
            return 1
        }

        override suspend fun markBranchChanged(sessionId: String, retainedMessageCount: Int): Int = 0

        override suspend fun deleteAll() = Unit

        override suspend fun deleteById(sessionId: String) = Unit

        override suspend fun deleteByAssistant(assistantId: String): Int = 0
        override suspend fun assistantIdsForSpace(spaceId: String): List<String> = listOf(entity.assistantId)
        override suspend fun migrateSpace(sourceSpaceId: String, targetSpaceId: String): Int = 0
    }
}