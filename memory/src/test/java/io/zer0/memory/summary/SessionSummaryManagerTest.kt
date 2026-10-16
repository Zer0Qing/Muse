package io.zer0.memory.summary

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.Model
import io.zer0.ai.core.UIMessage
import io.zer0.memory.llm.MemoryLlmClient
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
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

    @Test
    fun concurrentRollingSummaryForSameSessionSerializesCriticalSection() = runTest {
        // v2.6.2（记忆审计）: 同一 session 的两次 rollingSummary 并发时，
        // createRollingSummaryDraft（含 LLM 调用与写入）必须在锁内串行，不得交叠。
        // 否则两次调用会基于同一份旧快照各自生成摘要，后写覆盖前写。
        val dao = InMemorySessionSummaryDao()
        val llm = ConcurrencyProbeLlmClient()
        val manager = SessionSummaryManager(dao, llm)

        val older = listOf(UIMessage(role = MessageRole.USER, content = "第一条", createdAt = 1L))
        val newer = listOf(
            UIMessage(role = MessageRole.USER, content = "第一条", createdAt = 1L),
            UIMessage(role = MessageRole.USER, content = "第二条", createdAt = 2L),
        )

        val a = async { manager.rollingSummary("s1", older, null) }
        val b = async { manager.rollingSummary("s1", newer, null) }
        a.await()
        b.await()

        assertTrue("同一 session 的临界区不得交叠", llm.maxConcurrent <= 1)
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

/** v2.6.2: 可读写的内存 DAO，用于验证并发写入后的最终状态。 */
private class InMemorySessionSummaryDao : SessionSummaryDao {
    private val rows = mutableMapOf<String, SessionSummaryEntity>()

    override suspend fun upsert(entity: SessionSummaryEntity) {
        rows[entity.sessionId] = entity
    }

    override suspend fun get(sessionId: String): SessionSummaryEntity? = rows[sessionId]

    override suspend fun getAll(): List<SessionSummaryEntity> = rows.values.toList()

    override suspend fun getInRange(
        startISO: String,
        endISO: String,
        since: String?,
        assistantId: String?,
        spaceId: String?,
    ): List<SessionSummaryEntity> = rows.values.toList()

    override suspend fun getDirty(): List<SessionSummaryEntity> = rows.values.toList()

    override suspend fun markProcessed(sessionId: String, now: String) = Unit

    override suspend fun deleteAll() {
        rows.clear()
    }

    override suspend fun deleteById(sessionId: String) {
        rows.remove(sessionId)
    }
}

/** v2.6.2: 探测同一 session 临界区内 LLM 调用是否交叠。 */
private class ConcurrencyProbeLlmClient : MemoryLlmClient {
    private var concurrent = 0
    var maxConcurrent = 0
        private set

    override suspend fun callText(
        systemPrompt: String,
        userContent: String,
        model: Model?,
        temperature: Float,
        maxTokens: Int,
        timeoutMs: Long,
    ): String {
        concurrent++
        maxConcurrent = maxOf(maxConcurrent, concurrent)
        // 固定窗口:无锁时第二个同 session 调用会在此窗口内进入,从而暴露交叠。
        delay(200)
        concurrent--
        return "### 重要事实\n- 事实\n### 事情经过\n- 经过"
    }
}
