package io.zer0.muse.data.agentbot

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.data.session.SessionRepository
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
 * 对外发布的输出边界与恢复语义。
 *
 * 核心断言：**只有经过 PublicationService 的内容才可见**，而且
 * 一批多条 item 中断后不会重投已发出的那些。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PublicationServiceTest {

    private lateinit var db: MuseDb
    private lateinit var repo: AgentBotRepository
    private lateinit var service: PublicationService
    private lateinit var sessionRepo: SessionRepository
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
        sessionRepo = SessionRepository(
            sessionDao = db.sessionDao(),
            messageDao = db.messageDao(),
            database = db,
            context = context,
            messageImageStore = io.zer0.muse.data.session.MessageImageStore(java.io.File(context.cacheDir, "muse_images")),
        )
        service = PublicationService(db, repo, sessionRepo)
    }

    @After
    fun tearDown() {
        db.close();
    }

    @Test
    fun `publishing a single text item commits outbox and message together`() = runTest {
        val sessionId = sessionRepo.createAgentSession("default")
        val result = service.publish(
            botId = "bot-1",
            originSessionId = sessionId,
            causeEventIds = "e1",
            requestRevision = 1L,
            taskRevision = 1,
            items = listOf(PublicationService.Item(PublicationKind.TEXT, "我来处理，结果出来告诉你")),
        )

        assertEquals(1, result.committed)
        assertEquals(0, result.failed)
        assertTrue(result.allCommitted)

        val pending = repo.pendingPublications()
        assertTrue("提交后不该还挂着 PENDING", pending.isEmpty())
    }

    @Test
    fun `new input arriving mid-batch discards the whole batch`() = runTest {
        val sessionId = sessionRepo.createAgentSession("default")
        val result = service.publish(
            botId = "bot-1",
            originSessionId = sessionId,
            causeEventIds = "e1",
            requestRevision = 1L,
            taskRevision = 1,
            items = listOf(PublicationService.Item(PublicationKind.TEXT, "旧回复")),
            currentRequestRevision = 2L,
        )

        assertEquals("新输入已到达，一条都不该发", 0, result.committed)
        assertEquals(1, result.superseded)
    }

    @Test
    fun `missing origin session is refused`() = runTest {
        val result = service.publish(
            botId = "bot-1",
            originSessionId = "",
            causeEventIds = "",
            requestRevision = 1L,
            taskRevision = 1,
            items = listOf(PublicationService.Item(PublicationKind.TEXT, "不该发出去")),
        )
        assertEquals(0, result.committed)
        assertEquals(1, result.failed)
    }

    @Test
    fun `empty batch is a no-op`() = runTest {
        val result = service.publish(
            botId = "bot-1",
            originSessionId = "s1",
            causeEventIds = "",
            requestRevision = 1L,
            taskRevision = 1,
            items = emptyList(),
        )
        assertEquals(0, result.committed);
        assertTrue(result.allCommitted)
    }

    @Test
    fun `model raw text kind is rejected at the boundary`() = runTest {
        val sessionId = sessionRepo.createAgentSession("default")
        var threw = false
        try {
            service.publish(
                botId = "bot-1",
                originSessionId = sessionId,
                causeEventIds = "",
                requestRevision = 1L,
                taskRevision = 1,
                items = listOf(PublicationService.Item("MODEL_RAW_TEXT", "私有推理")),
            )
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("模型正文不得进入公共发布通道", threw)
    }

    @Test
    fun `resume only re-sends items that were never committed`() = runTest {
        val sessionId = sessionRepo.createAgentSession("default")
        val publicationId = repo.enqueuePublication(
            botId = "bot-1",
            originSessionId = sessionId,
            causeEventIds = "",
            requestRevision = 1L,
            taskRevision = 1,
            items = listOf(
                PublicationKind.TEXT to "第一句",
                PublicationKind.TEXT to "第二句",
            ),
        )

        // 模拟"第一条已发、进程被杀"：手动提交第一条
        assertTrue(repo.commitPublication("$publicationId:0", "m-0"))

        val resumed = service.resumePublication(publicationId)
        assertEquals("只补发未发出的那条", 1, resumed.committed)

        val items = db.publicationOutboxDao().listByPublication(publicationId)
        assertTrue(items.all { it.status == PublicationStatus.COMMITTED })
    }

    @Test
    fun `resume on a fully committed batch does nothing`() = runTest {
        val sessionId = sessionRepo.createAgentSession("default")
        val publicationId = repo.enqueuePublication(
            botId = "bot-1",
            originSessionId = sessionId,
            causeEventIds = "",
            requestRevision = 1L,
            taskRevision = 1,
            items = listOf(PublicationKind.TEXT to "只有一句"),
        )
        repo.commitPublication("$publicationId:0", "m-0")

        val resumed = service.resumePublication(publicationId)
        assertEquals("已全部提交的批次不该重投", 0, resumed.committed)
    }

    @Test
    fun `supersede keeps committed items intact`() = runTest {
        val sessionId = sessionRepo.createAgentSession("default")
        val publicationId = repo.enqueuePublication(
            botId = "bot-1",
            originSessionId = sessionId,
            causeEventIds = "",
            requestRevision = 1L,
            taskRevision = 1,
            items = listOf(
                PublicationKind.TEXT to "已发出的",
                PublicationKind.TEXT to "还没发的",
            ),
        )
        repo.commitPublication("$publicationId:0", "m-0")

        assertEquals(1, service.supersede(publicationId))
        val items = db.publicationOutboxDao().listByPublication(publicationId).associateBy { it.itemIndex }
        assertEquals(PublicationStatus.COMMITTED, items[0]!!.status)
        assertEquals(PublicationStatus.SUPERSEDED, items[1]!!.status)
    }

    @Test
    fun `multi item batch publishes in order`() = runTest {
        val sessionId = sessionRepo.createAgentSession("default")
        val result = service.publish(
            botId = "bot-1",
            originSessionId = sessionId,
            causeEventIds = "e1",
            requestRevision = 1L,
            taskRevision = 1,
            items = listOf(
                PublicationService.Item(PublicationKind.TEXT, "第一句"),
                PublicationService.Item(PublicationKind.TEXT, "第二句"),
                PublicationService.Item(PublicationKind.APPROVAL_NOTICE, "需要你批准一步"),
            ),
        )
        assertEquals(3, result.committed)
        assertEquals(3, result.messageIds.size)
    }
}