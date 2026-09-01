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
        override suspend fun refreshContext() = Unit
        override fun detachStreaming() {
            detachCalled = true
        }
        override fun onForkError(throwable: Throwable) = Unit
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
}
