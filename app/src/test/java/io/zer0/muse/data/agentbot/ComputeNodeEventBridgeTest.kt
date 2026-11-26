package io.zer0.muse.data.agentbot

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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
import io.mockk.mockk
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

    private fun newBridge(): ComputeNodeEventBridge = ComputeNodeEventBridge(
        context = context,
        computeNodeManager = nodeManager,
        nodeDao = db.remoteComputeNodeDao(),
        repository = repo,
        publication = publication,
        settings = SettingsRepository(context, mockk(relaxed = true)),
        notifier = { binding, text -> notified += binding.botId to text },
        clock = { clock },
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
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true,"removed":2}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true,"removed":0}"""))
        val bridge = newBridge()

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
        assertEquals(1, messages.size)
        assertTrue(messages.first().content.contains("Tokyo-VPS"))
        assertTrue(messages.first().content.contains("watch-price"))
        assertTrue(messages.first().content.contains("价格降到 199 元"))
        assertTrue(messages.first().content.contains("run-9"))
        assertEquals(1, notified.size)
        assertEquals(2, server.requestCount)
        val ackReq = server.takeRequest()
        assertEquals("/outbox/ack", ackReq.path)
        val ackBody = ackReq.body.readUtf8()
        assertTrue(ackBody.contains("evt-1"))
        assertTrue(ackBody.contains("evt-2"))
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
