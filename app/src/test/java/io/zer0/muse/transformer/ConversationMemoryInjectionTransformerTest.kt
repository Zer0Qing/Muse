package io.zer0.muse.transformer

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.Model
import io.zer0.ai.core.UIMessage
import io.zer0.memory.llm.MemoryLlmClient
import io.zer0.memory.summary.SessionSummaryDao
import io.zer0.memory.summary.SessionSummaryEntity
import io.zer0.memory.summary.SessionSummaryManager
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationMemoryInjectionTransformerTest {

    @Test
    fun injectsOnlyCurrentSessionMemoryImmediatelyAfterCompressionSummary() = runTest {
        val transformer = transformer(
            SessionSummaryEntity(
                sessionId = "session-1",
                createdAt = "2026-10-01T00:00:00Z",
                updatedAt = "2026-10-02T00:00:00Z",
                summary = "Assistant promised to continue checking.",
                messageCount = 12,
                assistantId = "default",
                spaceId = "default",
            ),
        )
        val messages = listOf(
            system("static prompt"),
            system("${CONTEXT_COMPRESSED_MARKER} old history"),
            UIMessage(role = MessageRole.USER, content = "continue"),
        )

        val transformed = transformer.transform(messages, context())

        assertEquals(messages[1], transformed[1])
        assertTrue(transformed[2].content.contains("Assistant promised to continue checking."))
        assertTrue(transformed[2].content.contains("<conversation_memory>"))
        assertEquals(messages[2], transformed[3])
    }

    @Test
    fun doesNotInjectWithoutCompressionOrWhenConversationMemoryIsDisabled() = runTest {
        val transformer = transformer(
            SessionSummaryEntity(
                sessionId = "session-1",
                createdAt = "2026-10-01T00:00:00Z",
                updatedAt = "2026-10-02T00:00:00Z",
                summary = "Previous context.",
                messageCount = 12,
            ),
        )
        val messages = listOf(system("static prompt"), UIMessage(role = MessageRole.USER, content = "hello"))
        val compressedMessages = messages + system("${CONTEXT_COMPRESSED_MARKER} history")

        assertEquals(messages, transformer.transform(messages, context()))
        assertEquals(
            compressedMessages,
            transformer.transform(compressedMessages, context(memoryEnabled = false)),
        )
    }

    @Test
    fun doesNotInjectAcrossSessionAssistantOrSpaceBoundaries() = runTest {
        val transformer = transformer(
            SessionSummaryEntity(
                sessionId = "session-1",
                createdAt = "2026-10-01T00:00:00Z",
                updatedAt = "2026-10-02T00:00:00Z",
                summary = "Private assistant context.",
                messageCount = 12,
                assistantId = "assistant-1",
                spaceId = "work",
            ),
        )
        val messages = listOf(system("${CONTEXT_COMPRESSED_MARKER} history"))

        assertEquals(messages, transformer.transform(messages, context(sessionId = "session-2", scope = "assistant-1", space = "work")))
        assertEquals(messages, transformer.transform(messages, context(scope = "assistant-2", space = "work")))
        assertEquals(messages, transformer.transform(messages, context(scope = "assistant-1", space = "personal")))
    }

    @Test
    fun doesNotDuplicateAnAlreadyInjectedSessionSummary() = runTest {
        val transformer = transformer(
            SessionSummaryEntity(
                sessionId = "session-1",
                createdAt = "2026-10-01T00:00:00Z",
                updatedAt = "2026-10-02T00:00:00Z",
                summary = "Previous context.",
                messageCount = 12,
            ),
        )
        val existingMemory = system("<conversation_memory>already present</conversation_memory>")
        val messages = listOf(system("${CONTEXT_COMPRESSED_MARKER} history"), existingMemory)

        assertEquals(messages, transformer.transform(messages, context()))
    }

    private fun transformer(summary: SessionSummaryEntity): ConversationMemoryInjectionTransformer = ConversationMemoryInjectionTransformer(
        SessionSummaryManager(
            dao = SingleSessionSummaryDao(summary),
            llmClient = NoCallMemoryLlmClient,
        ),
    )

    private fun context(
        sessionId: String = "session-1",
        memoryEnabled: Boolean = true,
        scope: String = "main",
        space: String = "default",
    ) = TransformContext(
        sessionId = sessionId,
        extras = mapOf(
            "conversation_memory_enabled" to memoryEnabled,
            "current_scope" to scope,
            "current_space" to space,
        ),
    )

    private fun system(content: String) = UIMessage(role = MessageRole.SYSTEM, content = content)
}

private class SingleSessionSummaryDao(
    private val summary: SessionSummaryEntity,
) : SessionSummaryDao {
    override suspend fun upsert(entity: SessionSummaryEntity) = Unit
    override suspend fun get(sessionId: String): SessionSummaryEntity? = summary.takeIf { it.sessionId == sessionId }
    override suspend fun getAll(): List<SessionSummaryEntity> = listOf(summary)
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
    // 助手被删除时要清掉它名下的摘要;单会话假件里没有这条助手的数据,固定返回 0 行。
    override suspend fun deleteByAssistant(assistantId: String): Int = 0
}

private object NoCallMemoryLlmClient : MemoryLlmClient {
    override suspend fun callText(
        systemPrompt: String,
        userContent: String,
        model: Model?,
        temperature: Float,
        maxTokens: Int,
        timeoutMs: Long,
    ): String = error("conversation memory injection must not call the LLM")
}
