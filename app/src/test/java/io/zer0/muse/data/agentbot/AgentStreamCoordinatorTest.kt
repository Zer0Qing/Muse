package io.zer0.muse.data.agentbot

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.ai.core.ChatStreamEvent
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.data.session.SessionRepository
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AgentStreamCoordinatorTest {

    private lateinit var db: MuseDb
    private lateinit var repo: AgentBotRepository
    private lateinit var sessions: SessionRepository
    private lateinit var publication: PublicationService

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
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
            database = db,
        )
        sessions = SessionRepository(
            sessionDao = db.sessionDao(),
            messageDao = db.messageDao(),
            database = db,
            context = context,
            messageImageStore = io.zer0.muse.data.session.MessageImageStore(
                java.io.File(context.cacheDir, "muse_images_stream_test"),
            ),
        )
        publication = PublicationService(db = db, repository = repo, sessionRepository = sessions)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `plain streaming text publishes without requiring bot_say tool`() = runBlocking {
        val sessionId = sessions.createAgentSession("bot-1")
        val drafts = mutableListOf<String>()
        val coordinator = AgentStreamCoordinator(
            streamingClient = { _, _, _, _, _ ->
                flowOf(
                    ChatStreamEvent.ReasoningDelta("先想一下"),
                    ChatStreamEvent.ContentDelta("你好，"),
                    ChatStreamEvent.ContentDelta("马上帮你处理"),
                    ChatStreamEvent.Done("stop"),
                )
            },
            publication = publication,
            governor = BotResourceGovernor(),
        )
        val identity = AgentTurnIdentity(
            assistantId = "bot-1",
            sessionId = sessionId,
            requestId = "msg-u1",
            requestRevision = 1L,
        )
        val outcome = coordinator.runStreamingTurn(
            identity = identity,
            systemPrompt = "test",
            history = emptyList(),
            userText = "你好",
            currentRequestRevision = { 1L },
            onDraft = { drafts += it.text },
        )

        assertFalse(outcome.requestSuperseded)
        assertEquals("你好，马上帮你处理", outcome.text)
        assertEquals("先想一下", outcome.reasoning)
        assertTrue(outcome.publishedMessageId.isNotBlank())
        assertTrue(drafts.contains("你好，"))
        assertTrue(drafts.last() == "你好，马上帮你处理")
    }

    @Test
    fun `launch_mission auto-adds default ok reaction and parses choices and steer`() = runBlocking {
        val sessionId = sessions.createAgentSession("bot-1")
        val coordinator = AgentStreamCoordinator(
            streamingClient = { _, _, _, _, _ ->
                flowOf(
                    ChatStreamEvent.ContentDelta("好，我来在这台电脑上帮你跑一下"),
                    ChatStreamEvent.ToolCallDelta(
                        index = 0,
                        id = "call-1",
                        name = AgentStreamCoordinator.TOOL_LAUNCH_MISSION,
                        argumentsDelta = """{"goal":"安装环境并检测","acceptance":"汇报结果","resource_scope":"work/"}""",
                    ),
                    ChatStreamEvent.ToolCallDelta(
                        index = 1,
                        id = "call-2",
                        name = AgentStreamCoordinator.TOOL_OFFER_CHOICES,
                        argumentsDelta = """{"prompt":"你想怎么继续？","options":[{"label":"换替代方案"},{"label":"先导出报告"}]}""",
                    ),
                    ChatStreamEvent.ToolCallDelta(
                        index = 2,
                        id = "call-3",
                        name = AgentStreamCoordinator.TOOL_STEER_MISSION,
                        argumentsDelta = """{"task_id":"task-99","instruction":"预算压到 3000 以内"}""",
                    ),
                    ChatStreamEvent.Done("tool_calls"),
                )
            },
            publication = publication,
            governor = BotResourceGovernor(),
        )
        val identity = AgentTurnIdentity(
            assistantId = "bot-1",
            sessionId = sessionId,
            requestId = "msg-u2",
            requestRevision = 1L,
        )

        val outcome = coordinator.runStreamingTurn(
            identity = identity,
            systemPrompt = "",
            history = emptyList(),
            userText = "帮我装一下",
            currentRequestRevision = { 1L },
        )

        assertEquals(1, outcome.dispatches.size)
        assertEquals("安装环境并检测", outcome.dispatches.first().goal)
        assertEquals(1, outcome.reactions.size)
        assertEquals("👌", outcome.reactions.first().emoji)
        assertEquals("msg-u2", outcome.reactions.first().targetMessageId)
        assertEquals(1, outcome.steerings.size)
        assertEquals("task-99", outcome.steerings.first().taskId)
        assertNotNull(outcome.choiceOffer)
        assertEquals(2, outcome.choiceOffer?.options?.size)
    }

    @Test
    fun `superseded streaming turn drops publish and side actions`() = runBlocking {
        val sessionId = sessions.createAgentSession("bot-1")
        val coordinator = AgentStreamCoordinator(
            streamingClient = { _, _, _, _, _ ->
                flowOf(
                    ChatStreamEvent.ContentDelta("过时的回复"),
                    ChatStreamEvent.Done("stop"),
                )
            },
            publication = publication,
            governor = BotResourceGovernor(),
        )
        val identity = AgentTurnIdentity(
            assistantId = "bot-1",
            sessionId = sessionId,
            requestId = "msg-old",
            requestRevision = 1L,
        )
        val outcome = coordinator.runStreamingTurn(
            identity = identity,
            systemPrompt = "",
            history = emptyList(),
            userText = "旧问题",
            currentRequestRevision = { 2L },
        )

        assertTrue(outcome.requestSuperseded)
        assertEquals("", outcome.publishedMessageId)
    }
}
