package io.zer0.memory.ticker

import androidx.test.core.app.ApplicationProvider
import io.zer0.ai.core.Model
import io.zer0.memory.compile.MemoryCompiler
import io.zer0.memory.deep.DeepMemoryProcessor
import io.zer0.memory.fact.FactDbProvider
import io.zer0.memory.llm.MemoryLlmClient
import io.zer0.memory.summary.CompiledSectionDao
import io.zer0.memory.summary.CompiledSectionEntity
import io.zer0.memory.summary.DailyStateDao
import io.zer0.memory.summary.DailyStateEntity
import io.zer0.memory.summary.SessionSummaryDao
import io.zer0.memory.summary.SessionSummaryEntity
import io.zer0.memory.summary.SessionSummaryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Phase 2.3.3: [MemoryTicker] 单元测试。
 *
 * 覆盖核心调度逻辑:
 *  - 守卫逻辑: stop / isMemoryEnabled=false 时所有公开 API 立即返回 no-op
 *  - 健康状态: 初始 [getHealthStatus] 包含全部 6 个 step 且均为默认值
 *  - start/stop 生命周期: start 幂等,stop 后可重启(v1.78 修复的 bug)
 *  - turn counting: 累计轮数,未达阈值不触发后台任务
 *
 * 设计说明:
 *  - 用真实 [SessionSummaryManager] / [MemoryCompiler] / [DeepMemoryProcessor] 实例 +
 *    fake Room DAO(返回空数据)+ fake [MemoryLlmClient](抛错被 resultOf 吞)。
 *  - 因为 fake DAO 返回空 list/session,所有 compile* / rolling / deep 流水线
 *    都会在"空输入"分支 early return,不会真正调用 LLM,所以 fake LLM 抛 error 也不影响测试。
 *  - 不直接测 daily pipeline 的具体步骤,因为 daily pipeline 涉及大量异步 IO 操作,
 *    难以在 TestScope 内完整同步,这部分覆盖留给后续集成测试。
 */
@RunWith(RobolectricTestRunner::class)
class MemoryTickerTest {

    private lateinit var fakeDao: FakeSessionSummaryDao
    private lateinit var fakeCompiledDao: FakeCompiledSectionDao
    private lateinit var fakeDailyDao: FakeDailyStateDao
    private lateinit var fakeLlm: FakeMemoryLlmClient
    private lateinit var summaryManager: SessionSummaryManager
    private lateinit var compiler: MemoryCompiler
    private lateinit var deepProcessor: DeepMemoryProcessor
    private lateinit var factDbProvider: FactDbProvider

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        // 注入 Main dispatcher(本测试主要靠 runTest 内部调度, setMain 仅是为了避免 MemoryTicker
        // 内部某些路径用 Dispatchers.Main 时的 fallback 异常)
        Dispatchers.setMain(testDispatcher)

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        factDbProvider = FactDbProvider(context)

        fakeDao = FakeSessionSummaryDao()
        fakeCompiledDao = FakeCompiledSectionDao()
        fakeDailyDao = FakeDailyStateDao()
        fakeLlm = FakeMemoryLlmClient()

