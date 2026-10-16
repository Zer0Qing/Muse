package io.zer0.memory.fact

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.ai.core.Model
import io.zer0.memory.llm.MemoryLlmClient
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.0.92: LLM 记忆整合器 (LlmFactConsolidator) 测试。
 *
 * 覆盖:
 *  - 相似簇被合并成一条(keeper 保留、其余删除);
 *  - 模型显式 __SKIP__ → 不合并;
 *  - 模型疑似原样输出(残留编号)→ 不合并;
 *  - 模型异常 → 静默降级,数据不变。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LlmFactConsolidatorTest {

    private lateinit var db: FactDb
    private lateinit var dao: FactDao
    private lateinit var store: FactStore

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, FactDb::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.factDao()
        store = FactStore(dao, db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** 可控 fake LLM — 记录调用次数,按预设返回。 */
    private class FakeLlm(private val response: String) : MemoryLlmClient {
        var calls = 0
        override suspend fun callText(
            systemPrompt: String,
            userContent: String,
            model: Model?,
            temperature: Float,
            maxTokens: Int,
            timeoutMs: Long,
        ): String {
            calls++
            return response
        }
    }

    private suspend fun seedSimilarPair() {
        // 用"前缀包含"型相似对,保证进入 findSimilarGroups 的聚簇(算法层可判),
        // 测试聚焦整合器行为本身,不依赖 bigram 阈值的边缘值。
        store.add(FactStore.Fact(fact = "用户喜欢吃苹果"))
        store.add(FactStore.Fact(fact = "用户喜欢吃苹果和香蕉"))
    }

    @Test
    fun mergesSimilarGroupIntoOneFact() = runTest {
        seedSimilarPair()
        val llm = FakeLlm("用户喜欢吃苹果和香蕉")
        val consolidator = LlmFactConsolidator(llm)

        val merged = consolidator.consolidate(store, "main", "default")

        assertEquals(1, merged)
        assertEquals("应只剩一条", 1, store.getByScopeAndSpace("main", "default").size)
        assertEquals("用户喜欢吃苹果和香蕉", store.getByScopeAndSpace("main", "default").first().fact)
    }

    @Test
    fun skipMarkerMeansNoMerge() = runTest {
        seedSimilarPair()
        val llm = FakeLlm("__SKIP__")
        val consolidator = LlmFactConsolidator(llm)

        val merged = consolidator.consolidate(store, "main", "default")

        assertEquals(0, merged)
        assertEquals("数据保持不变", 2, store.getByScopeAndSpace("main", "default").size)
    }

    @Test
    fun numberedRawOutputMeansNoMerge() = runTest {
        seedSimilarPair()
        val llm = FakeLlm("1. 用户喜欢吃苹果\n2. 用户喜欢吃苹果和香蕉")
        val consolidator = LlmFactConsolidator(llm)

        val merged = consolidator.consolidate(store, "main", "default")

        assertEquals(0, merged)
        assertEquals(2, store.getByScopeAndSpace("main", "default").size)
    }

    @Test
    fun `mergeIntoKeeper is atomic and cleans derived rows`() = runTest {
        // v2.6.2（记忆审计）: keeper 更新 + 成员删除在同一事务；成员删除时同步清 FTS/links/revisions。
        val id1 = store.add(FactStore.Fact(fact = "用户喜欢吃苹果"))
        val id2 = store.add(FactStore.Fact(fact = "用户喜欢吃苹果和香蕉"))
        val idMissing = 999_999L

        val removed = store.mergeIntoKeeper(
            keeperId = id1,
            mergedContent = "用户喜欢吃苹果和香蕉",
            removableIds = listOf(id2, idMissing),
            scope = "main",
            spaceId = "default",
        )

        assertEquals("只删除存在的成员", 1, removed)
        assertEquals("keeper 保留且内容已更新", 1, store.getByScopeAndSpace("main", "default").size)
        assertEquals("用户喜欢吃苹果和香蕉", store.getByScopeAndSpace("main", "default").first().fact)
    }

    @Test
    fun `mergeIntoKeeper does nothing when keeper ownership mismatches`() = runTest {
        val id1 = store.add(FactStore.Fact(fact = "甲的记忆"))
        val id2 = store.add(FactStore.Fact(fact = "乙的记忆"))
        val removed = store.mergeIntoKeeper(
            keeperId = id1,
            mergedContent = "合并后的内容",
            removableIds = listOf(id2),
            scope = "other_scope",
            spaceId = "default",
        )
        assertEquals("归属不符时不改动", -1, removed)
        assertEquals("两条原事实均保留", 2, store.getByScopeAndSpace("main", "default").size)
    }

    @Test
    fun llmFailureDegradesSilently() = runTest {        seedSimilarPair()
        val llm = object : MemoryLlmClient {
            override suspend fun callText(
                systemPrompt: String,
                userContent: String,
                model: Model?,
                temperature: Float,
                maxTokens: Int,
                timeoutMs: Long,
            ): String = throw RuntimeException("LLM timeout")
        }
        val consolidator = LlmFactConsolidator(llm)

        val merged = consolidator.consolidate(store, "main", "default")

        assertEquals(0, merged)
        assertEquals("失败时数据不变", 2, store.getByScopeAndSpace("main", "default").size)
    }
}
