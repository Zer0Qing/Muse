package io.zer0.muse.data.agentbot

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.mockk
import io.zer0.ai.core.ChatStreamEvent
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.Model
import io.zer0.ai.core.UIMessage
import io.zer0.muse.computenode.CloudComputerSummary
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.UserProfile
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.tools.BrowserManager
import io.zer0.muse.tools.ToolRegistry
import io.zer0.muse.tools.ToolRiskLevel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AgentPromptAndLoopM2Test {

    private lateinit var context: Context
    private lateinit var db: MuseDb
    private lateinit var repo: AgentBotRepository
    private lateinit var sessions: SessionRepository
    private lateinit var publication: PublicationService
    private lateinit var toolRegistry: ToolRegistry

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
            database = db,
        )
        sessions = SessionRepository(
            sessionDao = db.sessionDao(),
            messageDao = db.messageDao(),
            database = db,
            context = context,
            messageImageStore = io.zer0.muse.data.session.MessageImageStore(
                java.io.File(context.cacheDir, "muse_images_agent_m2_test"),
            ),
        )
        publication = PublicationService(db = db, repository = repo, sessionRepository = sessions)
        toolRegistry = ToolRegistry(context = context, browserManager = mockk<BrowserManager>(relaxed = true))
        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "web_search",
                description = "search",
                parameters = mapOf("query" to "q"),
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { args -> """{"q":"${args["query"]}"}""" }
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `buildSplit renders templates and isolates dynamic telemetry into second part`() = runBlocking {
        val settings = mockk<SettingsRepository>(relaxed = true)
        coEvery { settings.getUserProfile() } returns UserProfile(userNickName = "小青")
        var cpu = 15.0
        val builder = AgentCapabilityPromptBuilder(
            cloudComputersProvider = {
                listOf(
                    CloudComputerSummary(
                        name = "WorkBox",
                        os = "Ubuntu",
                        cpuPercent = cpu,
                        memUsedMb = 512.0,
                        memTotalMb = 4096L,
                        packs = emptyList(),
                    ),
                )
            },
            settingsRepository = settings,
        )

        val split1 = builder.buildSplit(
            assistantName = "阿缪",
            assistantSystemPrompt = "你是 {{char}}，正在帮助 {{user_name}}。",
            activeMissions = listOf(AgentCapabilityPromptBuilder.ActiveMissionSummary("t1", "整理文档", "running")),
        )
        cpu = 78.0
        val split2 = builder.buildSplit(
            assistantName = "阿缪",
            assistantSystemPrompt = "你是 {{char}}，正在帮助 {{user_name}}。",
            activeMissions = emptyList(),
        )

        assertTrue(split1.first.contains("你是 阿缪，正在帮助 小青。"))
        assertFalse(split1.first.contains("{{char}}") || split1.first.contains("{{user_name}}"))
        assertEquals("云端遥测与活跃任务变化不得改变静态 SYSTEM #1", split1.first, split2.first)
        assertTrue(split1.second.contains("WorkBox"))
        assertTrue(split1.second.contains("整理文档"))
    }

    @Test
    fun `runStreamingTurn preserves thinkingSignature and breaks identical tool call loops`() = runBlocking {
        val sessionId = sessions.createAgentSession("bot-1")
        val seenMessages = mutableListOf<List<UIMessage>>()
        var callCount = 0
        val coordinator = AgentStreamCoordinator(
            streamingClient = { messages, _, _, _, _ ->
                seenMessages += messages
                callCount++
                when (callCount) {
                    1 -> flowOf(
                        ChatStreamEvent.ReasoningDelta("thinking round 1", signature = "sig-agent-1"),
                        ChatStreamEvent.ToolCallDelta(0, "tc1", "web_search", """{"query":"muse"}"""),
                        ChatStreamEvent.Done("tool_calls"),
                    )
                    else -> flowOf(
                        ChatStreamEvent.ToolCallDelta(0, "tc_$callCount", "web_search", """{"query":"muse"}"""),
                        ChatStreamEvent.Done("tool_calls"),
                    )
                }
            },
            publication = publication,
            governor = BotResourceGovernor(),
            dependencies = AgentStreamCoordinatorDependencies(toolRegistry = toolRegistry),
        )

        coordinator.runStreamingTurn(
            identity = AgentTurnIdentity("bot-1", sessionId, "msg-u1", 1L),
            systemPrompt = "static",
            dynamicSystemPrompt = "dynamic",
            history = emptyList(),
            userText = "搜索 muse",
            model = Model(id = "claude-sonnet-4-5", providerId = "p1"),
            currentRequestRevision = { 1L },
        )

        assertEquals("相同工具与参数连续重复时应在第 2 轮就终止而非跑满 12 轮", 2, callCount)
        val round2Assistant = seenMessages[1].first { it.role == MessageRole.ASSISTANT }
        assertEquals("sig-agent-1", round2Assistant.thinkingSignature)
        assertEquals("claude-sonnet-4-5", round2Assistant.modelId)
    }
}
