package io.zer0.memory.fact

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * v12 (T2-2): 运行时相关记忆检索测试。
 * searchRelevantFacts 按 query 用 FTS 召回,并按 scope + space 过滤,
 * 防止跨助手/跨空间串记忆。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SearchRelevantFactsTest {

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

    @Test
    fun `recalls relevant facts by query`() = runTest {
        store.add(FactStore.Fact(fact = "用户喜欢喝美式咖啡"))
        store.add(FactStore.Fact(fact = "用户最近在筹备搬家"))
        store.add(FactStore.Fact(fact = "用户下周要交论文初稿"))

        val hits = store.searchRelevantFacts("咖啡", limit = 5)
        assertEquals("应召回咖啡相关事实", 1, hits.size)
        assertTrue(hits[0].fact.contains("咖啡"))
    }

    @Test
    fun `filters by scope and space`() = runTest {
        store.add(FactStore.Fact(fact = "用户喜欢喝美式咖啡"), scope = "main", spaceId = "default")
        store.add(FactStore.Fact(fact = "用户喜欢喝美式咖啡"), scope = "assistant-1", spaceId = "default")
        store.add(FactStore.Fact(fact = "用户喜欢喝美式咖啡"), scope = "main", spaceId = "work")

        val mainHits = store.searchRelevantFacts("咖啡", scope = "main", spaceId = "default", limit = 5)
        assertEquals("只召回 main/default 空间", 1, mainHits.size)

        val workHits = store.searchRelevantFacts("咖啡", scope = "main", spaceId = "work", limit = 5)
        assertEquals("只召回 main/work 空间", 1, workHits.size)
    }

    @Test
    fun `relevant search filters scope before applying result limit`() = runTest {
        repeat(30) { index ->
            store.add(
                FactStore.Fact(fact = "其他助手的咖啡事实 $index"),
                scope = "assistant-$index",
                spaceId = "default",
            )
        }
        store.add(
            FactStore.Fact(fact = "当前助手的咖啡偏好"),
            scope = "main",
            spaceId = "default",
        )

        val hits = store.searchRelevantFacts("咖啡", scope = "main", spaceId = "default", limit = 1)

        assertEquals(1, hits.size)
        assertEquals("当前助手的咖啡偏好", hits.single().fact)
    }

    @Test
    fun `scoped full text never returns another space`() = runTest {
        store.add(FactStore.Fact(fact = "工作空间里的咖啡偏好"), scope = "main", spaceId = "work")
        store.add(FactStore.Fact(fact = "生活空间里的咖啡偏好"), scope = "main", spaceId = "life")
        store.add(FactStore.Fact(fact = "其他助手里的咖啡偏好"), scope = "assistant-1", spaceId = "work")

        val hits = store.searchFullTextScoped("咖啡", scope = "main", spaceId = "work", limit = 10)

        assertEquals(1, hits.size)
        assertEquals("工作空间里的咖啡偏好", hits.single().fact)
        assertEquals("main", hits.single().scope)
        assertEquals("work", hits.single().spaceId)
    }

    @Test
    fun `scoped tag search never returns another scope or space`() = runTest {
        store.add(FactStore.Fact(fact = "工作标签事实", tags = listOf("preference")), scope = "main", spaceId = "work")
        store.add(FactStore.Fact(fact = "生活标签事实", tags = listOf("preference")), scope = "main", spaceId = "life")
        store.add(FactStore.Fact(fact = "其他助手标签事实", tags = listOf("preference")), scope = "assistant-1", spaceId = "work")

        val hits = store.searchByTagsScoped(
            queryTags = listOf("preference"),
            scope = "main",
            spaceId = "work",
            limit = 10,
        )

        assertEquals(1, hits.size)
        assertEquals("工作标签事实", hits.single().fact)
        assertEquals("main", hits.single().scope)
        assertEquals("work", hits.single().spaceId)
    }

    @Test
    fun `empty query returns empty`() = runTest {
        store.add(FactStore.Fact(fact = "用户喜欢喝美式咖啡"))
        assertTrue(store.searchRelevantFacts("").isEmpty())
        assertTrue(store.searchRelevantFacts("   ").isEmpty())
    }

    @Test
    fun `recall keeps its size when many candidates are suppressed`() = runTest {
        // v17: 出场记忆仍占 FTS 候选窗口,召回不能因此变少。
        // 先入库的 14 条全部已出场,按 rowid 会排在前面的窗口里。
        val suppressedIds = (1..14).map { index ->
            dao.insert(
                FactEntity(
                    fact = "已出场的咖啡记忆 $index",
                    tags = "[]",
                    createdAt = Instant.now().toString(),
                    importance = 0,
                    suppressedAt = Instant.now().toString(),
                ),
            )
        }.toSet()
        repeat(10) { index ->
            dao.insert(
                FactEntity(
                    fact = "仍在召回中的咖啡记忆 $index",
                    tags = "[]",
                    createdAt = Instant.now().toString(),
                    importance = 0,
                ),
            )
        }
        store.rebuildFtsIndex()

        val hits = store.searchRelevantFacts("咖啡", limit = 5)

        assertEquals("出场不应把召回条数挤少", 5, hits.size)
        assertTrue("召回结果不得包含已出场的记忆", hits.none { it.id in suppressedIds })
    }
}
