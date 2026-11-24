package io.zer0.muse.data.agentbot

import android.content.Context
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

/**
 * Agent 心情（Mood）与思考耗时端到端链路测试：
 * 流式清洗（不泄露 `<mood>` 标签到正文）→ 落库（MessageEntity.mood / reasoningDurationMs）→ 恢复（TranscriptRestorer）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AgentMoodPipelineTest {

    private lateinit var db: MuseDb
    private lateinit var repo: AgentBotRepository
    private lateinit var sessions: SessionRepository
    private lateinit var publication: PublicationService
    private lateinit var restorer: TranscriptRestorer

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
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
                java.io.File(context.cacheDir, "muse_images_mood_pipeline_test"),
            ),
        )
        publication = PublicationService(db = db, repository = repo, sessionRepository = sessions)
        restorer = TranscriptRestorer(sessions)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `streaming turn strips mood tag from body, persists mood and reasoningDurationMs, and restores them`() = runBlocking {
        val sessionId = sessions.createAgentSession("bot-mood")
        val draftTexts = mutableListOf<String>()
        val draftMoods = mutableListOf<String>()
        val coordinator = AgentStreamCoordinator(
            streamingClient = { _, _, _, _, _ ->
                flowOf(
                    ChatStreamEvent.ReasoningDelta("先检查一下上下文"),
                    ChatStreamEvent.ContentDelta("<mood>状态：专注\n想法：帮用户快速查清</mood>"),
                    ChatStreamEvent.ContentDelta("好的，我马上帮你核对。"),
                    ChatStreamEvent.Done("stop"),
                )
            },
            publication = publication,
            governor = BotResourceGovernor(),
        )

        val outcome = coordinator.runStreamingTurn(
            identity = AgentTurnIdentity(
                assistantId = "bot-mood",
                sessionId = sessionId,
                requestId = "req-mood-1",
                requestRevision = 1L,
            ),
            systemPrompt = "test",
            history = emptyList(),
            userText = "帮我查一下",
            currentRequestRevision = { 1L },
            onDraft = { draft ->
                draftTexts += draft.text
                if (draft.mood.isNotBlank()) draftMoods += draft.mood
            },
        )

        assertEquals("好的，我马上帮你核对。", outcome.text)
        assertFalse("正文不应残留 <mood> 标签", outcome.text.contains("<mood>"))
        assertTrue("流式草稿不应把 <mood> 原文吐给正文", draftTexts.none { it.contains("<mood>") })
        assertTrue("流式期应提取出 mood 内容", draftMoods.any { it.contains("专注") })
        assertNotNull("思考结束后应冻结 reasoningDurationMs", outcome.reasoningDurationMs)

        val restored = restorer.read(sessionId, limit = 10)
        val published = restored.messages.firstOrNull { it.messageId == outcome.publishedMessageId }
        assertNotNull("落库后的消息必须能被 TranscriptRestorer 恢复", published)
        assertEquals("好的，我马上帮你核对。", published!!.text) // 上一行已 assertNotNull
        assertTrue("恢复后的消息必须保留 mood", published.mood.orEmpty().contains("专注"))
        assertNotNull("恢复后的消息必须保留 reasoningDurationMs", published.reasoningDurationMs)
    }

    @Test
    fun `mood leaked into reasoning channel is salvaged and stripped from reasoning display`() = runBlocking {
        val sessionId = sessions.createAgentSession("bot-mood-2")
        val coordinator = AgentStreamCoordinator(
            streamingClient = { _, _, _, _, _ ->
                flowOf(
                    ChatStreamEvent.ReasoningDelta("分析步骤中\n<mood>心情：轻松</mood>"),
                    ChatStreamEvent.ContentDelta("已经整理好了。"),
                    ChatStreamEvent.Done("stop"),
                )
            },
            publication = publication,
            governor = BotResourceGovernor(),
        )

        val outcome = coordinator.runStreamingTurn(
            identity = AgentTurnIdentity(
                assistantId = "bot-mood-2",
                sessionId = sessionId,
                requestId = "req-mood-2",
                requestRevision = 1L,
            ),
            systemPrompt = "test",
            history = emptyList(),
            userText = "整理一下",
            currentRequestRevision = { 1L },
        )

        assertFalse("reasoning 展示通道应剔除 <mood> 块", outcome.reasoning.contains("<mood>"))
        val restored = restorer.read(sessionId, limit = 10)
        val published = restored.messages.firstOrNull { it.messageId == outcome.publishedMessageId }
        assertNotNull(published)
        assertTrue("应从 reasoning 通道捞回 mood 并持久化", published!!.mood.orEmpty().contains("轻松")) // 上一行已 assertNotNull
    }
}
