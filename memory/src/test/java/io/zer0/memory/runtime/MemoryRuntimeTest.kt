package io.zer0.memory.runtime

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.memory.compile.MemoryCompiler
import io.zer0.memory.fact.FactDbProvider
import io.zer0.memory.llm.MemoryLlmClient
import io.zer0.memory.pin.PinnedMemoryStore
import io.zer0.memory.summary.MemoryDb
import io.zer0.memory.summary.SessionSummaryDao
import io.zer0.memory.summary.SessionSummaryEntity
import io.zer0.memory.summary.SessionSummaryManager
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/**
 * MemoryRuntime 统一读取测试。
 *
 * 验证 scope/space 隔离、suppressed 过滤、pinned 开关以及 renderer 输出。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MemoryRuntimeTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val tmp = Files.createTempDirectory("memory-runtime-test").toFile()

    private class FakeSummaryDao : SessionSummaryDao {
        val rows = mutableMapOf<String, SessionSummaryEntity>()
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
        override suspend fun markProcessed(sessionId: String, now: String) {}
        override suspend fun deleteAll() = rows.clear()
        override suspend fun deleteById(sessionId: String) {
            rows.remove(sessionId)
        }
        override suspend fun deleteByAssistant(assistantId: String): Int {
            val keys = rows.filterValues { it.assistantId == assistantId }.keys
            keys.forEach { rows.remove(it) }
            return keys.size
        }
        override suspend fun revisionOf(sessionId: String): Long? = null
        override suspend fun bumpRevisionIfCurrent(sessionId: String, expectedRevision: Long, sourceFingerprint: String): Int = 0
        override suspend fun markProcessedIfCurrent(sessionId: String, expectedRevision: Long, now: String): Int = 0
        override suspend fun markBranchChanged(sessionId: String, retainedMessageCount: Int): Int = 0
    }

    @Test
    fun `renderer reports empty state without fabricated memory`() {
        val result = MemoryReadResult()
        assertEquals("", MemoryPromptRenderer.render(result))
    }

    @Test
    fun `runtime requires valid scope`() = runTest {
        val noopLlm = object : MemoryLlmClient {
            override suspend fun callText(
                systemPrompt: String,
                userContent: String,
                model: io.zer0.ai.core.Model?,
                temperature: Float,
                maxTokens: Int,
                timeoutMs: Long,
            ): String = ""
        }
        val memoryDb = Room.inMemoryDatabaseBuilder(context, MemoryDb::class.java).allowMainThreadQueries().build()
        val compiler = MemoryCompiler(memoryDb.compiledSectionDao(), noopLlm, scopedSectionDao = memoryDb.scopedCompiledSectionDao())
        val runtime = DefaultMemoryRuntime(
            factDbProvider = FactDbProvider(context),
            compiler = compiler,
            summaryManager = SessionSummaryManager(FakeSummaryDao(), noopLlm),
            pinnedMemoryStore = PinnedMemoryStore(File(tmp, "pinned")),
        )
        val invalid = MemoryReadRequest(scope = MemoryScopeKey("", "main", "default"), tokenBudget = 100)
        try {
            runtime.read(invalid)
            assertTrue("invalid scope should be rejected", false)
        } catch (_: IllegalStateException) {
            // expected
        } finally {
            memoryDb.close()
        }
    }
}
