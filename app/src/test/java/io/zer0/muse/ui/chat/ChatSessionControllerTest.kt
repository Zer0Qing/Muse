package io.zer0.muse.ui.chat

import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.zer0.ai.core.UIMessage
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.session.ConversationSessionManager
import io.zer0.muse.tools.SessionPermissionMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ChatSessionControllerTest {

    private class ScopedAccessor(
        initial: ChatUiState,
        override val coroutineScope: CoroutineScope,
    ) : ChatStateAccessor {
        private val state = MutableStateFlow(initial)
        override val snapshot: ChatUiState get() = state.value
        override fun update(transform: (ChatUiState) -> ChatUiState) = state.update(transform)
        override val messagesSnapshot: List<UIMessage> get() = emptyList()
        override fun updateMessages(transform: (List<UIMessage>) -> List<UIMessage>) = Unit
    }

    private class FakeBridge : SessionFlowBridge {
        var detachCalled = false
        var stoppedSessions = mutableListOf<String?>()
        var suppressedSessions = mutableListOf<String?>()
        override suspend fun refreshContext() = Unit
        override fun detachStreaming() {
            detachCalled = true
        }
        override fun onForkError(throwable: Throwable) = Unit
        override fun stopGenerationForSession(sessionId: String?) {
            stoppedSessions += sessionId
        }
        override fun suppressSessionWrites(sessionId: String?) {
            suppressedSessions += sessionId
        }
    }

    private fun controller(
        scope: CoroutineScope,
        repo: SessionRepository,
        state: ChatUiState = ChatUiState(),
        bridge: SessionFlowBridge = FakeBridge(),
        treeSnapshotStore: io.zer0.muse.data.chat.ConversationTreeSnapshotStore? = null,
        accessor: ChatStateAccessor = ScopedAccessor(state, scope),
        sessionManager: ConversationSessionManager = mockk(relaxed = true),
        settings: SettingsRepository = mockk(relaxed = true),
    ) = ChatSessionController(
        accessor = accessor,
        sessionRepository = repo,
        sessionMemoryCache = mockk(relaxed = true),
        browserManagerRegistry = null,
        bridge = bridge,
        sessionDeps = SessionDeps(
            stateStore = ChatStateStore(),
            settings = settings,
            assistantRepository = mockk(relaxed = true),
            sessionPermissionStore = mockk(relaxed = true),
            sessionManager = sessionManager,
            appContext = ApplicationProvider.getApplicationContext(),
            onStopTts = {},
            onDisposeAsr = {},
            onNotifySessionEnd = {},
            currentSessionIdForApproval = { null },
            globalActiveProviderId = { null },
            globalSelectedModelId = { null },
            onSend = {},
            messageController = mockk(relaxed = true),
            chatGenerationManager = mockk(relaxed = true),
            onClearDelegation = {},
            onCancelPendingApprovals = {},
            treeSnapshotStore = treeSnapshotStore,
            restorePendingApprovalsForSession = {},
            activeProviderForSession = { null },
            selectedModelForSession = { null },
            onSessionSwitched = {},
            requeueOutboxForSession = {},
        ),
    )

    @Test
    fun `older new session cannot take focus back from a newer one`() = runTest {
        val repo = mockk<SessionRepository>(relaxed = true)
        val firstId = CompletableDeferred<String>()
        coEvery { repo.findReusableEmptySession(any()) } returns null
        coEvery { repo.createSession(assistantId = "first") } coAnswers { firstId.await() }
        coEvery { repo.createSession(assistantId = "second") } returns "session-2"
        val sessionManager = mockk<ConversationSessionManager>(relaxed = true)
        val settings = mockk<SettingsRepository>(relaxed = true)
        every { settings.defaultSessionPermissionModeFlow } returns flowOf(SessionPermissionMode.ASK)
        val accessor = ScopedAccessor(ChatUiState(sessionState = ChatSessionState(currentSessionId = "original")), this)
        val controller = controller(this, repo, accessor = accessor, sessionManager = sessionManager, settings = settings)
        val ready = mutableListOf<String>()

        controller.createNewSession(onReady = { ready += "first" }, assistantIdOverride = "first")
        runCurrent()
        verify(exactly = 0) { sessionManager.release("original") }

        controller.createNewSession(onReady = { ready += "second" }, assistantIdOverride = "second")
        runCurrent()
        firstId.complete("session-1")
        advanceUntilIdle()

        assertEquals("session-2", accessor.snapshot.currentSessionId)
        assertEquals(listOf("second"), ready)
        verify(exactly = 1) { sessionManager.release("original") }
        verify(exactly = 0) { sessionManager.acquire("session-1") }
        verify(exactly = 1) { sessionManager.acquire("session-2") }
    }

    @Test
    fun `returning to task mode withdraws a pending agent entry`() = runTest {
        val repo = mockk<SessionRepository>(relaxed = true)
        val accessor = ScopedAccessor(ChatUiState(), this)
        val controller = controller(this, repo, accessor = accessor)
        controller.setAgentMode(true)
        assertTrue(accessor.snapshot.isSwitchingSession)

        controller.setAgentMode(false)
        advanceUntilIdle()

        assertFalse(accessor.snapshot.isSwitchingSession)
        assertFalse(accessor.snapshot.isAgentMode)
        coVerify(exactly = 0) { repo.getRecentAgentByAssistant(any(), any()) }
    }

    @Test
    fun `renameSession delegates to repository`() = runTest {
        val repo = mockk<SessionRepository>(relaxed = true)
        controller(this, repo).renameSession("s1", "new title")
        advanceUntilIdle()
        coVerify { repo.renameSession("s1", "new title") }
    }

    @Test
    fun `setSessionIgnoreMemory with no session is a no-op`() = runTest {
        val repo = mockk<SessionRepository>(relaxed = true)
        controller(this, repo).setSessionIgnoreMemory(true)
        advanceUntilIdle()
        coVerify(exactly = 0) { repo.setSessionIgnoreMemory(any(), any()) }
    }

    @Test
    fun `forkSessionFromMessage delegates to repository`() = runTest {
        val repo = mockk<SessionRepository>(relaxed = true)
        coEvery { repo.forkSession("s1", any()) } returns "new1"
        // 让 switchSession 走"会话不存在"早退路径,避免触碰 settings 的 Flow 空流
        coEvery { repo.getSessionById("new1") } returns null
        controller(this, repo, ChatUiState(sessionState = ChatSessionState(currentSessionId = "s1")))
            .forkSessionFromMessage(Uuid.random())
        advanceUntilIdle()
        coVerify { repo.forkSession("s1", any()) }
    }

    @Test
    fun `forkSessionFromMessage in agent mode uses agent session`() = runTest {
        val repo = mockk<SessionRepository>(relaxed = true)
        coEvery { repo.forkSession("agent-1", any()) } returns null
        val state = ChatUiState(
            sessionState = ChatSessionState(currentSessionId = "task-1"),
            agentState = ChatAgentState(isAgentMode = true, agentSessionId = "agent-1"),
        )

        controller(this, repo, state = state).forkSessionFromMessage(Uuid.random())
        advanceUntilIdle()

        coVerify { repo.forkSession("agent-1", any()) }
        coVerify(exactly = 0) { repo.forkSession("task-1", any()) }
    }

    // ── P0-5: 删除/归档会话必须停止在途生成 + 写抑制 ──

    @Test
    fun `deleteSession stops generation and suppresses writes`() = runTest {
        val repo = mockk<SessionRepository>(relaxed = true)
        val bridge = FakeBridge()
        controller(this, repo, bridge = bridge).deleteSession("s1")
        advanceUntilIdle()
        coVerify { repo.softDeleteSession("s1") }
        assertEquals(listOf("s1"), bridge.stoppedSessions)
        assertEquals(listOf("s1"), bridge.suppressedSessions)
    }

    /**
     * 删会话要**连对话树选择快照一起清掉**。
     *
     * 以前 `ConversationTreeSnapshotStore.delete()` 全仓零调用：每删一条会话就在
     * `filesDir/conversation_tree_snapshots/` 留一个孤儿文件，而重建同名会话时 `load`
     * 会把上一个会话的分支选中状态读回来 —— 用户看到"新会话自带一堆选中"且无从解释。
     * 这里用真 store（Robolectric 给的是真 filesDir），断言文件确实消失。
     */
    @Test
    fun `deleteSession also removes the conversation tree snapshot`() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dir = java.io.File(context.filesDir, "conversation_tree_snapshots").apply { mkdirs() }
        val snapshotFile = java.io.File(dir, "s1.json").apply { writeText("{}") }
        assertTrue("前置条件：快照文件应当先存在", snapshotFile.exists())

        val repo = mockk<SessionRepository>(relaxed = true)
        controller(
            this,
            repo,
            treeSnapshotStore = io.zer0.muse.data.chat.ConversationTreeSnapshotStore(context),
        ).deleteSession("s1")
        advanceUntilIdle()

        coVerify { repo.softDeleteSession("s1") }
        // store 里是 `withContext(Dispatchers.IO)`，跑在真实 IO 线程上；
        // runTest 的 advanceUntilIdle 只推进虚拟时钟，不等真线程 —— 所以要显式等一会儿，
        // 否则这条断言会跑在删除完成之前（第一次就这么红的）。
        val deadline = System.currentTimeMillis() + 3_000
        while (snapshotFile.exists() && System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(20)
        }
        assertFalse("删会话后快照文件必须跟着没（否则同名会话会读回旧选择）", snapshotFile.exists())
    }

    @Test
    fun `archive stops generation and suppresses writes`() = runTest {
        val repo = mockk<SessionRepository>(relaxed = true)
        val bridge = FakeBridge()
        controller(this, repo, bridge = bridge).setSessionArchived("s1", archived = true)
        advanceUntilIdle()
        coVerify { repo.setArchived("s1", true) }
        assertEquals(listOf("s1"), bridge.stoppedSessions)
        assertEquals(listOf("s1"), bridge.suppressedSessions)
    }

    @Test
    fun `unarchive does not suppress writes`() = runTest {
        val repo = mockk<SessionRepository>(relaxed = true)
        val bridge = FakeBridge()
        controller(this, repo, bridge = bridge).setSessionArchived("s1", archived = false)
        advanceUntilIdle()
        coVerify { repo.setArchived("s1", false) }
        assertTrue("取消归档不应触发写抑制", bridge.suppressedSessions.isEmpty())
        assertTrue("取消归档不应停止生成", bridge.stoppedSessions.isEmpty())
    }
}
