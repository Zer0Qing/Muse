package io.zer0.muse.data.agentbot

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.data.session.SessionRepository
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 发布的消息必须有真实 seq。

 * 审查 P1-7：`PublicationService` 传 `seq = 0L` 并注释"交给 DAO 自增"，
 * 但 `messageDao().upsert` 是 `@Insert(REPLACE)`，没有自增语义。
 *
 * 排序键是 `CASE WHEN commitSeq > 0 THEN commitSeq ELSE seq END`，
 * seq=0 会把 bot 消息排到最前；`getRecentBySession` 是 DESC + LIMIT，
 * 长会话里它会被挤出窗口 —— 用户「消息丢了」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PublishedMessageSeqTest {

    private lateinit var db: MuseDb
    private lateinit var repo: AgentBotRepository
    private lateinit var sessions: SessionRepository
    private lateinit var service: PublicationService

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
        )
        sessions = SessionRepository(
            sessionDao = db.sessionDao(),
            messageDao = db.messageDao(),
            database = db,
            context = context,
            messageImageStore = io.zer0.muse.data.session.MessageImageStore(File(context.cacheDir, "img")),
        )
        service = PublicationService(db, repo, sessions)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `published messages get a positive seq`() = runTest {
        val sessionId = sessions.createAgentSession("default")
        service.publish(
            botId = "bot-1",
            originSessionId = sessionId,
            causeEventIds = "",
            requestRevision = 1L,
            taskRevision = 1,
            items = listOf(PublicationService.Item(PublicationKind.TEXT, "我来处理")),
        )

        val stored = db.messageDao().getRecentBySession(sessionId, 10)
        assertTrue("必须有消息", stored.isNotEmpty())
        assertTrue(
            "seq 必须大于 0，否则会被排序键排到最前并被 recent 窗口挤出",
            stored.all { it.seq > 0L },
        )
    }

    @Test
    fun `consecutive publications get increasing seq`() = runTest {
        val sessionId = sessions.createAgentSession("default")
        repeat(3) { i ->
            service.publish(
                botId = "bot-1",
                originSessionId = sessionId,
                causeEventIds = "",
                requestRevision = 1L,
                taskRevision = 1,
                items = listOf(PublicationService.Item(PublicationKind.TEXT, "第" + i + "条")),
            )
        }

        val seqs = db.messageDao().getRecentBySession(sessionId, 10).map { it.seq }
        assertTrue("三条消息", seqs.size == 3)
        assertTrue("seq 必须严格递增，否则顺序不定", seqs.toSet().size == 3)
    }
}
