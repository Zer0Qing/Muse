package io.zer0.muse.data.agentbot

import io.zer0.muse.computenode.WatcherSpec
import io.zer0.muse.data.computenode.RemoteComputeNodeEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
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
        assertEquals(RemoteWatcherBinding("node-1", WATCHER_ID).encode(), dao.getById(WATCHER_ID)?.remoteWatcherId)
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

    @Test
    fun `manual check and scheduled check share a claim and only collect once`() = runTest {
        val dao = FakeWatcherDao()
        val collector = object : WatcherOutputCollector {
            var calls = 0
            override suspend fun collect(watcher: AgentWatcherEntity): String {
                calls += 1
                delay(100)
                return "same"
            }
        }
        val engine = AgentWatcherEngine(
            dao = dao,
            collector = collector,
            announcer = RecordingAnnouncer(),
            notifier = RecordingNotifier(),
            clock = { 1_000L },
        )
        dao.upsert(watcher(lastFingerprint = AgentWatcherEngine.fingerprintOf("same"), nextCheckAt = 0L))

        val scheduled = async { engine.checkDue(at = 1_000L).single() }
        val manual = async { engine.checkNow(WATCHER_ID) }
        val outcomes = listOf(scheduled.await(), manual.await())

        assertEquals(1, collector.calls)
        assertTrue(outcomes.any { it is WatcherCheckOutcome.Unchanged })
        assertTrue(outcomes.any { it is WatcherCheckOutcome.Skipped })
    }

    @Test
    fun `pause revokes on original cloud node and retains pending revocation on failure`() = runTest {
        val dao = FakeWatcherDao()
        val cloud = FakeCloudHost(RemoteComputeNodeEntity(nodeId = "node-original", status = "ONLINE"))
        val engine = AgentWatcherEngine(
            dao = dao,
            collector = FakeCollector(),
            announcer = RecordingAnnouncer(),
            notifier = RecordingNotifier(),
            cloudHost = cloud,
            clock = { 2_000L },
        )
        dao.upsert(watcher(remoteWatcherId = RemoteWatcherBinding("node-original", "remote-1").encode()))

        cloud.removeResult = false
        assertTrue(!engine.pause(WATCHER_ID))
        assertEquals(AgentWatcherStatus.PAUSED, dao.getById(WATCHER_ID)?.status)
        assertEquals(RemoteWatcherBinding("node-original", "remote-1").encode(), dao.getById(WATCHER_ID)?.remoteWatcherId)

        cloud.removeResult = true
        engine.reconcilePendingRemoteRevocations()
        assertEquals("", dao.getById(WATCHER_ID)?.remoteWatcherId)
        assertEquals("node-original", cloud.lastRemovedNodeId)
    }

    @Test
    fun `legacy cloud binding refuses local execution when original node is unknown`() = runTest {
        val dao = FakeWatcherDao()
        val collector = FakeCollector(output = "must not execute")
        val engine = AgentWatcherEngine(
            dao = dao,
            collector = collector,
            announcer = RecordingAnnouncer(),
            notifier = RecordingNotifier(),
            cloudHost = FakeCloudHost(RemoteComputeNodeEntity(nodeId = "different-node", status = "ONLINE")),
            clock = { 1_000L },
        )
        dao.upsert(watcher(computeTarget = "cloud", remoteWatcherId = "legacy-remote-id", nextCheckAt = 0L))

        val outcome = engine.checkNow(WATCHER_ID)

        assertTrue(outcome is WatcherCheckOutcome.Failed)
        assertEquals(0, collector.calls)
    }

    @Test
    fun `remote watcher binding codec preserves node and remote id and rejects legacy value`() {
        val encoded = RemoteWatcherBinding("node.with.separator", "remote-watcher-1").encode()

        assertEquals(RemoteWatcherBinding("node.with.separator", "remote-watcher-1"), RemoteWatcherBinding.decode(encoded))
        assertNull(RemoteWatcherBinding.decode("legacy-watcher-id"))
    }

    @Test
    fun `pause waits for active check and is not overwritten by its stale watcher snapshot`() = runTest {
        val dao = FakeWatcherDao()
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val collector = object : WatcherOutputCollector {
            override suspend fun collect(watcher: AgentWatcherEntity): String {
                started.complete(Unit)
                finish.await()
                return "baseline"
            }
        }
        val engine = AgentWatcherEngine(
            dao = dao,
            collector = collector,
            announcer = RecordingAnnouncer(),
            notifier = RecordingNotifier(),
            clock = { 1_000L },
        )
        dao.upsert(watcher(nextCheckAt = 0L))
        val check = async { engine.checkNow(WATCHER_ID) }
        started.await()
        val pause = async { engine.pause(WATCHER_ID) }
        finish.complete(Unit)

        check.await()
        assertTrue(pause.await())
        assertEquals(AgentWatcherStatus.PAUSED, dao.getById(WATCHER_ID)?.status)
    }

    @Test
    fun `failed notification retries stored announcement without recollecting or calling model`() = runTest {
        val dao = FakeWatcherDao()
        val collector = FakeCollector(output = "v2")
        val announcer = RecordingAnnouncer(reply = "价格下降")
        var notificationCalls = 0
        val notifier = object : WatcherNotificationSink {
            override fun notify(watcher: AgentWatcherEntity, message: String) {
                notificationCalls += 1
                if (notificationCalls == 1) error("notification service unavailable")
                assertEquals("价格下降", message)
            }
        }
        val engine = AgentWatcherEngine(
            dao = dao,
            collector = collector,
            announcer = announcer,
            notifier = notifier,
            clock = { 10_000L },
        )
        dao.upsert(
            watcher(
                lastFingerprint = AgentWatcherEngine.fingerprintOf("v1"),
                lastResult = "v1",
                nextCheckAt = 0L,
            ),
        )

        val changed = engine.checkWatcher(dao.getById(WATCHER_ID)!!, at = 10_000L)
        val storedAfterFailure = dao.getById(WATCHER_ID)
        assertTrue(changed is WatcherCheckOutcome.Changed)
        assertTrue(storedAfterFailure?.lastError?.startsWith(AgentWatcherStatus.WATCHER_NOTIFY_PENDING_PREFIX) == true)
        assertEquals(1, collector.calls)
        assertEquals(1, announcer.calls)

        val restartedEngine = AgentWatcherEngine(
            dao = dao,
            collector = FakeCollector(output = "must not recollect"),
            announcer = RecordingAnnouncer(reply = "must not resummarize"),
            notifier = notifier,
            clock = { 10_001L },
        )
        restartedEngine.checkDue(at = 10_001L)

        assertEquals(2, notificationCalls)
        assertEquals(1, collector.calls)
        assertEquals(1, announcer.calls)
        assertEquals("", dao.getById(WATCHER_ID)?.lastError)
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
        remoteWatcherId: String = "",
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
        remoteWatcherId = remoteWatcherId,
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

    override fun observeByBot(botId: String): Flow<List<AgentWatcherEntity>> = flowOf(rows.values.filter { it.botId == botId })

    override fun observeBySession(sessionId: String): Flow<List<AgentWatcherEntity>> =
        flowOf(rows.values.filter { it.originSessionId == sessionId })

    override suspend fun listActive(): List<AgentWatcherEntity> = rows.values.filter { it.status == AgentWatcherStatus.ACTIVE }

    override suspend fun listPendingRemoteRevocation(): List<AgentWatcherEntity> = rows.values.filter {
        it.status in setOf(AgentWatcherStatus.PAUSED, AgentWatcherStatus.ENDED) && it.remoteWatcherId.isNotBlank()
    }

    override suspend fun listPendingNotifications(): List<AgentWatcherEntity> =
        rows.values.filter { it.lastError.startsWith(AgentWatcherStatus.WATCHER_NOTIFY_PENDING_PREFIX) }

    override suspend fun listDue(now: Long): List<AgentWatcherEntity> = rows.values
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

    override suspend fun clearPendingNotification(watcherId: String, expectedMarker: String): Int {
        val current = rows[watcherId] ?: return 0
        if (current.lastError != expectedMarker) return 0
        rows[watcherId] = current.copy(lastError = "")
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

    override suspend fun announce(watcher: AgentWatcherEntity, previousResult: String, currentResult: String): String {
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
    var removeResult: Boolean = true
    var lastRemovedNodeId: String = ""

    override suspend fun findNode(nodeId: String): RemoteComputeNodeEntity? = node?.takeIf { it.nodeId == nodeId }

    override suspend fun findOnlineNode(): RemoteComputeNodeEntity? = node

    override suspend fun upsert(node: RemoteComputeNodeEntity, spec: WatcherSpec): String {
        upsertCalls += 1
        lastSpec = spec
        return spec.watcherId
    }

    override suspend fun remove(node: RemoteComputeNodeEntity, remoteWatcherId: String): Boolean {
        lastRemovedNodeId = node.nodeId
        return removeResult
    }
}
