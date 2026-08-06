package io.zer0.muse.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.zer0.ai.ChatService
import io.zer0.ai.core.MessageRole
import io.zer0.ai.image.ImageService
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.artifact.ArtifactRepository
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.audit.AuditLogger
import io.zer0.muse.data.lorebook.LorebookRepository
import io.zer0.muse.data.promptinjection.PromptInjectionRepository
import io.zer0.muse.data.quickmsg.QuickMessageRepository
import io.zer0.muse.data.session.FolderRepository
import io.zer0.muse.data.session.SessionEntity
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.data.skill.SkillRepository
import io.zer0.muse.doc.DocumentParser
import io.zer0.muse.doc.OcrManager
import io.zer0.muse.notification.MuseNotificationManager
import io.zer0.muse.rag.RagService
import io.zer0.muse.schedule.ChatGenerationManager
import io.zer0.muse.schedule.UserActivityProfile
import io.zer0.muse.session.ConversationSessionManager
import io.zer0.muse.tools.AgentRouter
import io.zer0.muse.tools.DelegationChainTracker
import io.zer0.muse.tools.DelegationPauseManager
import io.zer0.muse.tools.SessionPermissionStore
import io.zer0.muse.tools.SkillExecutor
import io.zer0.muse.tools.ToolRegistry
import io.zer0.muse.transformer.SystemPromptAssembler
import io.zer0.muse.ui.speech.TtsManager
import io.zer0.muse.vision.VisionBridge
import io.zer0.muse.vision.VisionProgress
import io.zer0.muse.web.WebSearchService
import io.zer0.ai.video.VideoGenerationService
import io.zer0.memory.ticker.MemoryTicker
import io.zer0.muse.network.NetworkMonitor
import io.zer0.muse.tools.DeferredResultStore
import io.zer0.muse.data.subagent.SubagentThreadStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * R-TEST-02: 会话焦点恢复回归测试。
 *
 * 覆盖 R-UI-02 后端修复:
 *  - 进程重建后优先还原用户离开时查看的会话,而不是最近活跃会话
 *  - outbox 重放只恢复消息,不改写当前查看焦点
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ChatViewModelFocusRestoreTest {

    private val testDispatcher = StandardTestDispatcher()

    private val chatService: ChatService = mockk(relaxed = true)
    private val settings: SettingsRepository = mockk(relaxed = true)
    private val memoryTicker: MemoryTicker = mockk(relaxed = true)
    private val sessionRepository: SessionRepository = mockk(relaxed = true)
    private val imageService: ImageService = mockk(relaxed = true)
    private val videoGenerationService: VideoGenerationService = mockk(relaxed = true)
    private val documentParser: DocumentParser = mockk(relaxed = true)
    private val toolRegistry: ToolRegistry = mockk(relaxed = true)
    private val assistantRepository: AssistantRepository = mockk(relaxed = true)
    private val webSearchService: WebSearchService = mockk(relaxed = true)
    private val lorebookRepository: LorebookRepository = mockk(relaxed = true)
    private val quickMessageRepository: QuickMessageRepository = mockk(relaxed = true)
    private val promptInjectionRepository: PromptInjectionRepository = mockk(relaxed = true)
    private val ocrManager: OcrManager = mockk(relaxed = true)
    private val ttsManager: TtsManager = mockk(relaxed = true)
    private val skillRepository: SkillRepository = mockk(relaxed = true)
    private val skillExecutor: SkillExecutor = mockk(relaxed = true)
    private val delegationPauseManager: DelegationPauseManager = mockk(relaxed = true)
    private val delegationChainTracker: DelegationChainTracker = mockk(relaxed = true)
    private val agentRouter: AgentRouter = mockk(relaxed = true)
    private val folderRepository: FolderRepository = mockk(relaxed = true)
    private val notificationManager: MuseNotificationManager = mockk(relaxed = true)
    private val systemPromptAssembler: SystemPromptAssembler = mockk(relaxed = true)
    private val chatGenerationManager: ChatGenerationManager = mockk(relaxed = true)
    private val artifactRepository: ArtifactRepository = mockk(relaxed = true)
    private val ragService: RagService = mockk(relaxed = true)
    private val visionBridge: VisionBridge = mockk(relaxed = true)
    private val auditLogger: AuditLogger = mockk(relaxed = true)
    private val sessionPermissionStore: SessionPermissionStore = mockk(relaxed = true)
    private val networkMonitor: NetworkMonitor = mockk(relaxed = true)
    private val deferredResultStore: DeferredResultStore = mockk(relaxed = true)
    private val subagentThreadStore: SubagentThreadStore = mockk(relaxed = true)
    private val sessionManager: ConversationSessionManager = mockk(relaxed = true)
    private val activityProfile: UserActivityProfile = mockk(relaxed = true)
    private lateinit var appContext: Context

    private lateinit var viewModel: ChatViewModel

    private val oldSession = SessionEntity(id = "session-old", title = "old", createdAt = 1L, updatedAt = 2L)
    private val newSession = SessionEntity(id = "session-new", title = "new", createdAt = 3L, updatedAt = 4L)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        appContext = ApplicationProvider.getApplicationContext()

        every { sessionRepository.observeSessions() } returns flowOf(listOf(newSession, oldSession))
        every { settings.providerConfigFlow } returns emptyFlow()
        every { settings.providersFlow } returns emptyFlow()
        every { settings.activeProviderIdFlow } returns emptyFlow()
        every { settings.selectedModelIdFlow } returns emptyFlow()
        every { settings.toolModelIdFlow } returns emptyFlow()
        every { settings.mediaConfigFlow } returns emptyFlow()
        every { settings.multiAgentConfigFlow } returns emptyFlow()
        every { delegationChainTracker.chains } returns MutableStateFlow<Map<String, DelegationChainTracker.ChainNode>>(emptyMap())
        every { delegationPauseManager.activePauses } returns MutableStateFlow<Map<String, DelegationPauseManager.PauseRequest>>(emptyMap())
        every { chatGenerationManager.activeGeneration } returns MutableStateFlow<ChatGenerationManager.ActiveGeneration?>(null)
        every { visionBridge.progressFlow } returns MutableStateFlow(VisionProgress(idle = true, index = 0, total = 0))
        every { deferredResultStore.completedTasks } returns MutableStateFlow(emptyMap())
        every { deferredResultStore.tasks } returns MutableStateFlow(emptyMap())
        coEvery { subagentThreadStore.listActiveThreads() } returns emptyList()
        coEvery { sessionRepository.getPendingOutbox() } returns emptyList()
        coEvery { sessionRepository.getMessageCount(any()) } returns 0
        coEvery { sessionRepository.getRecentMessages(any(), any()) } returns emptyList()
        coEvery { sessionRepository.getAssistantId(any()) } returns "default"
        coEvery { assistantRepository.getById(any()) } returns null
        coEvery { settings.loadChatDraft(any()) } returns ""
        coEvery { settings.getViewedSessionId() } returns "session-old"
        every { settings.defaultSessionPermissionModeFlow } returns flowOf(io.zer0.muse.tools.SessionPermissionMode.ASK)

        viewModel = createViewModel()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): ChatViewModel = ChatViewModel(
        chatService = chatService,
        settings = settings,
        memoryTicker = memoryTicker,
        sessionRepository = sessionRepository,
        imageService = imageService,
        videoGenerationService = videoGenerationService,
        documentParser = documentParser,
        toolRegistry = toolRegistry,
        assistantRepository = assistantRepository,
        webSearchService = webSearchService,
        lorebookRepository = lorebookRepository,
        quickMessageRepository = quickMessageRepository,
        promptInjectionRepository = promptInjectionRepository,
        ocrManager = ocrManager,
        ttsManager = ttsManager,
        skillRepository = skillRepository,
        skillExecutor = skillExecutor,
        delegationPauseManager = delegationPauseManager,
        delegationChainTracker = delegationChainTracker,
        agentRouter = agentRouter,
        folderRepository = folderRepository,
        notificationManager = notificationManager,
        systemPromptAssembler = systemPromptAssembler,
        chatGenerationManager = chatGenerationManager,
        artifactRepository = artifactRepository,
        appContext = appContext,
        ragService = ragService,
        visionBridge = visionBridge,
        auditLogger = auditLogger,
        sessionPermissionStore = sessionPermissionStore,
        networkMonitor = networkMonitor,
        activityProfile = activityProfile,
        deferredResultStore = deferredResultStore,
        subagentThreadStore = subagentThreadStore,
        sessionManager = sessionManager,
        toolOrchestrator = mockk(relaxed = true),
        toolApprovalRouter = io.zer0.muse.tools.ToolApprovalRouter(),
    )

    @Test
    fun `进程恢复优先还原查看会话而不是最近活跃会话`() = runTest(testDispatcher) {
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("应恢复用户离开时查看的 session-old", "session-old", viewModel.state.value.currentSessionId)
        coVerify(atLeast = 1) { settings.getViewedSessionId() }
        coVerify(atLeast = 1) { settings.saveViewedSessionId("session-old") }
    }

    @Test
    fun `outbox 重放不改写查看焦点`() = runTest(testDispatcher) {
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("初始焦点应为 session-old", "session-old", viewModel.state.value.currentSessionId)

        // 模拟启动后出现一条属于生成会话的 outbox;恢复只补写消息,不应把焦点切到生成会话。
        coEvery { sessionRepository.getPendingOutbox() } returns listOf(
            io.zer0.muse.data.session.MessageOutboxEntity(
                id = "outbox-1",
                sessionId = "session-generating",
                userMessageId = "user-1",
                text = "hello",
                imageBase64Json = "[]",
                createdAt = System.currentTimeMillis(),
                assistantMessageId = "assistant-1",
            ),
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("outbox 重放不应改写查看焦点", "session-old", viewModel.state.value.currentSessionId)
        coVerify(exactly = 0) { settings.saveViewedSessionId("session-generating") }
    }
}