        summaryManager = SessionSummaryManager(fakeDao, fakeLlm)
        // fileWriter = null 让 compileDaily/rollDailyWindow/assembleWeekFromDaily 走"无 fileWriter"分支
        compiler = MemoryCompiler(fakeCompiledDao, fakeLlm, fileWriter = null)
        deepProcessor = DeepMemoryProcessor(factDbProvider, fakeLlm)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        factDbProvider.releaseAll()
    }

    // ──────────────────────────────────────────────
    //  守卫逻辑: stop / isMemoryEnabled=false 时所有 API 早返回
    // ──────────────────────────────────────────────

    @Test
    fun `notifyTurn when stopped does not launch background task`() = runTest(testDispatcher) {
        val ticker = newTicker(scope = this, isMemoryEnabled = { true })
        ticker.stop()
        advanceUntilIdle()
        val initialChildCount = coroutineContext[Job]?.children?.count() ?: 0

        ticker.notifyTurn(sessionId = "s1", messages = emptyList(), model = null)
        advanceUntilIdle()

        val afterChildCount = coroutineContext[Job]?.children?.count() ?: 0
        assertEquals("stop 后 notifyTurn 不应启动新后台任务", initialChildCount, afterChildCount)
    }

    @Test
    fun `notifyTurn when memory disabled does not launch background task`() = runTest(testDispatcher) {
        val ticker = newTicker(scope = this, isMemoryEnabled = { false })
        val initialChildCount = coroutineContext[Job]?.children?.count() ?: 0

        ticker.notifyTurn(sessionId = "s1", messages = emptyList(), model = null)
        advanceUntilIdle()

        val afterChildCount = coroutineContext[Job]?.children?.count() ?: 0
        assertEquals("disabled 时 notifyTurn 不应启动新后台任务", initialChildCount, afterChildCount)
    }

    @Test
    fun `notifySessionEnd when stopped returns immediately completed job`() = runTest(testDispatcher) {
        val ticker = newTicker(scope = this, isMemoryEnabled = { true })
        ticker.stop()
        advanceUntilIdle()

        // stop 已拦住 notifyTurn, 所以 turnCounts 为空 → notifySessionEnd 走 "count == 0" 早返回
        val job = ticker.notifySessionEnd(sessionId = "s1", messages = emptyList(), model = null)
        advanceUntilIdle()

        assertTrue("stop 后 notifySessionEnd 返回的应是 no-op Job(立即完成)", job.isCompleted)
    }

    @Test
    fun `notifySessionEnd when memory disabled returns immediately completed job`() = runTest(testDispatcher) {
        val ticker = newTicker(scope = this, isMemoryEnabled = { false })

        val job = ticker.notifySessionEnd(sessionId = "s1", messages = emptyList(), model = null)
        advanceUntilIdle()

        assertTrue("disabled 时 notifySessionEnd 应立即完成", job.isCompleted)
    }

    @Test
    fun `flushSession when stopped returns immediately completed job`() = runTest(testDispatcher) {
        val ticker = newTicker(scope = this, isMemoryEnabled = { true })
        ticker.stop()
        advanceUntilIdle()

        val job = ticker.flushSession(sessionId = "s1", messages = emptyList(), model = null)
        advanceUntilIdle()

        assertTrue("stop 后 flushSession 应立即完成", job.isCompleted)
    }

    @Test
    fun `flushSession when memory disabled returns immediately completed job`() = runTest(testDispatcher) {
        val ticker = newTicker(scope = this, isMemoryEnabled = { false })

        val job = ticker.flushSession(sessionId = "s1", messages = emptyList(), model = null)
        advanceUntilIdle()

        assertTrue("disabled 时 flushSession 应立即完成", job.isCompleted)
    }

    // ──────────────────────────────────────────────
    //  健康状态查询
    // ──────────────────────────────────────────────

    @Test
    fun `getHealthStatus initial state contains all steps with default values`() {
        // 无需 runTest, 状态查询是同步的; scope 仅用于构造 Ticker
        val scope = TestScope(testDispatcher)
        val ticker = newTicker(scope = scope, isMemoryEnabled = { true })
        val health = ticker.getHealthStatus()

        // STEP_KEYS = rollingSummary + 5 个 daily step = 6 个
        assertEquals("应包含 6 个 step", MemoryTicker.STEP_KEYS.size, health.size)

        MemoryTicker.STEP_KEYS.forEach { key ->
            assertTrue("应包含 step: $key", health.containsKey(key))
            val stepHealth = health[key]
            assertNotNull(stepHealth)
            assertEquals("初始 failCount 应为 0", 0, stepHealth!!.failCount)
            assertEquals("初始 lastSuccessAt 应为 null", null, stepHealth.lastSuccessAt)
            assertEquals("初始 lastErrorAt 应为 null", null, stepHealth.lastErrorAt)
            assertEquals("初始 lastErrorMsg 应为 null", null, stepHealth.lastErrorMsg)
        }
    }

    @Test
    fun `healthFlow initial value matches getHealthStatus after v1_0_51 fix`() {
        // v1.0.51: _healthFlow 初始化为 _health.toMap(),与 getHealthStatus() 保持一致。
        val scope = TestScope(testDispatcher)
        val ticker = newTicker(scope = scope, isMemoryEnabled = { true })

        // healthFlow 初始即包含 6 个 step (v1.0.51 修复后不再是空 map)
        assertEquals("healthFlow 初始应包含 6 个 step", 6, ticker.healthFlow.value.size)

        // getHealthStatus 同样有 6 个 step,两者一致
        assertEquals("getHealthStatus 应有 6 个 step", 6, ticker.getHealthStatus().size)
        assertEquals(
            "healthFlow 与 getHealthStatus 初始应一致",
            ticker.getHealthStatus().keys,
            ticker.healthFlow.value.keys,
        )
    }

    // ──────────────────────────────────────────────
    //  start/stop 生命周期
    // ──────────────────────────────────────────────

    @Test
    fun `start is idempotent - second call does not create additional timer job`() = runTest(testDispatcher) {
        val ticker = newTicker(scope = this, isMemoryEnabled = { true })

        ticker.start()
        advanceUntilIdle()
        val childCountAfterFirstStart = coroutineContext[Job]?.children?.count() ?: 0
        assertEquals("start() 应创建 1 个 timer job", 1, childCountAfterFirstStart)

        ticker.start()
        advanceUntilIdle()
        val childCountAfterSecondStart = coroutineContext[Job]?.children?.count() ?: 0

        assertEquals("二次 start() 不应再创建 timer job", 1, childCountAfterSecondStart)

        ticker.stop()
        advanceUntilIdle()
    }

    @Test
    fun `ticker can be restarted after stop`() = runTest(testDispatcher) {
        // v1.78: 修复 stop() 后无法重启的 bug — start() 时重置 _stopped=false
        val ticker = newTicker(scope = this, isMemoryEnabled = { true })

        ticker.start()
        advanceUntilIdle()
        val childCountAfterStart = coroutineContext[Job]?.children?.count() ?: 0
        assertEquals(1, childCountAfterStart)

        ticker.stop()
        advanceUntilIdle()
        val childCountAfterStop = coroutineContext[Job]?.children?.count() ?: 0
        assertEquals("stop() 后 timer job 应已结束", 0, childCountAfterStop)

        // 重启
        ticker.start()
        advanceUntilIdle()
        val childCountAfterRestart = coroutineContext[Job]?.children?.count() ?: 0
        assertEquals("stop 后应能重启(不创建新 timer job 视为 bug)", 1, childCountAfterRestart)

        ticker.stop()
    }

    @Test
    fun `stop sets stopped flag preventing further notifyTurn from launching tasks`() = runTest(testDispatcher) {
        val ticker = newTicker(scope = this, isMemoryEnabled = { true })
        ticker.start()
        advanceUntilIdle()

        ticker.stop()
        advanceUntilIdle()

        // 此时 notifyTurn 应被 _stopped 守卫拦住,不启动新任务
        val childCountBefore = coroutineContext[Job]?.children?.count() ?: 0
        assertEquals("stop 后应无后台 timer", 0, childCountBefore)

        ticker.notifyTurn(sessionId = "s1", messages = emptyList(), model = null)
        advanceUntilIdle()

        val childCountAfter = coroutineContext[Job]?.children?.count() ?: 0
        assertEquals("stop 后 notifyTurn 不应启动新任务", 0, childCountAfter)
    }

    // ──────────────────────────────────────────────
    //  turn counting
    // ──────────────────────────────────────────────

    @Test
    fun `notifyTurn accumulates count under threshold without launching summary task`() = runTest(testDispatcher) {
        val ticker = newTicker(scope = this, isMemoryEnabled = { true })
        // 注意:不调用 start(),避免 timer job 干扰 childCount 统计

        // 通知 9 次(TURNS_PER_SUMMARY - 1),不达阈值
        repeat(MemoryTicker.TURNS_PER_SUMMARY - 1) {
            ticker.notifyTurn(sessionId = "s1", messages = emptyList(), model = null)
        }
        advanceUntilIdle()

        // 因为 fake DAO 返回空, doDaily 在 checkDailyJob 中会触发但很快跑完(空数据)
        // 但 deepMemory/compileDaily 等因 fileWriter=null / 空 sessions 会快速 return
        // 关键验证: fakeLlm.callTextCount 应为 0(没触发 rollingSummary 因为没到阈值)
        assertEquals(
            "未达 TURNS_PER_SUMMARY 阈值时不应调用 LLM",
            0,
            fakeLlm.callTextCount,
        )

        // cleanup
        ticker.stop()
        advanceUntilIdle()
    }

    @Test
    fun `notifyTurn at threshold triggers rollingSummary without throwing`() = runTest(testDispatcher) {
        val ticker = newTicker(scope = this, isMemoryEnabled = { true })

        // 通知到阈值,触发 rollingSummary
        repeat(MemoryTicker.TURNS_PER_SUMMARY) {
            ticker.notifyTurn(sessionId = "s2", messages = emptyList(), model = null)
        }
        advanceUntilIdle()

        // 因为 fake messages 为空,convText 为空,rollingSummary 会 early return 不调 LLM
        // compileToday 也会被 doCompileTodayAndAssemble 触发, sessions 为空时也 early return
        // 验证:即使达阈值触发,空对话也不应抛异常且不应真正调用 LLM(callTextCount=0)
        assertEquals(
            "达阈值但空对话时,rollingSummary 应 early return 不调 LLM",
            0,
            fakeLlm.callTextCount,
        )

        ticker.stop()
        advanceUntilIdle()
    }

    // ──────────────────────────────────────────────
    //  helpers
    // ──────────────────────────────────────────────

    private fun newTicker(scope: CoroutineScope, isMemoryEnabled: () -> Boolean): MemoryTicker {
        return MemoryTicker(
            summaryManager = summaryManager,
            compiler = compiler,
            deepProcessor = deepProcessor,
            dailyStateDao = fakeDailyDao,
            getResetAt = { null },
            isMemoryEnabled = isMemoryEnabled,
            scope = scope,
            runtimeContext = MemoryRuntimeContext(getConfig = { MemoryConfig() }),
        )
    }
}

