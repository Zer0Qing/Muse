package io.zer0.muse.tools

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.zer0.muse.data.session.SearchResult
import io.zer0.muse.data.session.SessionRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchConversationToolTest {

    private val repository = mockk<SessionRepository>()

    @Test
    fun `requires a host session and a non blank query`() = runTest {
        val context = ToolExecutionContext(scope = "assistant-a", spaceId = "default", sessionId = null)

        assertTrue(SearchConversationTool.execute(mapOf("query" to "history"), repository, enabled = true, context).startsWith("Error:"))
        assertTrue(SearchConversationTool.execute(emptyMap(), repository, enabled = true, context).startsWith("Error:"))
        coVerify(exactly = 0) { repository.searchMessagesInSession(any(), any(), any()) }
    }

    @Test
    fun `disabled recall does not read conversation history`() = runTest {
        val context = ToolExecutionContext(scope = "assistant-a", spaceId = "default", sessionId = "session-1")

        val result = SearchConversationTool.execute(
            args = mapOf("query" to "history"),
            sessionRepository = repository,
            enabled = false,
            context = context,
        )

        assertTrue(result.contains("disabled", ignoreCase = true))
        coVerify(exactly = 0) { repository.searchMessagesInSession(any(), any(), any()) }
    }

    @Test
    fun `returns current session user and assistant originals with bounded limit`() = runTest {
        val context = ToolExecutionContext(scope = "assistant-a", spaceId = "default", sessionId = "session-1")
        coEvery {
            repository.searchMessagesInSession("session-1", "history", 5)
        } returns listOf(
            SearchResult("u1", "session-1", "Chat", "old user", "USER", 1L, "用户原文细节"),
            SearchResult("a1", "session-1", "Chat", "old assistant", "ASSISTANT", 2L, "助手原文细节"),
        )

        val result = SearchConversationTool.execute(
            args = mapOf("query" to "history", "limit" to "5"),
            sessionRepository = repository,
            enabled = true,
            context = context,
        )

        assertTrue(result.contains("用户原文细节"))
        assertTrue(result.contains("助手原文细节"))
        assertTrue(result.contains("session-1"))
        assertTrue(result.contains("historical reference", ignoreCase = true))
        coVerify(exactly = 1) { repository.searchMessagesInSession("session-1", "history", 5) }
    }
}
