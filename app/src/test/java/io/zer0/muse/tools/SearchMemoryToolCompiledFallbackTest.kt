package io.zer0.muse.tools

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.memory.compile.CompiledSectionHit
import io.zer0.memory.fact.FactDb
import io.zer0.memory.fact.FactStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * search_memory 的编译摘要回落。
 *
 * 覆盖：facts 表全空时回落并标注来源、facts 有命中时不回落、没有回落入口时保持原有空结果文案。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SearchMemoryToolCompiledFallbackTest {

    private lateinit var db: FactDb
    private lateinit var store: FactStore

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, FactDb::class.java)
            .allowMainThreadQueries()
            .build()
        store = FactStore(db.factDao(), db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun context(): ToolExecutionContext = ToolExecutionContext(scope = "main", spaceId = "default")

    @Test
    fun `compiled summary answers when the facts table has nothing`() = runBlocking {
        var seenScope: String? = null
        var seenSpace: String? = null
        val result = SearchMemoryTool.execute(
            mapOf("query" to "子奇 徒步"),
            store,
            context(),
            SearchMemoryTool.CompiledSummarySearch { _, scope, spaceId, _ ->
                seenScope = scope
                seenSpace = spaceId
                listOf(CompiledSectionHit("today", "子奇今天去山里徒步了"))
            },
        )

        assertEquals("main", seenScope)
        assertEquals("default", seenSpace)
        assertTrue("结果应标出来源是编译摘要: $result", result.contains("编译摘要"))
        assertTrue("结果应带上摘要原文: $result", result.contains("子奇今天去山里徒步了"))
    }

    @Test
    fun `fact hits keep the compiled summary out of the result`() = runBlocking {
        store.add(FactStore.Fact(fact = "子奇喜欢徒步"), "main", "default")

        val result = SearchMemoryTool.execute(
            mapOf("query" to "子奇"),
            store,
            context(),
            SearchMemoryTool.CompiledSummarySearch { _, _, _, _ ->
                listOf(CompiledSectionHit("today", "不应出现的摘要段落"))
            },
        )

        assertTrue("应返回事实条目: $result", result.contains("Found 1 memory fact"))
        assertFalse("事实命中时不该回落摘要: $result", result.contains("编译摘要"))
        assertFalse(result.contains("不应出现的摘要段落"))
    }

    @Test
    fun `without a summary reader the empty answer stays unchanged`() = runBlocking {
        val result = SearchMemoryTool.execute(mapOf("query" to "子奇 徒步"), store, context())

        assertEquals("No matching memories found.", result)
    }
}
