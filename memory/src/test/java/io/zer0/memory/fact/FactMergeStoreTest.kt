package io.zer0.memory.fact

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v19「多条合并成一条」存储层测试。
 *
 * 覆盖:
 *  - mergeFacts: 新事实落地、源行指向新行、召回只剩新行、列表/显式检索仍能看到源行
 *  - metadata 继承规则: 最高 importance、tags 去重、scope/space 沿用
 *  - unmergeFacts: 新事实消失、指向清空、召回恢复
 *  - 边界: 空列表 / 单条 / id 不存在 / 正文为空 / 归属不符 / 已合并行不再参与合并
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FactMergeStoreTest {

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
    fun `mergeFacts creates aggregate fact and points every source at it`() = runTest {
        val coffee = store.add(FactStore.Fact(fact = "用户喜欢喝美式咖啡"))
        val office = store.add(FactStore.Fact(fact = "用户办公室在望京 SOHO"))
        val cat = store.add(FactStore.Fact(fact = "用户养了一只叫团子的猫"))

        val mergedText = "用户喜欢喝美式咖啡；办公室在望京 SOHO；养了一只叫团子的猫"
        val mergedId = store.mergeFacts(listOf(coffee, office, cat), mergedText)

        assertNotNull("合并应返回新事实 id", mergedId)
        val merged = store.getById(mergedId!!)!!
        assertEquals(mergedText, merged.fact)
        assertNull("合并产物本身不再指向别的行", merged.mergedIntoId)

        // 每一条源行都指向新行
        listOf(coffee, office, cat).forEach { id ->
            assertEquals("源行 $id 应指向合并后的新行", mergedId, store.getById(id)!!.mergedIntoId)
        }

        // 反查聚合成员,供 UI 展示「由 3 条合并」
        assertEquals(listOf(coffee, office, cat), store.getMergedChildren(mergedId).map { it.id })

        // 自动召回只有合并后的新行
        val recall = store.searchRelevantFacts("美式咖啡", limit = 8)
        assertEquals("自动召回只应保留合并后的新行", listOf(mergedId), recall.map { it.id })

        // 列表与显式检索仍能看到被合并的源行(用户要能查到自己合并过什么)
        val listed = store.getByScopeAndSpace("main", "default")
        assertTrue("源行应仍在列表里", listed.map { it.id }.containsAll(listOf(coffee, office, cat)))
        val explicit = store.searchFullTextScoped("美式咖啡", scope = "main", spaceId = "default", limit = 8)
        assertTrue("显式检索应能看到被合并的源行", explicit.any { it.id == coffee })
    }

    @Test
    fun `merged sources leave projection input but stay in lists`() = runTest {
        val a = store.add(FactStore.Fact(fact = "用户喜欢喝美式咖啡"))
        val b = store.add(FactStore.Fact(fact = "用户办公室在望京 SOHO"))

        val mergedId = store.mergeFacts(listOf(a, b), "用户喜欢喝美式咖啡；办公室在望京 SOHO")!!

        // 编译投影取数口径(includeSuppressed=false)只剩合并后的新行
        assertEquals(
            listOf(mergedId),
            store.getByScopeAndSpace("main", "default", includeSuppressed = false).map { it.id },
        )
        assertEquals(listOf(mergedId), store.getByScope("main", includeSuppressed = false).map { it.id })
        assertEquals(listOf(mergedId), store.getBySpace("default", includeSuppressed = false).map { it.id })
        // 显式列表(默认参数)四条都在
        assertEquals(3, store.getByScopeAndSpace("main", "default").size)
    }

    @Test
    fun `mergeFacts inherits highest importance and deduplicated tags`() = runTest {
        val first = dao.insert(
            FactEntity(
                fact = "用户对青霉素过敏",
                tags = """["健康","过敏"]""",
                createdAt = "2026-01-01T00:00:00Z",
                importance = 2,
                category = "medical",
                confidence = 0.8f,
                source = "inferred",
                time = "2026-01-01T00:00",
            ),
        )
        val second = dao.insert(
            FactEntity(
                fact = "用户偏好清淡饮食",
                tags = """["健康","饮食"]""",
                createdAt = "2026-01-02T00:00:00Z",
                importance = 1,
                category = "general",
                confidence = 0.9f,
                source = "user_explicit",
            ),
        )

        val mergedId = store.mergeFacts(listOf(first, second), "用户对青霉素过敏，偏好清淡饮食")!!
        val merged = store.getById(mergedId)!!

        assertEquals("应取源里最高的 importance", 2, merged.importance)
        assertEquals("tags 应合并去重且保持首次出现顺序", listOf("健康", "过敏", "饮食"), merged.tags)
        assertEquals("category 取第一个非 general 的源", "medical", merged.category)
        assertEquals("confidence 取源最高", 0.9f, merged.confidence, 0.001f)
        assertEquals("源里有 user_explicit 则为 user_explicit", "user_explicit", merged.source)
        assertEquals("time 取第一个非空", "2026-01-01T00:00", merged.time)
        assertEquals("scope/space 沿用所选范围", "main", merged.scope)
        assertEquals("default", merged.spaceId)
    }

    @Test
    fun `unmergeFacts removes aggregate and brings sources back to recall`() = runTest {
        val a = store.add(FactStore.Fact(fact = "用户喜欢喝美式咖啡"))
        val b = store.add(FactStore.Fact(fact = "用户办公室在望京 SOHO"))
        val mergedId = store.mergeFacts(listOf(a, b), "用户喜欢喝美式咖啡；办公室在望京 SOHO")!!

        assertTrue("撤销合并应成功", store.unmergeFacts(mergedId))

        assertNull("合并产物应从库里消失", store.getById(mergedId))
        assertTrue("聚合成员集合应为空", store.getMergedChildren(mergedId).isEmpty())
        listOf(a, b).forEach { id ->
            val fact = store.getById(id)!!
            assertNull("源行 $id 的合并指向应被清空", fact.mergedIntoId)
        }
        // 召回恢复: 源行重新进入自动召回,合并文本不再出现
        val recall = store.searchRelevantFacts("美式咖啡", limit = 8)
        assertTrue("源行应重新进入自动召回", recall.any { it.id == a })
        assertFalse("已撤销的合并文本不应再被召回", recall.any { it.id == mergedId })
        assertEquals(
            setOf(a, b),
            store.getByScopeAndSpace("main", "default", includeSuppressed = false).map { it.id }.toSet(),
        )
    }

    @Test
    fun `mergeFacts can be undone for a single source`() = runTest {
        val only = store.add(FactStore.Fact(fact = "用户喜欢喝美式咖啡"))
        val mergedId = store.mergeFacts(listOf(only), "用户喜欢喝美式咖啡（早上）")!!

        assertEquals(mergedId, store.getById(only)!!.mergedIntoId)
        assertEquals(listOf(mergedId), store.searchRelevantFacts("美式咖啡", limit = 8).map { it.id })

        assertTrue(store.unmergeFacts(mergedId))
        assertNull(store.getById(only)!!.mergedIntoId)
        assertTrue(store.searchRelevantFacts("美式咖啡", limit = 8).any { it.id == only })
    }

    @Test
    fun `mergeFacts ignores empty input blank text and unknown ids`() = runTest {
        val id = store.add(FactStore.Fact(fact = "用户喜欢喝美式咖啡"))
        val before = store.size()

        assertNull("空列表不应产生新事实", store.mergeFacts(emptyList(), "合并文本"))
        assertNull("空正文不应产生新事实", store.mergeFacts(listOf(id), "   "))
        assertNull("id 全部不存在时不应产生新事实", store.mergeFacts(listOf(999_999L), "合并文本"))
        assertEquals("边界输入不应改动任何行", before, store.size())
        assertNull("源行不应被标记", store.getById(id)!!.mergedIntoId)
    }

    @Test
    fun `mergeFacts skips sources outside the requested scope and already merged rows`() = runTest {
        val mainFact = store.add(FactStore.Fact(fact = "用户喜欢喝美式咖啡"), scope = "main")
        val otherScope = store.add(FactStore.Fact(fact = "用户办公室在望京 SOHO"), scope = "assistant-A")

        assertNull(
            "选中的行全部落在别的作用域时不做任何改动",
            store.mergeFacts(listOf(otherScope), "合并文本", scope = "main"),
        )
        assertNull("归属不符的行不应被标记", store.getById(otherScope)!!.mergedIntoId)

        // 混合选择: 只合并与主源同 scope + 同 space 的行
        val second = store.add(FactStore.Fact(fact = "用户养了一只叫团子的猫"), scope = "main")
        val mergedId = store.mergeFacts(listOf(mainFact, otherScope, second), "合并文本", scope = "main")!!
        assertEquals(listOf(mainFact, second), store.getMergedChildren(mergedId).map { it.id })
        assertNull("跨作用域的行不参与合并", store.getById(otherScope)!!.mergedIntoId)

        // 已合并过的行不再参与第二次合并(避免链式指向)
        val third = store.add(FactStore.Fact(fact = "用户喜欢在周末爬山"), scope = "main")
        val secondMerge = store.mergeFacts(listOf(mainFact, third), "合并文本二", scope = "main")!!
        assertEquals("已合并行应被跳过", listOf(third), store.getMergedChildren(secondMerge).map { it.id })
        assertEquals("已合并行仍指回第一次的合并产物", mergedId, store.getById(mainFact)!!.mergedIntoId)
    }
}
