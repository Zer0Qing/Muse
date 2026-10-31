package io.zer0.muse.proactive

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.proactive.ProactiveOutcomeRepository
import io.zer0.muse.data.proactive.ProactiveRelationRepository
import io.zer0.muse.data.proactive.ProactiveThoughtRepository
import io.zer0.muse.data.session.MessageEntity
import io.zer0.muse.data.session.MessageImageStore
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.data.session.SessionEntity
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.notification.MuseNotificationManager
import io.zer0.muse.schedule.ChatGenerationManager
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

/**
 * 主动消息落点选择：在哪儿聊完，就由哪儿发。
 *
 * 用真实 SessionRepository + 内存 Room，因为排除条件（已删 / 已归档 / 小手机 /
 * 无用户发言）都写在 SQL 里 —— 用假仓库回放结果等于什么都没验。
 * `source` 每一级都要断言：它决定了「消息发去了哪个房间」能不能从日志里复盘。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ProactiveContextLoaderSessionTest {

    private companion object {
        const val ASSISTANT_ID = "a1"
        const val MINUTE = 60_000L
    }

    private lateinit var context: Context
    private lateinit var database: MuseDb
    private lateinit var repository: SessionRepository
    private lateinit var settings: SettingsRepository
    private lateinit var assistant: AssistantEntity

    private val now = System.currentTimeMillis()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, MuseDb::class.java)
            .allowMainThreadQueries()
            .build()
        repository = SessionRepository(
            sessionDao = database.sessionDao(),
            messageDao = database.messageDao(),
            database = database,
            context = context,
            messageImageStore = MessageImageStore(File(context.cacheDir, "proactive-session-${UUID.randomUUID()}")),
        )
        settings = mockk(relaxed = true)
        coEvery { settings.getViewedSessionId() } returns null
        assistant = mockk(relaxed = true)
        every { assistant.id } returns ASSISTANT_ID
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun loader(): ProactiveContextLoader = ProactiveContextLoader(
        stores = ProactiveStores(
            thoughts = mockk<ProactiveThoughtRepository>(relaxed = true),
            relations = mockk<ProactiveRelationRepository>(relaxed = true),
            outcomes = mockk<ProactiveOutcomeRepository>(relaxed = true),
        ),
        runtime = ProactiveRuntime(
            settings = settings,
            sessions = repository,
            assistants = mockk(relaxed = true),
            notifier = mockk<MuseNotificationManager>(relaxed = true),
            generations = mockk<ChatGenerationManager>(relaxed = true),
        ),
        sources = ProactiveSources(factStore = mockk(relaxed = true), milestoneDao = mockk(relaxed = true)),
    )

    private suspend fun seedSession(
        id: String,
        updatedAt: Long,
        assistantId: String = ASSISTANT_ID,
        isAgentSession: Boolean = false,
        archived: Boolean = false,
        deletedAt: Long? = null,
    ) {
        database.sessionDao().insert(
            SessionEntity(
                id = id,
                title = id,
                createdAt = updatedAt,
                updatedAt = updatedAt,
                assistantId = assistantId,
                isAgentSession = isAgentSession,
                archived = archived,
                deletedAt = deletedAt,
            ),
        )
    }

    private suspend fun seedUserMessage(sessionId: String, at: Long, content: String = "在吗") {
        database.messageDao().upsert(
            MessageEntity(
                id = UUID.randomUUID().toString(),
                sessionId = sessionId,
                role = "USER",
                content = content,
                createdAt = at,
            ),
        )
    }

    private suspend fun seedAssistantMessage(sessionId: String, at: Long) {
        database.messageDao().upsert(
            MessageEntity(
                id = UUID.randomUUID().toString(),
                sessionId = sessionId,
                role = "ASSISTANT",
                content = "在",
                createdAt = at,
            ),
        )
    }

    @Test
    fun `recent active plain session wins over older agent session`() = runTest {
        seedSession("plain", updatedAt = now - 5 * MINUTE)
        seedUserMessage("plain", at = now - 6 * MINUTE)
        seedSession("agent", updatedAt = now - 600 * MINUTE, isAgentSession = true)
        seedUserMessage("agent", at = now - 601 * MINUTE)

        val pick = loader().resolveSessionPick(assistant)

        assertEquals("应写到普通对话会话", "plain", pick?.sessionId)
        assertEquals(ProactiveContextLoader.SessionSource.RECENT_ACTIVE, pick?.source)
        // resolveSession 是对外入口，签名语义不变：仍是同一个 id
        assertEquals("plain", loader().resolveSession(assistant))
    }

    @Test
    fun `recent active agent session is picked when it is the latest`() = runTest {
        seedSession("plain", updatedAt = now - 600 * MINUTE)
        seedUserMessage("plain", at = now - 601 * MINUTE)
        seedSession("agent", updatedAt = now - 5 * MINUTE, isAgentSession = true)
        seedUserMessage("agent", at = now - 6 * MINUTE)

        val pick = loader().resolveSessionPick(assistant)

        assertEquals("Agent 会话最近有活动就写回 Agent 会话", "agent", pick?.sessionId)
        assertEquals(ProactiveContextLoader.SessionSource.RECENT_ACTIVE, pick?.source)
    }

    @Test
    fun `deleted session is skipped`() = runTest {
        seedSession("deleted", updatedAt = now - 1 * MINUTE, deletedAt = now - 1 * MINUTE)
        seedUserMessage("deleted", at = now - 2 * MINUTE)
        seedSession("plain", updatedAt = now - 300 * MINUTE)
        seedUserMessage("plain", at = now - 301 * MINUTE)

        val pick = loader().resolveSessionPick(assistant)

        assertEquals("已软删除的会话不许写", "plain", pick?.sessionId)
        assertEquals(ProactiveContextLoader.SessionSource.RECENT_ACTIVE, pick?.source)
    }

    @Test
    fun `archived session is skipped`() = runTest {
        seedSession("archived", updatedAt = now - 1 * MINUTE, archived = true)
        seedUserMessage("archived", at = now - 2 * MINUTE)
        seedSession("plain", updatedAt = now - 300 * MINUTE)
        seedUserMessage("plain", at = now - 301 * MINUTE)

        val pick = loader().resolveSessionPick(assistant)

        assertEquals("已归档的会话不许写", "plain", pick?.sessionId)
        assertEquals(ProactiveContextLoader.SessionSource.RECENT_ACTIVE, pick?.source)
    }

    @Test
    fun `viewed session is picked when no session has user messages`() = runTest {
        // 该助手名下只有不说话的老房间（只有主动消息落过），第 1 级取不到
        seedSession("agent-old", updatedAt = now - 900 * MINUTE, isAgentSession = true)
        seedAssistantMessage("agent-old", at = now - 900 * MINUTE)
        // 用户刚打开一个还没发言的新会话，它是当前正在看的
        seedSession("viewed", updatedAt = now - 1 * MINUTE)
        coEvery { settings.getViewedSessionId() } returns "viewed"

        val pick = loader().resolveSessionPick(assistant)

        assertEquals("应写进用户正在看的会话", "viewed", pick?.sessionId)
        assertEquals(ProactiveContextLoader.SessionSource.VIEWED, pick?.source)
    }

    @Test
    fun `viewed session of another assistant is ignored and a new agent session is created`() = runTest {
        seedSession("other-assistant", updatedAt = now - 1 * MINUTE, assistantId = "a2")
        coEvery { settings.getViewedSessionId() } returns "other-assistant"

        val pick = loader().resolveSessionPick(assistant)

        assertNotNull("兜底应新建会话", pick)
        assertNotEquals("不许写进别的助手的会话", "other-assistant", pick?.sessionId)
        assertEquals(ProactiveContextLoader.SessionSource.NEW_AGENT, pick?.source)
        val created = database.sessionDao().getById(pick!!.sessionId)
        assertNotNull(created)
        assertTrue("兜底新建的是 Agent 会话", created!!.isAgentSession)
        assertEquals(ASSISTANT_ID, created.assistantId)
    }

    @Test
    fun `no session at all falls back to a new agent session`() = runTest {
        val pick = loader().resolveSessionPick(assistant)

        assertNotNull(pick)
        assertEquals(ProactiveContextLoader.SessionSource.NEW_AGENT, pick?.source)
        val created = database.sessionDao().getById(pick!!.sessionId)
        assertNotNull(created)
        assertTrue(created!!.isAgentSession)
        assertEquals("新建会话要绑在该助手上", ASSISTANT_ID, created.assistantId)
    }

    @Test
    fun `素材总量超预算时丢弃最旧的若干条`() = runTest {
        // 40 条 × 600 字：单条都不算长，叠起来却有两万多字。
        // 预算的作用就是把这个总量封顶，且丢的必须是最旧的那一段。
        val total = ProactiveContextLoader.RECENT_MESSAGE_COUNT
        seedSession("busy", updatedAt = now)
        repeat(total) { index ->
            seedUserMessage(
                sessionId = "busy",
                at = now - (total - index) * MINUTE,
                content = "m" + index.toString().padStart(2, '0') + "x".repeat(597),
            )
        }

        val context = loader().loadSessionContext("busy", ASSISTANT_ID)

        val chars = context.recentMessages.sumOf { it.content.length }
        assertTrue(
            "素材总长应落进预算：$chars > ${ProactiveContextLoader.CONTEXT_CHAR_BUDGET}",
            chars <= ProactiveContextLoader.CONTEXT_CHAR_BUDGET,
        )
        // 预算 6000 / 每条 600 = 最近 10 条
        assertEquals("应只保留最新的十条", 10, context.recentMessages.size)
        assertTrue(
            "保留区间应从第 31 条开始（丢弃最旧的 30 条）",
            context.recentMessages.first().content.startsWith("m30"),
        )
        assertTrue(
            "最后一条必须是最新那条",
            context.recentMessages.last().content.startsWith("m39"),
        )
        assertEquals("最后发言时间应是保留区间里最新那条", now - MINUTE, context.lastUserMessageAt)
    }
}
