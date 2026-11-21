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
import java.io.File

/**
 * 发布的 messageId 必须能原样读回。
 *
 * SessionRepository 读回时走 Uuid.parse，解析失败会**静默回退成随机 id**。
 * 一旦发生，重建出来的 messageId 与库里的对不上，去重失效、消息可能重复。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PublishedMessageIdRoundTripTest {

    private lateinit var db: MuseDb
    private lateinit var repo: AgentBotRepository
    private lateinit var sessions: SessionRepository
    private lateinit var service: PublicationService

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext();
        db = Room.inMemoryDatabaseBuilder(context, MuseDb::class.java)
            .allowMainThreadQueries()
            .build();
        repo = AgentBotRepository(
            taskDao = db.botTaskDao(),
            runDao = db.taskRunDao(),
            stepDao = db.taskStepDao(),
            eventDao = db.botEventDao(),
            approvalDao = db.approvalRequestDao(),
            publicationDao = db.publicationOutboxDao(),
            bindingDao = db.botBindingDao(),
        );
        sessions = SessionRepository(
            sessionDao = db.sessionDao(),
            messageDao = db.messageDao(),
            database = db,
            context = context,
            messageImageStore = io.zer0.muse.data.session.MessageImageStore(File(context.cacheDir, "img")),
        );
        service = PublicationService(db, repo, sessions);
    }

    @After
    fun tearDown() {
        db.close();
    }

    @Test
    fun `published message id reads back unchanged`() = runTest {
        val sessionId = sessions.createAgentSession("default");
        val result = service.publish(
            botId = "bot-1",
            originSessionId = sessionId,
            causeEventIds = "",
            requestRevision = 1L,
            taskRevision = 1,
            items = listOf(PublicationService.Item(PublicationKind.TEXT, "我来处理")),
        );

        assertEquals(1, result.committed);
        val publishedId = result.messageIds.single();

        val readBack = sessions.getRecentMessages(sessionId, 10);
        assertEquals(1, readBack.size);
        assertEquals(
            "读回的 id 必须与发布时一致，否则去重失效、消息会重复",
            publishedId,
            readBack.single().id.toString(),
        );
    }

    @Test
    fun `reloaded transcript has no duplicate ids`() = runTest {
        val sessionId = sessions.createAgentSession("default");
        service.publish(
            botId = "bot-1", originSessionId = sessionId, causeEventIds = "",
            requestRevision = 1L, taskRevision = 1,
            items = listOf(
                PublicationService.Item(PublicationKind.TEXT, "第一句"),
                PublicationService.Item(PublicationKind.TEXT, "第二句"),
            ),
        );

        val ids = sessions.getRecentMessages(sessionId, 10).map { it.id.toString() };
        assertEquals("两条消息的 id 必须不同", 2, ids.toSet().size);
    }
}
