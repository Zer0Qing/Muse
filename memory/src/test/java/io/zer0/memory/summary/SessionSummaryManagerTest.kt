package io.zer0.memory.summary

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.Model
import io.zer0.ai.core.UIMessage
import io.zer0.memory.llm.MemoryLlmClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionSummaryManagerTest {

    @Test
    fun rollingSummaryInputKeepsOnlyAssistantVibeFromMoodMetadata() = runTest {
        val dao = EmptySessionSummaryDao()
        val llmClient = CapturingMemoryLlmClient(
            response = """
            ### 重要事实
            - 无
            ### 事情经过
            - 助手承诺继续跟进。
            """.trimIndent(),
        )

        val manager = SessionSummaryManager(dao, llmClient)
        manager.createRollingSummaryDraft(
            sessionId = "session-1",
            messages = listOf(
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    content = "我会继续跟进这件事。",
                    mood = "Vibe: 有些担心\nSparks: 暂时想到的联想\nReflections: 还需要核对\nWill: 继续检查",
                    createdAt = 1_798_914_240_000L,
                ),
            ),
            model = null,
        )

        assertTrue(llmClient.userContent.contains("助手当时表达的感受"))
        assertTrue(llmClient.userContent.contains("Vibe: 有些担心"))
        assertFalse(llmClient.userContent.contains("Sparks:"))
        assertFalse(llmClient.userContent.contains("Reflections:"))
        assertFalse(llmClient.userContent.contains("Will:"))
        assertTrue(llmClient.userContent.contains("我会继续跟进这件事。"))
    }
}

private class EmptySessionSummaryDao : SessionSummaryDao {
    override suspend fun upsert(entity: SessionSummaryEntity) = Unit
    override suspend fun get(sessionId: String): SessionSummaryEntity? = null
    override suspend fun getAll(): List<SessionSummaryEntity> = emptyList()
    override suspend fun getInRange(
        startISO: String,
        endISO: String,
        since: String?,
        assistantId: String?,
        spaceId: String?,
    ): List<SessionSummaryEntity> = emptyList()
    override suspend fun getDirty(): List<SessionSummaryEntity> = emptyList()
    override suspend fun markProcessed(sessionId: String, now: String) = Unit
    override suspend fun deleteAll() = Unit
    override suspend fun deleteById(sessionId: String) = Unit
}

private class CapturingMemoryLlmClient(
    private val response: String,
) : MemoryLlmClient {
    var userContent: String = ""
        private set

    override suspend fun callText(
        systemPrompt: String,
        userContent: String,
        model: Model?,
        temperature: Float,
        maxTokens: Int,
        timeoutMs: Long,
    ): String {
        this.userContent = userContent
        return response
    }
}
