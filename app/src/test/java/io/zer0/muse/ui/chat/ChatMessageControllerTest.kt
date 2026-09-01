package io.zer0.muse.ui.chat

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.muse.data.chat.ConversationTree
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.tools.SkillExecutor
import io.zer0.muse.ui.ChatUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatMessageControllerTest {

    private class ScopedAccessor(
        initial: ChatUiState,
        override val coroutineScope: CoroutineScope,
        initialMessages: List<UIMessage> = emptyList(),
    ) : ChatStateAccessor {
        private val state = MutableStateFlow(initial)
        private val messages = MutableStateFlow(initialMessages)
        override val snapshot: ChatUiState get() = state.value
        override fun update(transform: (ChatUiState) -> ChatUiState) = state.update(transform)
        override val messagesSnapshot: List<UIMessage> get() = messages.value
        override fun updateMessages(transform: (List<UIMessage>) -> List<UIMessage>) = messages.update(transform)
    }

    private fun controller(
        scope: CoroutineScope,
        repo: SessionRepository,
        messages: List<UIMessage> = emptyList(),
    ) = ChatMessageController(
        accessor = ScopedAccessor(ChatUiState(), scope, messages),
        sessionRepository = repo,
        skillExecutor = mockk<SkillExecutor>(relaxed = true),
        treeState = MutableStateFlow(ConversationTree()),
        treeSnapshotStore = null,
    )

    @Test
    fun `loadMessagesPaged returns empty when no messages`() = runTest {
        val repo = mockk<SessionRepository>(relaxed = true)
        coEvery { repo.getMessageCount("s1") } returns 0
        val (messages, hasMore) = controller(this, repo).loadMessagesPaged("s1")
        assertTrue(messages.isEmpty())
        assertFalse(hasMore)
    }

    @Test
    fun `loadMessagesPaged sets hasMore when total exceeds page size`() = runTest {
        val repo = mockk<SessionRepository>(relaxed = true)
        coEvery { repo.getMessageCount("s1") } returns 60
        val page = List(50) { UIMessage(role = MessageRole.USER, content = "m$it") }
        coEvery { repo.getRecentMessages("s1", 50) } returns page
        val (messages, hasMore) = controller(this, repo).loadMessagesPaged("s1")
        assertEquals(50, messages.size)
        assertTrue(hasMore)
    }

    @Test
    fun `restoreAgentPlansForSession returns empty when no tool messages`() = runTest {
        val repo = mockk<SessionRepository>(relaxed = true)
        coEvery { repo.getToolCallMessages("s1") } returns emptyList()
        val plans = controller(this, repo).restoreAgentPlansForSession("s1", emptyList())
        assertTrue(plans.isEmpty())
    }

    @Test
    fun `loadMoreHistory with no messages is a no-op`() = runTest {
        val repo = mockk<SessionRepository>(relaxed = true)
        controller(this, repo).loadMoreHistory()
        advanceUntilIdle()
        coVerify(exactly = 0) { repo.getOlderMessages(any(), any(), any()) }
    }

    @Test
    fun `selectUserVariant on empty tree is a no-op`() = runTest {
        val c = controller(this, mockk<SessionRepository>(relaxed = true))
        c.selectUserVariant("none", 0)
        // 空树无节点 → 不抛异常、消息仍为空
        assertTrue(c.treeState.value.userNodes.isEmpty())
    }

    @Test
    fun `selectAssistantVariant on empty tree keeps messages empty`() = runTest {
        val c = controller(this, mockk<SessionRepository>(relaxed = true))
        c.selectAssistantVariant("g", "a", 0)
        assertTrue(c.treeState.value.displayMessages.isEmpty())
    }

    @Test
    fun `healBranchCounts on empty tree does not upsert`() = runTest {
        val repo = mockk<SessionRepository>(relaxed = true)
        controller(this, repo).healBranchCounts("s1", emptyList(), ConversationTree())
        coVerify(exactly = 0) { repo.upsertMessage(any(), any()) }
    }
}
