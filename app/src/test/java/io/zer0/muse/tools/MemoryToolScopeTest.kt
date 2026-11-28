package io.zer0.muse.tools

import io.zer0.memory.runtime.DedupPinnedRequest
import io.zer0.memory.runtime.DedupPinnedResult
import io.zer0.memory.runtime.DeleteMemoryRequest
import io.zer0.memory.runtime.DeleteMemoryResult
import io.zer0.memory.runtime.FactPinResult
import io.zer0.memory.runtime.MemoryScopeKey
import io.zer0.memory.runtime.MemorySearchRequest
import io.zer0.memory.runtime.MemorySearchResult
import io.zer0.memory.runtime.MemoryUseCases
import io.zer0.memory.runtime.PinMemoryRequest
import io.zer0.memory.runtime.PinMemoryResult
import io.zer0.memory.runtime.SaveMemoryRequest
import io.zer0.memory.runtime.SaveMemoryResult
import io.zer0.memory.runtime.UnpinMemoryRequest
import io.zer0.memory.runtime.UnpinMemoryResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 记忆工具的入口收口测试（Task 1）。
 *
 * 钉三件真实会坏事的东西：
 *  1. 作用域只能来自宿主上下文 —— 模型参数里塞 assistantId/spaceId 不许越界；
 *  2. 抑制态默认不外泄：检索入口不许顺手把「已退出自动召回」的事实当正常命中递给模型；
 *  3. 生产注册链路只走 MemoryUseCases，不许再退回直接 store 的老分支 —— 那是第二套真相源。
 */
class MemoryToolScopeTest {

    private class RecordingUseCases : MemoryUseCases {
        val searches = mutableListOf<MemorySearchRequest>()
        val saves = mutableListOf<SaveMemoryRequest>()
        val deletes = mutableListOf<DeleteMemoryRequest>()
        val pins = mutableListOf<PinMemoryRequest>()
        val unpins = mutableListOf<UnpinMemoryRequest>()
        val factPins = mutableListOf<Pair<MemoryScopeKey, Long>>()

        override suspend fun search(request: MemorySearchRequest): MemorySearchResult {
            searches += request
            return MemorySearchResult.Empty
        }

        override suspend fun save(request: SaveMemoryRequest): SaveMemoryResult {
            saves += request
            return SaveMemoryResult.Saved(id = 7L)
        }

        override suspend fun delete(request: DeleteMemoryRequest): DeleteMemoryResult {
            deletes += request
            return DeleteMemoryResult.Rejected("memory belongs to another space")
        }

        override suspend fun pin(request: PinMemoryRequest): PinMemoryResult {
            pins += request
            return PinMemoryResult.Pinned(id = "0123456789")
        }

        override suspend fun unpin(request: UnpinMemoryRequest): UnpinMemoryResult {
            unpins += request
            return UnpinMemoryResult.Removed
        }

        override suspend fun dedupPinned(request: DedupPinnedRequest): DedupPinnedResult = DedupPinnedResult.Removed(count = 0)

        override suspend fun toggleFactPinned(scope: MemoryScopeKey, factId: Long): FactPinResult {
            factPins += scope to factId
            return FactPinResult.Changed(pinned = true)
        }
    }

    private fun context(subagent: Boolean = false) = ToolExecutionContext(
        scope = "chat",
        spaceId = "space-b",
        assistantId = "assistant-a",
        isSubagentContext = subagent,
    )

    @Test
    fun `search takes the trusted scope and includes suppressed facts for explicit recall`() {
        runBlocking {
            val cases = RecordingUseCases()
            val output = SearchMemoryTool.executeWithUseCases(
                args = mapOf(
                    "query" to "徒步",
                    "assistantId" to "someone-else",
                    "spaceId" to "someone-else-space",
                ),
                useCases = cases,
                executionContext = context(),
            )
            val request = cases.searches.single()
            assertEquals("assistant-a", request.scope.assistantId)
            assertEquals("chat", request.scope.scope)
            assertEquals("space-b", request.scope.spaceId)
            assertTrue("显式 search_memory 应允许按需查回已被移出自动召回的事实", request.includeSuppressed)
            assertTrue(output.isNotEmpty())
        }
    }

    @Test
    fun `subagent context cannot write long-term memory`() {
        assertEquals(
            "subagent context cannot write long-term memory",
            longTermMemoryWriteBlockReason(context(subagent = true)),
        )
        assertNull(longTermMemoryWriteBlockReason(context()))
    }

    @Test
    fun `write tools render use case results through the single entry`() {
        runBlocking {
            val cases = RecordingUseCases()
            assertEquals(
                "Saved to long-term memory (id: 7).",
                saveMemoryViaUseCases(cases, context(), mapOf("content" to "记住这条")),
            )
            assertEquals("assistant-a", cases.saves.single().scope.assistantId)
            assertEquals("space-b", cases.saves.single().scope.spaceId)

            assertEquals(
                "Pinned successfully (id: 01234567...).",
                pinMemoryViaUseCases(cases, context(), mapOf("content" to "置顶这条")),
            )
            assertEquals(
                "Unpinned successfully.",
                unpinMemoryViaUseCases(cases, context(), mapOf("keyword" to "生日")),
            )
            assertEquals(
                "Error: memory belongs to another space",
                deleteMemoryViaUseCases(cases, context(), mapOf("id" to "42")),
            )
            assertEquals(42L, cases.deletes.single().factId)
        }
    }

    @Test
    fun `production wiring requires the use cases and has no direct store fallback`() {
        val registrar = readSource("app/src/main/java/io/zer0/muse/tools/AgentToolsRegistrar.kt")
        assertFalse("注册器不许再保留 memoryUseCases 可空分支", registrar.contains("memoryUseCases != null"))
        assertFalse("注册器不许再回退直接 FactStore", registrar.contains("?: factStore"))
        assertTrue("use cases 必须是必需依赖", registrar.contains("private val memoryUseCases: MemoryUseCases,"))

        val koin = readSource("app/src/main/java/io/zer0/muse/AppKoinModule.kt")
        assertTrue(
            "生产图必须用 get 取 MemoryUseCases",
            koin.contains("memoryUseCases = get<io.zer0.memory.runtime.MemoryUseCases>()"),
        )
    }

    private fun readSource(relative: String): String {
        val candidates = listOf(File(relative), File("../" + relative), File("../../" + relative))
        return candidates.firstOrNull { it.isFile }?.readText() ?: error("找不到源文件: " + relative)
    }
}
