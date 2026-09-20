package io.zer0.muse.ui.chat

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.zer0.ai.core.UIMessage
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.ui.ChatSessionState
import io.zer0.muse.ui.ChatUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.core.app.ApplicationProvider
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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
    ) = ChatSessionController(
        accessor = ScopedAccessor(state, scope),
        sessionRepository = repo,
        sessionMemoryCache = mockk(relaxed = true),
        browserManagerRegistry = null,
        bridge = bridge,
        sessionDeps = SessionDeps(
            stateStore = ChatStateStore(),
            settings = mockk(relaxed = true),
            assistantRepository = mockk(relaxed = true),
            sessionPermissionStore = mockk(relaxed = true),
            sessionManager = mockk(relaxed = true),
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
            treeSnapshotStore = null,
            restorePendingApprovalsForSession = {},
            activeProviderForSession = { null },
            selectedModelForSession = { null },
            onSessionSwitched = {},
            requeueOutboxForSession = {},
        ),
    )

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