// ──────────────────────────────────────────────
//  Fakes
// ──────────────────────────────────────────────

/** Fake [SessionSummaryDao]: 全部返回空数据,记录调用次数便于断言。 */
private class FakeSessionSummaryDao : SessionSummaryDao {
    override suspend fun upsert(entity: SessionSummaryEntity) {}
    override suspend fun get(sessionId: String): SessionSummaryEntity? = null
    override suspend fun getAll(): List<SessionSummaryEntity> = emptyList()
    override suspend fun getInRange(startISO: String, endISO: String, since: String?, assistantId: String?): List<SessionSummaryEntity> = emptyList()
    override suspend fun getDirty(): List<SessionSummaryEntity> = emptyList()
    override suspend fun markProcessed(sessionId: String, now: String) {}
    override suspend fun deleteAll() {}
    override suspend fun deleteById(sessionId: String) {}
}

/** Fake [CompiledSectionDao]: 内存 map 存储,空实现。 */
private class FakeCompiledSectionDao : CompiledSectionDao {
    private val store = mutableMapOf<String, CompiledSectionEntity>()
    override suspend fun upsert(entity: CompiledSectionEntity) {
        store[entity.sectionKey] = entity
    }
    override suspend fun get(key: String): CompiledSectionEntity? = store[key]
    override suspend fun getAll(): List<CompiledSectionEntity> = store.values.toList()
    override suspend fun updateContent(key: String, content: String, fingerprint: String?, now: String) {
        store[key] = CompiledSectionEntity(sectionKey = key, content = content, fingerprint = fingerprint, updatedAt = now)
    }
    override suspend fun clearAll(now: String) {
        store.keys.toList().forEach { k ->
            store[k] = CompiledSectionEntity(sectionKey = k, content = "", fingerprint = null, updatedAt = now)
        }
    }
    override suspend fun clearByKey(key: String, now: String) {
        store[key] = CompiledSectionEntity(sectionKey = key, content = "", fingerprint = null, updatedAt = now)
    }
    override suspend fun deleteAll() {
        store.clear()
    }
}

/** Fake [DailyStateDao]: 返回 null 让 daily pipeline 视为"无断点续跑"。 */
private class FakeDailyStateDao : DailyStateDao {
    @Volatile private var entity: DailyStateEntity? = null
    override suspend fun upsert(entity: DailyStateEntity) {
        this.entity = entity
    }
    override suspend fun get(): DailyStateEntity? = entity
    override suspend fun deleteAll() {
        entity = null
    }
}

/** Fake [MemoryLlmClient]: 记录调用次数,返回空串(被吞错时也不影响流程)。 */
private class FakeMemoryLlmClient : MemoryLlmClient {
    @Volatile var callTextCount: Int = 0
    override suspend fun callText(
        systemPrompt: String,
        userContent: String,
        model: Model?,
        temperature: Float,
        maxTokens: Int,
        timeoutMs: Long,
    ): String {
        callTextCount++
        // 返回空串让 MemoryCompiler 走 "result 为空 → SKIPPED" 分支
        // 这样 doCompileTodayAndAssemble/doDaily 都能快速完成
        return ""
    }
}
