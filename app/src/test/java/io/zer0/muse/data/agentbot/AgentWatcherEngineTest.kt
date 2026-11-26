package io.zer0.muse.data.agentbot

import io.zer0.muse.computenode.WatcherSpec
import io.zer0.muse.data.computenode.RemoteComputeNodeEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentWatcherEngineTest {

    @Test
    fun `unchanged output makes zero model calls and only advances check time`() = runTest {
        val dao = FakeWatcherDao()
        val collector = FakeCollector(output = "v1 \r\n")
        val announcer = RecordingAnnouncer()
        val notifier = RecordingNotifier()
        val engine = engine(dao, collector, announcer, notifier)
        dao.upsert(
            watcher(
                lastFingerprint = AgentWatcherEngine.fingerprintOf("v1"),
                lastResult = "v1",
                lastCheckedAt = 10L,
                nextCheckAt = 10L,
                intervalMinutes = 5,
            ),
        )

        val outcome = engine.checkWatcher(dao.getById(WATCHER_ID)!!, at = 1_000L) // 测试库刚写入的行，键必然存在

        assertTrue(outcome is WatcherCheckOutcome.Unchanged)
        assertEquals(0, announcer.calls)
        assertTrue(notifier.messages.isEmpty())
        val row = dao.getById(WATCHER_ID)!! // 同一测试内刚断言过存在
        assertEquals(1_000L, row.lastCheckedAt)
        assertEquals(1_000L + 5 * 60_000L, row.nextCheckAt)
        assertEquals("v1", row.lastResult)
    }

    @Test
    fun `changed output announces once and writes one notification`() = runTest {
        val dao = FakeWatcherDao()
        val collector = FakeCollector(output = "v2")
        val announcer = RecordingAnnouncer(reply = "关注的页面新增了 2 条")
        val notifier = RecordingNotifier()
        val engine = engine(dao, collector, announcer, notifier)
        dao.upsert(
            watcher(
                lastFingerprint = AgentWatcherEngine.fingerprintOf("v1"),
                lastResult = "v1",
                lastCheckedAt = 10L,
                nextCheckAt = 10L,
            ),
        )

        val outcome = engine.checkWatcher(dao.getById(WATCHER_ID)!!, at = 2_000L) // 测试库刚写入的行，键必然存在

        assertTrue(outcome is WatcherCheckOutcome.Changed)
        assertEquals("关注的页面新增了 2 条", (outcome as WatcherCheckOutcome.Changed).announcement)
        assertEquals(1, announcer.calls)
        assertEquals(listOf("关注的页面新增了 2 条"), notifier.messages)
        val row = dao.getById(WATCHER_ID)!! // 同一测试内刚断言过存在
        assertEquals(AgentWatcherEngine.fingerprintOf("v2"), row.lastFingerprint)
        assertEquals("v2", row.lastResult)
        assertEquals("", row.lastError)
    }

    @Test
    fun `first check records a baseline without waking the model`() = runTest {
        val dao = FakeWatcherDao()
        val announcer = RecordingAnnouncer()
        val notifier = RecordingNotifier()
        val engine = engine(dao, FakeCollector(output = "first snapshot"), announcer, notifier)
        dao.upsert(watcher(lastFingerprint = "", nextCheckAt = 0L))

        val outcome = engine.checkWatcher(dao.getById(WATCHER_ID)!!, at = 3_000L) // 测试库刚写入的行，键必然存在

        assertTrue(outcome is WatcherCheckOutcome.Baseline)
        assertEquals(0, announcer.calls)
        assertTrue(notifier.messages.isEmpty())
        assertEquals(
            AgentWatcherEngine.fingerprintOf("first snapshot"),
            dao.getById(WATCHER_ID)?.lastFingerprint,
        )
    }

    @Test
    fun `normalization ignores line endings and trailing whitespace`() {
        val a = AgentWatcherEngine.fingerprintOf("line1\r\nline2   \r\n")
        val b = AgentWatcherEngine.fingerprintOf("line1\nline2")
        assertEquals(b, a)
        assertEquals("line1\nline2", AgentWatcherEngine.normalizeOutput("\nline1  \r\nline2\n\n"))
    }

    @Test
    fun `cloud watcher is delegated to the online node without local collection`() = runTest {
        val dao = FakeWatcherDao()
        val collector = FakeCollector(output = "local should not run")
        val announcer = RecordingAnnouncer()
        val cloud = FakeCloudHost(node = RemoteComputeNodeEntity(nodeId = "node-1", status = "ONLINE"))
        val engine = AgentWatcherEngine(
            dao = dao,
            collector = collector,
            announcer = announcer,
            notifier = RecordingNotifier(),
            cloudHost = cloud,
            clock = { 5_000L },
        )
        dao.upsert(watcher(computeTarget = "cloud", nextCheckAt = 0L))

        val outcome = engine.checkWatcher(dao.getById(WATCHER_ID)!!, at = 5_000L) // 测试库刚写入的行，键必然存在

        assertTrue(outcome is WatcherCheckOutcome.Delegated)
        assertEquals("node-1", (outcome as WatcherCheckOutcome.Delegated).nodeId)
        assertEquals(0, collector.calls)
        assertEquals(0, announcer.calls)
        assertEquals(1, cloud.upsertCalls)
        assertEquals(WATCHER_ID, cloud.lastSpec?.watcherId)
        assertEquals(WATCHER_ID, dao.getById(WATCHER_ID)?.remoteWatcherId)
    }

    @Test
    fun `collector failure records lastError and never calls the model`() = runTest {
        val dao = FakeWatcherDao()
        val announcer = RecordingAnnouncer()
        val engine = engine(
            dao = dao,
            collector = FakeCollector(failure = IllegalStateException("network down")),
            announcer = announcer,
            notifier = RecordingNotifier(),
        )
        dao.upsert(watcher(lastFingerprint = "", nextCheckAt = 0L))

        val outcome = engine.checkWatcher(dao.getById(WATCHER_ID)!!, at = 6_000L) // 测试库刚写入的行，键必然存在

        assertTrue(outcome is WatcherCheckOutcome.Failed)
        assertEquals(0, announcer.calls)
        assertEquals("network down", dao.getById(WATCHER_ID)?.lastError)
        assertEquals(6_000L, dao.getById(WATCHER_ID)?.lastCheckedAt)
    }

    @Test
    fun `create watcher persists an active row and schedules the next check`() = runTest {
        val dao = FakeWatcherDao()
        val engine = engine(dao, FakeCollector(), RecordingAnnouncer(), RecordingNotifier())

        val created = engine.createWatcher(
            botId = "bot-1",
            originSessionId = "session-1",
            title = "降价提醒",
            instruction = "盯住商品价格",
            script = "print('price')",
            intervalMinutes = 15,
        )

        assertTrue(created != null)
        assertEquals(AgentWatcherStatus.ACTIVE, created?.status)
        assertEquals(15, created?.intervalMinutes)
        assertEquals(15 * 60_000L, created?.nextCheckAt)
        assertEquals("bot-1", dao.getById(created!!.watcherId)?.botId) // created 刚经非空断言，watcherId 必然存在
    }

    @Test
    fun `blank script is rejected instead of creating a dead watcher`() = runTest {
        val engine = engine(FakeWatcherDao(), FakeCollector(), RecordingAnnouncer(), RecordingNotifier())

        val created = engine.createWatcher(
            botId = "bot-1",
            originSessionId = "session-1",
            title = "空脚本",
            instruction = "什么也不做",
            script = "   ",
        )

        assertNull(created)
    }

    @Test
    fun `checkDue only inspects active watchers whose next check is due`() = runTest {
        val dao = FakeWatcherDao()
        val collector = FakeCollector(output = "same")
        val engine = engine(dao, collector, RecordingAnnouncer(), RecordingNotifier())
        dao.upsert(watcher(watcherId = "due", nextCheckAt = 100L, lastFingerprint = AgentWatcherEngine.fingerprintOf("same")))
        dao.upsert(watcher(watcherId = "future", nextCheckAt = 10_000L))
        dao.upsert(watcher(watcherId = "paused", nextCheckAt = 0L, status = AgentWatcherStatus.PAUSED))

        val outcomes = engine.checkDue(at = 1_000L)

        assertEquals(listOf("due"), outcomes.map { it.watcherId })
        assertEquals(1, collector.calls)
    }

    private fun engine(
        dao: FakeWatcherDao,
        collector: WatcherOutputCollector,
        announcer: WatcherAnnouncer,
        notifier: WatcherNotificationSink,
    ): AgentWatcherEngine = AgentWatcherEngine(
        dao = dao,
        collector = collector,
        announcer = announcer,
        notifier = notifier,
        cloudHost = null,
        clock = { 0L },
    )

    private fun watcher(
        watcherId: String = WATCHER_ID,
        lastFingerprint: String = "",
        lastResult: String = "",
        lastCheckedAt: Long = 0L,
        nextCheckAt: Long = 0L,
        intervalMinutes: Int = 30,
        computeTarget: String = "local",
        status: String = AgentWatcherStatus.ACTIVE,
    ): AgentWatcherEntity = AgentWatcherEntity(
        watcherId = watcherId,
        botId = "bot-1",
        originSessionId = "session-1",
        title = "价格跟进",
        instruction = "盯住价格变化",
        script = "print('price')",
        scriptKind = "python",
        computeTarget = computeTarget,
        intervalMinutes = intervalMinutes,
        lastFingerprint = lastFingerprint,
        lastResult = lastResult,
        lastCheckedAt = lastCheckedAt,
        nextCheckAt = nextCheckAt,
        status = status,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private companion object {
        const val WATCHER_ID = "watcher-1"
    }
}

private class FakeWatcherDao : AgentWatcherDao {

    private val rows = linkedMapOf<String, AgentWatcherEntity>()

    override suspend fun upsert(watcher: AgentWatcherEntity) {
        rows[watcher.watcherId] = watcher
    }

    override suspend fun upsertAll(watchers: List<AgentWatcherEntity>) {
        watchers.forEach { rows[it.watcherId] = it }
    }

    override suspend fun getById(watcherId: String): AgentWatcherEntity? = rows[watcherId]

    override fun observeAll(): Flow<List<AgentWatcherEntity>> = flowOf(rows.values.toList())

    override fun observeByBot(botId: String): Flow<List<AgentWatcherEntity>> =
        flowOf(rows.values.filter { it.botId == botId })

    override fun observeBySession(sessionId: String): Flow<List<AgentWatcherEntity>> =
        flowOf(rows.values.filter { it.originSessionId == sessionId })

    override suspend fun listActive(): List<AgentWatcherEntity> =
        rows.values.filter { it.status == AgentWatcherStatus.ACTIVE }

    override suspend fun listDue(now: Long): List<AgentWatcherEntity> =
        rows.values
            .filter { it.status == AgentWatcherStatus.ACTIVE && it.nextCheckAt <= now }
            .sortedBy { it.nextCheckAt }

    override suspend fun recordCheck(
        watcherId: String,
        fingerprint: String,
        result: String,
        checkedAt: Long,
        nextCheckAt: Long,
        error: String,
    ): Int {
        val current = rows[watcherId] ?: return 0
        rows[watcherId] = current.copy(
            lastFingerprint = fingerprint,
            lastResult = result,
            lastCheckedAt = checkedAt,
            nextCheckAt = nextCheckAt,
            lastError = error,
            updatedAt = checkedAt,
        )
        return 1
    }

    override suspend fun setStatus(watcherId: String, status: String, updatedAt: Long): Int {
        val current = rows[watcherId] ?: return 0
        rows[watcherId] = current.copy(status = status, updatedAt = updatedAt)
        return 1
    }

    override suspend fun deleteById(watcherId: String) {
        rows.remove(watcherId)
    }

    override suspend fun deleteBySession(sessionId: String) {
        rows.values.removeAll { it.originSessionId == sessionId }
    }

    override suspend fun deleteByAssistant(assistantId: String) {
        // 测试不涉及助手级联
    }

    override suspend fun getAll(): List<AgentWatcherEntity> = rows.values.toList()

    override suspend fun deleteAll() {
        rows.clear()
    }
}

private class FakeCollector(
    private val output: String = "",
    private val failure: Exception? = null,
) : WatcherOutputCollector {

    var calls: Int = 0
        private set

    override suspend fun collect(watcher: AgentWatcherEntity): String {
        calls += 1
        failure?.let { throw it }
        return output
    }
}

private class RecordingAnnouncer(
    private val reply: String = "有新变化",
) : WatcherAnnouncer {

    var calls: Int = 0
        private set

    override suspend fun announce(
        watcher: AgentWatcherEntity,
        previousResult: String,
        currentResult: String,
    ): String {
        calls += 1
        return reply
    }
}

private class RecordingNotifier : WatcherNotificationSink {

    val messages = mutableListOf<String>()

    override fun notify(watcher: AgentWatcherEntity, message: String) {
        messages += message
    }
}

private class FakeCloudHost(
    private val node: RemoteComputeNodeEntity?,
) : WatcherCloudHost {

    var upsertCalls: Int = 0
        private set
    var lastSpec: WatcherSpec? = null
        private set

    override suspend fun findOnlineNode(): RemoteComputeNodeEntity? = node

    override suspend fun upsert(node: RemoteComputeNodeEntity, spec: WatcherSpec): String {
        upsertCalls += 1
        lastSpec = spec
        return spec.watcherId
    }
}
