package io.zer0.muse.proactive

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.zer0.muse.data.ProactiveMessageConfig
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ProactiveContextLoaderRoutingPolicyTest {
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
            messageImageStore = MessageImageStore(File(context.cacheDir, "routing-${UUID.randomUUID()}")),
        )
        settings = mockk(relaxed = true)
        coEvery { settings.getViewedSessionId() } returns null
        every { settings.proactiveMessageConfigFlow } returns flowOf(ProactiveMessageConfig())
        assistant = mockk(relaxed = true)
        every { assistant.id } returns ASSISTANT_ID
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun defaultPolicyDoesNotPollutePlainChatWhenAgentIsAvailable() = runTest {
        seedSession("plain", isAgent = false)
        seedUserMessage("plain")
        seedSession("agent", isAgent = true)

        val pick = loader().resolveSessionPick(assistant)

        assertEquals("default proactive messages must land in Agent", "agent", pick?.sessionId)
        assertEquals(ProactiveContextLoader.SessionSource.EXISTING_AGENT, pick?.source)
    }

    @Test
    fun explicitlyAllowedPlainSessionCanReceiveProactiveMessage() = runTest {
        every { settings.proactiveMessageConfigFlow } returns
            flowOf(ProactiveMessageConfig(allowPlainChatSession = true))
        seedSession("plain", isAgent = false)
        seedUserMessage("plain")

        val pick = loader().resolveSessionPick(assistant)

        assertEquals("plain", pick?.sessionId)
    }

    @Test
    fun mutedPlainSessionIsNeverSelected() = runTest {
        every { settings.proactiveMessageConfigFlow } returns
            flowOf(ProactiveMessageConfig(allowPlainChatSession = true))
        seedSession("plain", isAgent = false)
        seedUserMessage("plain")
        database.sessionProactivePreferenceDao().upsert(
            SessionProactivePreferenceEntity(sessionId = "plain", muted = true),
        )
        seedSession("agent", isAgent = true)

        val pick = loader().resolveSessionPick(assistant)

        assertEquals("agent", pick?.sessionId)
    }

    @Test
    fun mutedOnlyTargetReturnsNoRoute() = runTest {
        every { settings.proactiveMessageConfigFlow } returns
            flowOf(ProactiveMessageConfig(agentSessionEnabled = false, allowPlainChatSession = true))
        seedSession("plain", isAgent = false)
        seedUserMessage("plain")
        database.sessionProactivePreferenceDao().upsert(
            SessionProactivePreferenceEntity(sessionId = "plain", muted = true),
        )

        assertNull(loader().resolveSessionPick(assistant))
    }

    @Test
    fun disabledAgentDoesNotReturnFromViewedSessionFallback() = runTest {
        every { settings.proactiveMessageConfigFlow } returns
            flowOf(ProactiveMessageConfig(agentSessionEnabled = false, allowPlainChatSession = true))
        coEvery { settings.getViewedSessionId() } returns "agent"
        seedSession("agent", isAgent = true)

        assertNull(
            "普通会话兜底不得通过 viewed fallback 绕回已关闭的 Agent",
            loader().resolveSessionPick(assistant),
        )
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
            proactiveSessionPreferences = ProactiveSessionPreferenceRepository(
                database.sessionProactivePreferenceDao(),
            ),
        ),
        sources = ProactiveSources(factStore = mockk(relaxed = true), milestoneDao = mockk(relaxed = true)),
    )

    private suspend fun seedSession(id: String, isAgent: Boolean) {
        database.sessionDao().insert(
            SessionEntity(
                id = id,
                title = id,
                createdAt = now - 5 * MINUTE,
                updatedAt = now - 5 * MINUTE,
                assistantId = ASSISTANT_ID,
                isAgentSession = isAgent,
            ),
        )
    }

    private suspend fun seedUserMessage(sessionId: String) {
        database.messageDao().upsert(
            MessageEntity(
                id = UUID.randomUUID().toString(),
                sessionId = sessionId,
                role = "USER",
                content = "在吗",
                createdAt = now - 6 * MINUTE,
            ),
        )
    }
}
