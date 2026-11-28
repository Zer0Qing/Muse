package io.zer0.muse.data.agentbot

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.data.session.MuseDb
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
 * V24：同一步不生成第二张审批卡。
 *
 * 用户看到两张一模一样的卡片会困惑：该点哪个？点了一个另一个还在。
 * 这比没有卡片更糟。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ApprovalIdempotencyTest {

    private lateinit var db: MuseDb
    private lateinit var repo: AgentBotRepository
    private var clock = 1_000L

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
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
            now = { clock },
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun create(stepId: String = "step-1") = repo.createApproval(
        botId = "bot-1",
        originSessionId = "s1",
        taskId = "t1",
        runId = "r1",
        stepId = stepId,
        taskRevision = 1,
        toolCallId = "c1",
        toolName = "workspace_write",
        argumentsRef = "a",
        argumentsHash = "h",
        resourceVersion = "v1",
        risk = "NORMAL",
    )

    @Test
    fun `same step does not create a second card`() = runTest {
        val first = create()
        val second = create()

        assertEquals("同一步必须复用同一张卡", first.approvalId, second.approvalId)
        assertEquals(1, db.approvalRequestDao().listPending().size)
    }

    @Test
    fun `different steps create separate cards`() = runTest {
        val a = create(stepId = "step-1")
        val b = create(stepId = "step-2")

        assertTrue("不同步骤是两件不同的事", a.approvalId != b.approvalId)
        assertEquals(2, db.approvalRequestDao().listPending().size)
    }

    @Test
    fun `a settled approval is not revived by a second call`() = runTest {
        val first = create()
        repo.settleApproval(first.approvalId, ApprovalStatus.DENIED, "user")

        val second = create()

        assertTrue(
            "已拒绝的不该被复活成新的待办",
            second.approvalId != first.approvalId,
        )
    }

    @Test
    fun `blank step id always creates a new card`() = runTest {
        val a = create(stepId = "")
        val b = create(stepId = "")

        assertTrue(a.approvalId != b.approvalId)
    }

    @Test
    fun `two runs with the same step index get separate cards`() = runTest {
        // stepCounter 是 launchRun 的局部变量，两个 run 都会从 0 开始。
        // 幂等键必须含 runId，否则第二个 run 会串到第一个 run 的审批。
        val runA = repo.createApproval(
            botId = "bot-1", originSessionId = "s1", taskId = "t1",
            runId = "run-A", stepId = "step-0", taskRevision = 1,
            toolCallId = "c1", toolName = "workspace_write",
            argumentsRef = "a", argumentsHash = "h", resourceVersion = "v1", risk = "NORMAL",
        )
        val runB = repo.createApproval(
            botId = "bot-1", originSessionId = "s1", taskId = "t2",
            runId = "run-B", stepId = "step-0", taskRevision = 1,
            toolCallId = "c2", toolName = "device_shell",
            argumentsRef = "b", argumentsHash = "h2", resourceVersion = "v2", risk = "HIGH",
        )

        assertTrue(
            "不同 run 的同序号步骤是两件不同的事，不能串卡",
            runA.approvalId != runB.approvalId,
        )
        assertEquals(2, db.approvalRequestDao().listPending().size)
    }

    @Test
    fun `same run same step still dedupes`() = runTest {
        val a = repo.createApproval(
            botId = "bot-1", originSessionId = "s1", taskId = "t1",
            runId = "run-X", stepId = "step-0", taskRevision = 1,
            toolCallId = "c1", toolName = "workspace_write",
            argumentsRef = "a", argumentsHash = "h", resourceVersion = "v1", risk = "NORMAL",
        )
        val b = repo.createApproval(
            botId = "bot-1", originSessionId = "s1", taskId = "t1",
            runId = "run-X", stepId = "step-0", taskRevision = 1,
            toolCallId = "c1", toolName = "workspace_write",
            argumentsRef = "a", argumentsHash = "h", resourceVersion = "v1", risk = "NORMAL",
        )

        assertEquals("同一 run 的同一步仍然复用", a.approvalId, b.approvalId)
    }
}
