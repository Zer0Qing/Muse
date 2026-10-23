package io.zer0.muse.tools

import io.mockk.coEvery
import io.mockk.mockk
import io.zer0.muse.data.session.SearchResult
import io.zer0.muse.data.session.SessionEntity
import io.zer0.muse.data.session.SessionRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跨会话原文检索的测试 —— 重点在**隐私边界**。
 *
 * 助手自审第 6 条要「能想起别处聊过的事」。技术上只需去掉 sessionId 过滤，
 * 但那样 A 角色就能读到用户和 B 角色的对话。所以实现为**按助手隔离**：
 * 只搜同一助手的其他会话。
 *
 * 这个测试的第一要务不是「能搜到」，而是「搜不到不该搜的」。
 */
class SearchConversationScopeTest {

    private val repo = mockk<SessionRepository>(relaxed = true)

    private fun session(id: String, assistantId: String, title: String = id) = SessionEntity(
        id = id,
        title = title,
        createdAt = 0L,
        updatedAt = 0L,
        assistantId = assistantId,
    )

    private fun result(sessionId: String, content: String, role: String = "USER") = SearchResult(
        messageId = "m-$sessionId-$content",
        sessionId = sessionId,
        sessionTitle = sessionId,
        contentSnippet = content,
        role = role,
        createdAt = 0L,
        content = content,
    )

    private fun context(sessionId: String) = ToolExecutionContext(scope = "main", spaceId = "default", sessionId = sessionId)

    @Test
    fun `default scope searches only the current session`() = runBlocking {
        coEvery { repo.searchMessagesInSession("s1", "音乐", 8) } returns listOf(result("s1", "聊过播放器"))
        coEvery { repo.searchMessages("音乐") } returns listOf(result("s2", "别处的音乐"))

        val out =
            SearchConversationTool.execute(
                args = mapOf("query" to "音乐"),
                sessionRepository = repo,
                enabled = true,
                context = context("s1"),
            )

        assertTrue(out.contains("聊过播放器"))
        assertFalse("默认不该跨会话", out.contains("别处的音乐"))
    }

    @Test
    fun `assistant scope includes the same assistant's other sessions`() = runBlocking {
        coEvery { repo.getSessionById("s1") } returns session("s1", "assistant-a")
        coEvery { repo.getSessionById("s2") } returns session("s2", "assistant-a", "上周的会话")
        // v2.6.7 fix (AUDIT F16): 跨会话过滤改为一次性取同助手会话 id 集。
        coEvery { repo.sessionsOfAssistant("assistant-a") } returns listOf("s1", "s2")
        coEvery { repo.searchMessages("音乐") } returns listOf(
            result("s1", "本会话内容"),
            result("s2", "上周聊过的内容"),
        )

        val out =
            SearchConversationTool.execute(
                args = mapOf("query" to "音乐", "scope" to "assistant"),
                sessionRepository = repo,
                enabled = true,
                context = context("s1"),
            )

        assertTrue(out.contains("上周聊过的内容"))
        assertTrue("跨会话要标出来源", out.contains("来自") || out.contains("from"))
    }

    @Test
    fun `assistant scope never leaks another assistant's conversations`() = runBlocking {
        coEvery { repo.getSessionById("s1") } returns session("s1", "assistant-a")
        coEvery { repo.getSessionById("s3") } returns session("s3", "assistant-b")
        // s2 属于同助手，s3 属于另一个助手。
        coEvery { repo.sessionsOfAssistant("assistant-a") } returns listOf("s1", "s2")
        coEvery { repo.searchMessages("秘密") } returns listOf(
            result("s2", "同助手的内容"),
            result("s3", "别的角色的私密对话"),
        )

        val out =
            SearchConversationTool.execute(
                args = mapOf("query" to "秘密", "scope" to "assistant"),
                sessionRepository = repo,
                enabled = true,
                context = context("s1"),
            )

        assertTrue(out.contains("同助手的内容"))
        assertFalse("绝不能读到别的助手和用户的对话", out.contains("别的角色的私密对话"))
    }

    @Test
    fun `unknown scope falls back to current session`() = runBlocking {
        coEvery { repo.searchMessagesInSession("s1", "x", 8) } returns listOf(result("s1", "本会话"))

        val out =
            SearchConversationTool.execute(
                args = mapOf("query" to "x", "scope" to "everything"),
                sessionRepository = repo,
                enabled = true,
                context = context("s1"),
            )

        assertTrue(out.contains("本会话"))
    }

    @Test
    fun `disabled recall returns an error and touches nothing`() = runBlocking {
        val out =
            SearchConversationTool.execute(
                args = mapOf("query" to "x"),
                sessionRepository = repo,
                enabled = false,
                context = context("s1"),
            )

        assertTrue(out.startsWith("Error:"))
    }

    @Test
    fun `missing session id is rejected`() = runBlocking {
        val out =
            SearchConversationTool.execute(
                args = mapOf("query" to "x"),
                sessionRepository = repo,
                enabled = true,
                context = ToolExecutionContext(scope = "main", spaceId = "default", sessionId = null),
            )

        assertTrue(out.startsWith("Error:"))
    }

    @Test
    fun `no match reports it plainly`() = runBlocking {
        coEvery { repo.searchMessagesInSession("s1", "无", 8) } returns emptyList()

        val out =
            SearchConversationTool.execute(
                args = mapOf("query" to "无"),
                sessionRepository = repo,
                enabled = true,
                context = context("s1"),
            )

        assertTrue(out.contains("No matching"))
    }
}
