package io.zer0.muse.data.agentbot

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.mockk
import io.zer0.muse.computenode.ComputeNodeEvent
import io.zer0.muse.computenode.ComputeNodeManager
import io.zer0.muse.computenode.MuseNodeClient
import io.zer0.muse.computenode.NodeEndpointOverride
import io.zer0.muse.computenode.NodeHealth
import io.zer0.muse.computenode.OutboxEvent
import io.zer0.muse.computenode.SshTunnelManager
import io.zer0.muse.data.SecureKeyCipher
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.computenode.ComputeNodeMode
import io.zer0.muse.data.computenode.ComputeNodeStatus
import io.zer0.muse.data.computenode.RemoteComputeNodeEntity
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.data.session.SessionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ComputeNodeEventBridgeTest {

    private lateinit var context: Context
    private lateinit var db: MuseDb
    private lateinit var repo: AgentBotRepository
    private lateinit var sessionRepo: SessionRepository
    private lateinit var publication: PublicationService
    private lateinit var server: MockWebServer
    private lateinit var client: MuseNodeClient
    private lateinit var tunnelManager: SshTunnelManager
    private lateinit var nodeScope: CoroutineScope
    private lateinit var nodeManager: ComputeNodeManager
    private val notified = mutableListOf<Pair<String, String>>()
    private var clock = 10_000L

    private val passThroughCipher = object : SecureKeyCipher {
        override suspend fun encrypt(plain: String): String = plain
        override suspend fun decrypt(stored: String): String = stored
        override suspend fun decryptOrNull(stored: String): String = stored
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, MuseDb::class.java)
            .allowMainThreadQueries()
            .build()
        repo = AgentBotRepository(
            taskDao = db.botTaskDao(),
            runDao = db.taskRunDao(),
            stepDao = db.taskStepDao(),
            eventDao = db.botEventDao(),
            approvalDao = db.approvalRequestDao(),
            publicationDao = db.publicationOutboxDao(),
            bindingDao = db.botBindingDao(),
            cardDao = db.botMessageCardDao(),
            routineDao = db.botRoutineDao(),
            now = { clock },
        )
        sessionRepo = SessionRepository(
            sessionDao = db.sessionDao(),
            messageDao = db.messageDao(),
            database = db,
            context = context,
            messageImageStore = io.zer0.muse.data.session.MessageImageStore(File(context.cacheDir, "bridge_test_images")),
        )
        publication = PublicationService(db, repo, sessionRepo)
        server = MockWebServer()
        server.start()
        val okHttp = OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).build()
        nodeScope = CoroutineScope(SupervisorJob())
        tunnelManager = SshTunnelManager(nodeScope, passThroughCipher)
        val baseUrl = server.url("/").toString().trimEnd('/')
        client = MuseNodeClient(
            baseClient = okHttp,
            endpointOverride = NodeEndpointOverride(baseUrl = baseUrl, bearerToken = "test-token"),
            cipher = passThroughCipher,
        )
        nodeManager = ComputeNodeManager(
            dao = db.remoteComputeNodeDao(),
            client = client,
            scope = nodeScope,
            clock = { clock },
        )
        notified.clear()
    }

    @After
    fun tearDown() {
        nodeManager.close()
        tunnelManager.closeAll()
        nodeScope.cancel()
        server.shutdown()
        db.close()
    }

    private fun newBridge(ownerResolver: suspend (String, OutboxEvent) -> OutboxOwner? = { _, _ -> null }): ComputeNodeEventBridge =
        ComputeNodeEventBridge(
            context = context,
            computeNodeManager = nodeManager,
            nodeDao = db.remoteComputeNodeDao(),
            repository = repo,
            publication = publication,
            settings = SettingsRepository(context, mockk(relaxed = true)),
            notifier = { binding, text -> notified += binding.botId to text },
            clock = { clock },
            outboxOwnerResolver = ownerResolver,
        )

    private suspend fun insertSampleNode(nodeId: String = "node-1", name: String = "轻量测试机") {
        db.remoteComputeNodeDao().upsert(
            RemoteComputeNodeEntity(
                nodeId = nodeId,
                name = name,
                connectionMode = ComputeNodeMode.SSH_TUNNEL.wireValue,
                host = "127.0.0.1",
                sshPort = 22,
                daemonPort = 18765,
                username = "ubuntu",
                encryptedCredential = "cred",
                encryptedNodeToken = "test-token",
                status = ComputeNodeStatus.ONLINE.wireValue,
            ),
        )
    }

    @Test
    fun `reconnect required writes card and online event resolves it`() = runTest {
        val sessionId = sessionRepo.createAgentSession("default")
        repo.ensureBinding("default", sessionId)
        val bridge = newBridge()

        bridge.handleEvent(
            ComputeNodeEvent.ReconnectRequired(
                nodeId = "node-1",
                payload = ReconnectCardPayload(
                    action = ReconnectActionKind.COMPUTE_NODE,
                    taskId = "node-1",
                    reason = "节点心跳超时",
                ),
            ),
        )

        val cardId = ComputeNodeEventBridge.reconnectCardId("default", "node-1")
        val created = db.botMessageCardDao().getById(cardId)
        assertNotNull(created)
        assertEquals(BotCardKind.RECONNECT, created?.cardKind)
        assertEquals(BotCardStatus.ACTIVE, created?.status)
        assertEquals("node-1", created?.taskId)

        bridge.handleEvent(
            ComputeNodeEvent.Online(
                nodeId = "node-1",
                health = NodeHealth(nodeVersion = "v22.0.0"),
            ),
        )

        val resolved = db.botMessageCardDao().getById(cardId)
        assertEquals(BotCardStatus.RESOLVED, resolved?.status)
    }

    @Test
    fun `outbox received publishes idempotently and acknowledges server`() = runTest {
        val sessionId = sessionRepo.createAgentSession("default")
        repo.ensureBinding("default", sessionId)
        insertSampleNode("node-1", "Tokyo-VPS")
        repeat(4) { server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true,"acked":1}""")) }
        val bridge = newBridge { _, _ -> OutboxOwner("default", sessionId) }

        val events = listOf(
            OutboxEvent(
                eventId = "evt-1",
                watcherId = "watch-price",
                outputTail = "价格降到 199 元",
            ),
            OutboxEvent(
                eventId = "evt-2",
                runId = "run-9",
                outputTail = "脚本执行完成",
            ),
        )

        bridge.handleEvent(ComputeNodeEvent.OutboxReceived("node-1", events))
        // 重复投递同一批事件：消息不重复，通知也只在首次新建时发一次。
        bridge.handleEvent(ComputeNodeEvent.OutboxReceived("node-1", events))

        val messages = db.messageDao().observeBySession(sessionId).first()
        assertEquals(2, messages.size)
        assertTrue(messages.all { it.content.contains("Tokyo-VPS") })
        assertTrue(messages.any { it.content.contains("watch-price") && it.content.contains("价格降到 199 元") })
        assertTrue(messages.any { it.content.contains("run-9") })
        assertEquals(2, notified.size)
        assertEquals(4, server.requestCount)
        val ackBodies = List(4) { server.takeRequest() }.map { request ->
            assertEquals("/outbox/ack", request.path)
            request.body.readUtf8()
        }
        assertTrue(ackBodies[0].contains("evt-1"))
        assertTrue(ackBodies[1].contains("evt-2"))
    }

    @Test
    fun `outbox event with unresolved owner is neither published nor acknowledged`() = runTest {
        val sessionId = sessionRepo.createAgentSession("default")
        repo.ensureBinding("default", sessionId)
        insertSampleNode()

        newBridge().handleEvent(
            ComputeNodeEvent.OutboxReceived(
                "node-1",
                listOf(OutboxEvent(eventId = "evt-unknown", runId = "unmapped-run", output = "private")),
            ),
        )

        assertTrue(db.messageDao().observeBySession(sessionId).first().isEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `owner resolver routes only watcher bound to event node`() = runTest {
        val sessionId = sessionRepo.createAgentSession("default")
        val resolver = ComputeNodeOutboxOwnerResolver(db.agentWatcherDao(), repo)
        db.agentWatcherDao().upsert(
            AgentWatcherEntity(
                watcherId = "watch-1",
                botId = "default",
                originSessionId = sessionId,
                computeTarget = "cloud",
                remoteWatcherId = RemoteWatcherBinding("node-1", "remote-watch-1").encode(),
            ),
        )

        val event = OutboxEvent(type = "watcher", watcherId = "watch-1", eventId = "evt-watch")
        assertEquals(OutboxOwner("default", sessionId), resolver.resolve("node-1", event))
        assertEquals(null, resolver.resolve("node-2", event))
    }

    @Test
    fun `outbox batch continues after one publish failure and acknowledges only committed item`() = runTest {
        val sessionA = sessionRepo.createAgentSession("assistant-a")
        val sessionB = sessionRepo.createAgentSession("assistant-b")
        repo.ensureBinding("bot-a", sessionA)
        repo.ensureBinding("bot-b", sessionB)
        insertSampleNode()
        repeat(3) { server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true,"acked":1}""")) }
        val flakyPublication = mockk<PublicationService>()
        val seenScopes = mutableMapOf<String, String>()
        val settled = mutableMapOf<String, PublicationService.PublicationResult>()
        var failFirst = true
        coEvery { flakyPublication.publishIdempotent(any(), any(), any()) } coAnswers {
            val key = firstArg<String>()
            val scope = secondArg<PublicationService.PublicationScope>()
            val eventId = if (key.endsWith("evt-a")) "evt-a" else "evt-b"
            if (eventId == "evt-a" && failFirst) {
                failFirst = false
                error("simulated single-item persistence failure")
            }
            settled[key]?.copy(created = false) ?: PublicationService.PublicationResult(
                publicationKey = key,
                messageIds = listOf("message-$eventId"),
                created = true,
            ).also {
                settled[key] = it
                seenScopes[eventId] = scope.originSessionId
            }
        }
        val bridge = ComputeNodeEventBridge(
            context = context,
            computeNodeManager = nodeManager,
            nodeDao = db.remoteComputeNodeDao(),
            repository = repo,
            publication = flakyPublication,
            settings = SettingsRepository(context, mockk(relaxed = true)),
            notifier = { binding, _ -> notified += binding.botId to "notified" },
            outboxOwnerResolver = { _, item ->
                if (item.runId == "run-a") OutboxOwner("bot-a", sessionA) else OutboxOwner("bot-b", sessionB)
            },
        )
        val batch = ComputeNodeEvent.OutboxReceived(
            "node-1",
            listOf(
                OutboxEvent(eventId = "evt-a", type = "exec", runId = "run-a", output = "a-private"),
                OutboxEvent(eventId = "evt-b", type = "exec", runId = "run-b", output = "b-private"),
            ),
        )

        bridge.handleEvent(batch)
        val firstAck = server.takeRequest()
        assertTrue(firstAck.body.readUtf8().contains("evt-b"))
        bridge.handleEvent(batch)
        val secondAck = server.takeRequest()
        val thirdAck = server.takeRequest()
        val retryAckBodies = listOf(secondAck.body.readUtf8(), thirdAck.body.readUtf8())

        assertTrue(retryAckBodies.any { it.contains("evt-a") })
        assertTrue(retryAckBodies.any { it.contains("evt-b") })
        assertEquals(sessionA, seenScopes["evt-a"])
        assertEquals(sessionB, seenScopes["evt-b"])
        assertEquals(2, notified.size)
    }

    @Test
    fun `remote exec owner is persisted before event and scoped by node and run`() = runTest {
        val sessionId = sessionRepo.createAgentSession("default")
        val recorded = repo.recordRemoteExecOwner(
            nodeId = "node-1",
            remoteRunId = "remote-run-1",
            botId = "default",
            originSessionId = sessionId,
        )

        assertTrue(recorded)
        val owner = repo.findRemoteExecOwner("node-1", "remote-run-1")
        assertEquals("default", owner?.botId)
        assertEquals(sessionId, owner?.originSessionId)
        assertEquals(null, repo.findRemoteExecOwner("node-2", "remote-run-1"))
        repo.clearRemoteExecOwner("node-1", "remote-run-1")
        assertEquals(null, repo.findRemoteExecOwner("node-1", "remote-run-1"))
    }

    @Test
    fun `unacknowledged outbox page remains retryable without advancing timestamp cursor`() = runTest {
        insertSampleNode()
        val page = """
            {"ok":true,"events":[{"eventId":"evt-retry","createdAt":100,"type":"exec","runId":"run-retry"}],
            "count":1,"serverTime":200}
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(page))
        server.enqueue(MockResponse().setResponseCode(200).setBody(page))

        assertEquals("evt-retry", nodeManager.pullOutbox("node-1").single().eventId)
        assertEquals("evt-retry", nodeManager.pullOutbox("node-1").single().eventId)

        val requests = List(2) { server.takeRequest() }
        assertTrue(requests.all { it.requestUrl?.queryParameter("since") == "0" })
    }

    @Test
    fun `online heartbeat re-pulls outbox items until ack removes them`() = runTest {
        insertSampleNode()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true,"service":"muse-node"}"""))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                    {"ok":true,"events":[{"eventId":"evt-pending","createdAt":100,"type":"exec","runId":"run-pending"}],
                    "count":1,"serverTime":100}
                """.trimIndent(),
            ),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true,"service":"muse-node"}"""))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                    {"ok":true,"events":[{"eventId":"evt-pending","createdAt":100,"type":"exec","runId":"run-pending"}],
                    "count":1,"serverTime":100}
                """.trimIndent(),
            ),
        )

        nodeManager.refresh("node-1")
        nodeManager.refresh("node-1")

        val requests = List(4) { server.takeRequest() }
        assertEquals(listOf("/health", "/outbox", "/health", "/outbox"), requests.map { it.requestUrl?.encodedPath })
        assertTrue(
            requests.filter { it.requestUrl?.encodedPath == "/outbox" }
                .all { it.requestUrl?.queryParameter("since") == "0" },
        )
    }

    @Test
    fun `outbox received without enabled bindings skips ack so events are not lost`() = runTest {
        insertSampleNode("node-1", "Tokyo-VPS")
        val bridge = newBridge()

        bridge.handleEvent(
            ComputeNodeEvent.OutboxReceived(
                nodeId = "node-1",
                events = listOf(OutboxEvent(eventId = "evt-1", runId = "run-1", output = "done")),
            ),
        )

        assertEquals(0, server.requestCount)
        assertTrue(notified.isEmpty())
    }
}
