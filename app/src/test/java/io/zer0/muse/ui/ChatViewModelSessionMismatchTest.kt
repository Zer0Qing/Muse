package io.zer0.muse.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.doc.DocumentParser
import io.zer0.muse.doc.OcrManager
import io.zer0.muse.notification.MuseNotificationManager
import io.zer0.muse.rag.RagService
import io.zer0.muse.schedule.ChatGenerationManager
import io.zer0.muse.schedule.UserActivityProfile
import io.zer0.muse.tools.AgentRouter
import io.zer0.muse.tools.DelegationChainTracker
import io.zer0.muse.tools.DelegationPauseManager
import io.zer0.muse.tools.SessionPermissionStore
import io.zer0.muse.tools.SkillExecutor
import io.zer0.muse.tools.ToolRegistry
import io.zer0.muse.data.skill.SkillRepository
import io.zer0.muse.transformer.SystemPromptAssembler
import io.zer0.muse.ui.speech.TtsManager
import io.zer0.muse.vision.VisionBridge
import io.zer0.muse.vision.VisionProgress
import io.zer0.muse.web.WebSearchService
import io.zer0.ai.video.VideoGenerationService
import io.zer0.memory.ticker.MemoryTicker
import io.zer0.muse.network.NetworkMonitor
import io.zer0.muse.tools.DeferredResultStore
import io.zer0.muse.tools.SubagentThreadStore
import io.zer0.muse.session.ConversationSessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ChatViewModel session mismatch 回滚逻辑单元测试(H1 修复点)。
 *
 * 测试场景:用户发送消息后、Channel 消费者处理请求前切换了会话,
 * 此时 req.sessionId 与当前 currentSessionId 不一致(mismatch),
 * 消费者应:
 *  1. 移除 enqueueSend 添加的乐观 user/assistant 消息
 *  2. 重置 isStreaming / isWaitingFirstToken
 *  3. 不向错误会话调用 sessionRepository.appendMessage
 *  4. 不启动 launchStream(不向错误会话发送 HTTP 请求)
 *
 * 实现要点:
 *  - ChatViewModel 构造依赖多达 30 个,全部用 MockK relaxed mock 注入
 *  - appContext 用 Robolectric 真实 Context(ToolConfigStore 需要 SharedPreferences)
 *  - 用 StandardTestDispatcher 控制 viewModelScope 协程调度,确保
 *    "发送 → 切换会话 → 消费者处理"的时序可控
 *  - 通过 ChatStateAccessor.update{} 直接设置 currentSessionId,模拟 switchSession 效果
 *  - chatGenerationManager.launchGeneration 被 mock 为 no-op,跳过流式逻辑
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ChatViewModelSessionMismatchTest {

    private val testDispatcher = StandardTestDispatcher()

    // ── 全部依赖 mock(relaxed = true 让未 stub 的方法返回默认值) ──
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

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        appContext = ApplicationProvider.getApplicationContext()

        // stub 关键 Flow 返回 emptyFlow,避免 init 中的 collect 触发副作用
        // (observeSessions 返回空流 → 不会自动创建/切换会话)
        every { sessionRepository.observeSessions() } returns emptyFlow()
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

        // appendMessage 默认成功返回
        coEvery { sessionRepository.appendMessage(any(), any()) } returns "msg-id"

        viewModel = createViewModel()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** 构造 ChatViewModel,注入全部 mock 依赖。 */
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
    )

    /**
     * 让 init 中 launch 的协程跑完(emptyFlow 的 collect 立即完成,
     * channel 消费者 suspend 在空 channel 上等待)。
     */
    private fun runInitCoroutines() {
        testDispatcher.scheduler.advanceUntilIdle()
    }

    // ── 测试用例 ────────────────────────────────────────────────────────

    @Test
    fun `发送消息时切换会话,乐观消息被移除`() = runTest(testDispatcher) {
        runInitCoroutines()

        // 设置当前会话 A + 输入文本
        viewModel.update { it.copy(currentSessionId = "session-A", input = "hello") }
        // 发送消息(enqueueSend 同步执行乐观更新 + trySend 入队)
        viewModel.send()

        // 验证乐观更新:user + assistant 占位消息已加入列表
        val stateAfterSend = viewModel.state.value
        assertEquals("乐观更新后应有 2 条消息", 2, stateAfterSend.messages.size)
        assertEquals(MessageRole.USER, stateAfterSend.messages[0].role)
        assertEquals(MessageRole.ASSISTANT, stateAfterSend.messages[1].role)

        // 模拟切换到会话 B(在消费者处理请求前)
        viewModel.update { it.copy(currentSessionId = "session-B") }

        // 让 channel 消费者处理请求(此时 currentSessionId=B ≠ req.sessionId=A)
        advanceUntilIdle()

        // 验证乐观消息已被移除
        val stateAfterRollback = viewModel.state.value
        assertEquals("session mismatch 回滚后消息列表应为空", 0, stateAfterRollback.messages.size)
    }

    @Test
    fun `session mismatch 后 isStreaming 和 isWaitingFirstToken 被重置`() = runTest(testDispatcher) {
        runInitCoroutines()

        viewModel.update { it.copy(currentSessionId = "session-A", input = "hello") }
        viewModel.send()

        // 发送后进入流式状态
        assertTrue("发送后应处于 isStreaming", viewModel.state.value.isStreaming)
        assertTrue("发送后应处于 isWaitingFirstToken", viewModel.state.value.isWaitingFirstToken)

        // 切换会话触发 mismatch
        viewModel.update { it.copy(currentSessionId = "session-B") }
        advanceUntilIdle()

        // 验证流式标志被重置
        assertFalse("mismatch 回滚后 isStreaming 应为 false", viewModel.state.value.isStreaming)
        assertFalse("mismatch 回滚后 isWaitingFirstToken 应为 false", viewModel.state.value.isWaitingFirstToken)
    }

    @Test
    fun `session mismatch 后不会向错误会话调用 appendMessage`() = runTest(testDispatcher) {
        runInitCoroutines()

        viewModel.update { it.copy(currentSessionId = "session-A", input = "hello") }
        viewModel.send()

        // 切换到会话 B,使 req.sessionId=A 与 currentSessionId=B 不匹配
        viewModel.update { it.copy(currentSessionId = "session-B") }
        advanceUntilIdle()

        // 验证 appendMessage 从未被调用(不向错误会话写入消息)
        coVerify(exactly = 0) { sessionRepository.appendMessage(any(), any()) }
    }

    @Test
    fun `session mismatch 后不会启动 launchStream`() = runTest(testDispatcher) {
        runInitCoroutines()

        viewModel.update { it.copy(currentSessionId = "session-A", input = "hello") }
        viewModel.send()

        viewModel.update { it.copy(currentSessionId = "session-B") }
        advanceUntilIdle()

        // launchGeneration 是 launchStream 的入口;mismatch 时不应调用
        // 注:launchGeneration 非 suspend,用 verify 而非 coVerify
        verify(exactly = 0) {
            chatGenerationManager.launchGeneration(any(), any(), any(), any())
        }
    }

    @Test
    fun `session 匹配时正常调用 appendMessage 并启动流式`() = runTest(testDispatcher) {
        runInitCoroutines()

        viewModel.update { it.copy(currentSessionId = "session-A", input = "hello") }
        viewModel.send()

        // 不切换会话,让消费者正常处理(sessionId 匹配)
        advanceUntilIdle()

        // 验证 appendMessage 被调用一次,且 sessionId 正确
        coVerify(exactly = 1) { sessionRepository.appendMessage("session-A", any()) }

        // 验证 launchGeneration 被调用(进入流式)
        // 注:launchGeneration 非 suspend,用 verify 而非 coVerify
        verify(atLeast = 1) {
            chatGenerationManager.launchGeneration(
                sessionId = "session-A",
                assistantId = any(),
                sessionTitle = any(),
                block = any(),
            )
        }
    }

    @Test
    fun `发送空文本且无图片时不触发乐观更新`() = runTest(testDispatcher) {
        runInitCoroutines()

        viewModel.update { it.copy(currentSessionId = "session-A", input = "   ") }
        viewModel.send()

        // 空输入应直接 return,不添加消息
        assertEquals(0, viewModel.state.value.messages.size)
        assertFalse(viewModel.state.value.isStreaming)
    }

    @Test
    fun `isStreaming 为 true 时再次 send 不会重复入队`() = runTest(testDispatcher) {
        runInitCoroutines()

        viewModel.update { it.copy(currentSessionId = "session-A", input = "hello") }
        viewModel.send()

        // 第一次发送后 isStreaming=true
        assertTrue(viewModel.state.value.isStreaming)
        val firstMsgCount = viewModel.state.value.messages.size

        // 再次设置输入并发送(应被 isStreaming 守卫拦截)
        viewModel.update { it.copy(input = "second message") }
        viewModel.send()

        // 消息数不应增加
        assertEquals(firstMsgCount, viewModel.state.value.messages.size)
    }

    @Test
    fun `Agent 模式下 mismatch 使用 agentSessionId 判断`() = runTest(testDispatcher) {
        runInitCoroutines()

        // Agent 模式:isAgentMode=true, agentSessionId=session-A
        viewModel.update {
            it.copy(isAgentMode = true, agentSessionId = "agent-session-A", input = "hello")
        }
        viewModel.send()

        // 验证乐观更新
        assertEquals(2, viewModel.state.value.messages.size)

        // 切换 agentSessionId 模拟 Agent Tab 切换
        viewModel.update { it.copy(agentSessionId = "agent-session-B") }
        advanceUntilIdle()

        // 验证乐观消息被移除
        assertEquals(0, viewModel.state.value.messages.size)
        assertFalse(viewModel.state.value.isStreaming)

        // 验证不向错误会话调用 appendMessage
        coVerify(exactly = 0) { sessionRepository.appendMessage(any(), any()) }
    }
}
