package io.zer0.memory.fact

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.memory.compile.FactsSectionProjector
import io.zer0.memory.ticker.MemoryConfig
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Phase 2.3.4: [FactStore] 单元测试。
 *
 * 用 Room in-memory 数据库(Robolectric 提供 SQLite)验证核心行为:
 *  - add / addBatch: 去重合并 + PII 脱敏 + 智能重要度推断
 *  - searchFullText: FTS4 全文检索 + 单字回退 LIKE
 *  - searchByTags: json_each 精确匹配 + 多标签 OR 逻辑
 *  - applyDecay: 关键事实(importance=2)永不衰减,命中加成延长保留
 *  - scope 隔离: 不同作用域的事实互不影响
 *
 * 设计说明:
 *  - 使用 `Room.inMemoryDatabaseBuilder` 让每个测试有独立干净的数据库
 *  - @Before 重建 db, @After 关闭,避免测试间状态泄漏
 *  - @Config(sdk=[33]) 用 API 33 的 SQLite 实现(支持 FTS4)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FactStoreTest {

    private lateinit var db: FactDb
    private lateinit var dao: FactDao
    private lateinit var store: FactStore

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, FactDb::class.java)
            .allowMainThreadQueries() // 测试用,避免 dispatcher 切换
            .build()
        dao = db.factDao()
        store = FactStore(dao, db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `applyFieldUpdates updates content importance category and tags atomically`() = runTest {
        // v2.6.2（记忆审计）: 三类字段在同一事务内写入，避免半更新。
        val id = store.add(FactStore.Fact(fact = "旧内容", category = "general"))
        val ok = store.applyFieldUpdates(
            id = id,
            scope = "main",
            spaceId = "default",
            content = "新内容",
            importance = 2,
            category = "work",
            tags = listOf("a", "b"),
        )
        assertTrue(ok)
        val f = store.getById(id)!!
        assertEquals("新内容", f.fact)
        assertEquals(2, f.importance)
        assertEquals("work", f.category)
        assertEquals(listOf("a", "b"), f.tags)
        // 全文检索能命中新内容
        assertTrue(store.searchFullText("新内容").any { it.id == id })
    }

    @Test
    fun `applyFieldUpdates rejects ownership mismatch`() = runTest {
        val id = store.add(FactStore.Fact(fact = "内容"))
        val ok = store.applyFieldUpdates(
            id = id,
            scope = "other",
            spaceId = "default",
            content = "越权修改",
            importance = null,
            category = null,
            tags = null,
        )
        assertFalse(ok)
        assertEquals("内容", store.getById(id)!!.fact)
    }

    // ──────────────────────────────────────────────
    //  add / getById: 基础 CRUD
    // ──────────────────────────────────────────────

    @Test
    fun `add single fact assigns auto-increment id and can be retrieved`() = runTest {
        val fact = FactStore.Fact(fact = "用户喜欢 Kotlin", tags = listOf("preference"))
        val id = store.add(fact)

        assertTrue("add 应返回正数 id", id > 0)
        val retrieved = store.getById(id)
        assertNotNull(retrieved)
        assertEquals("用户喜欢 Kotlin", retrieved!!.fact)
        assertEquals(listOf("preference"), retrieved.tags)
    }

    @Test
    fun `add with PII phone number scrubs to REDACTED`() = runTest {
        val fact = FactStore.Fact(fact = "我的电话是 13800138000 请勿泄露")
        val id = store.add(fact)

        val retrieved = store.getById(id)!!
        assertFalse("电话号码应被脱敏,不应出现在 fact 中", retrieved.fact.contains("13800138000"))
        assertTrue("脱敏后应包含 [REDACTED]", retrieved.fact.contains("[REDACTED]"))
    }

    @Test
    fun `add infers importance 2 for critical keywords`() = runTest {
        // importance=0 传入, FactStore 应通过 inferImportance 推断为 2(关键)
        val fact = FactStore.Fact(fact = "用户对青霉素过敏", importance = 0)
        val id = store.add(fact)

        val retrieved = store.getById(id)!!
        assertEquals("含'过敏'应推断 importance=2", 2, retrieved.importance)
    }

    @Test
    fun `add infers importance 1 for important keywords`() = runTest {
        val fact = FactStore.Fact(fact = "用户喜欢深色模式", importance = 0)
        val id = store.add(fact)

        val retrieved = store.getById(id)!!
        assertEquals("含'喜欢'应推断 importance=1", 1, retrieved.importance)
    }

    @Test
    fun `add infers importance 0 for ordinary text`() = runTest {
        val fact = FactStore.Fact(fact = "用户提到了天气", importance = 0)
        val id = store.add(fact)

        val retrieved = store.getById(id)!!
        assertEquals("普通文本应推断 importance=0", 0, retrieved.importance)
    }

    @Test
    fun `add respects explicit importance when set`() = runTest {
        val fact = FactStore.Fact(fact = "普通文本", importance = 2)
        val id = store.add(fact)

        val retrieved = store.getById(id)!!
        assertEquals("显式设置 importance 时不应被覆盖", 2, retrieved.importance)
    }

    @Test
    fun `add with scope stores fact under given scope`() = runTest {
        val id = store.add(FactStore.Fact(fact = "子助手记忆"), scope = "assistant-123")

        val retrieved = store.getById(id)!!
        assertEquals("assistant-123", retrieved.scope)
    }

    // ──────────────────────────────────────────────
    //  addBatch
    // ──────────────────────────────────────────────

    @Test
    fun `addBatch with empty list returns 0`() = runTest {
        val result = store.addBatch(emptyList())
        assertEquals(0, result)
        assertEquals(0, store.size())
    }

    @Test
    fun `addBatch inserts multiple facts and returns count`() = runTest {
        val entries = listOf(
            FactStore.Fact(fact = "事实 A"),
            FactStore.Fact(fact = "事实 B"),
            FactStore.Fact(fact = "事实 C"),
        )
        val inserted = store.addBatch(entries)

        assertEquals(3, inserted)
        assertEquals(3, store.size())
    }

    @Test
    fun `addBatch merges similar facts instead of duplicating`() = runTest {
        val entries = listOf(
            FactStore.Fact(fact = "用户对青霉素过敏"),
            FactStore.Fact(fact = "对青霉素过敏"), // 去主语后相同,应被识别为相似
        )
        store.addBatch(entries)

        // 应只保留 1 条(后插入的被合并到已存在的)
        assertEquals("相似事实应合并而非重复", 1, store.size())
    }

    @Test
    fun `addBatch uses same scope for all entries`() = runTest {
        store.addBatch(
            listOf(
                FactStore.Fact(fact = "子助手事实 1"),
                FactStore.Fact(fact = "子助手事实 2"),
            ),
            scope = "assistant-A",
        )

        val scoped = store.getByScope("assistant-A")
        assertEquals(2, scoped.size)
        // 其他作用域应为空
        assertEquals(0, store.getByScope("main").size)
    }

    // ──────────────────────────────────────────────
    //  searchFullText
    // ──────────────────────────────────────────────

    @Test
    fun `searchFullText with blank query returns empty`() = runTest {
        store.add(FactStore.Fact(fact = "测试事实"))
        assertTrue(store.searchFullText("").isEmpty())
        assertTrue(store.searchFullText("   ").isEmpty())
    }

    @Test
    fun `searchFullText finds fact by chinese keyword`() = runTest {
        store.add(FactStore.Fact(fact = "用户喜欢 Kotlin 编程语言"))
        store.add(FactStore.Fact(fact = "另一个无关事实"))

        val results = store.searchFullText("Kotlin")
        assertTrue("应至少找到 1 条匹配", results.isNotEmpty())
        assertTrue("结果应包含 Kotlin", results.any { it.fact.contains("Kotlin") })
    }

    @Test
    fun `searchFullText single chinese char falls back to LIKE`() = runTest {
        // 单字查询走 LIKE 回退(FTS4 2-gram 无法命中单字)
        store.add(FactStore.Fact(fact = "深色模式"))

        val results = store.searchFullText("深")
        assertTrue("单字查询应通过 LIKE 命中", results.isNotEmpty())
    }

    // ──────────────────────────────────────────────
    //  searchByTags
    // ──────────────────────────────────────────────

    @Test
    fun `searchByTags with empty tags returns empty`() = runTest {
        store.add(FactStore.Fact(fact = "事实", tags = listOf("preference")))
        assertTrue(store.searchByTags(emptyList()).isEmpty())
    }

    @Test
    fun `searchByTags matches single tag`() = runTest {
        store.add(FactStore.Fact(fact = "偏好", tags = listOf("preference")))
        store.add(FactStore.Fact(fact = "身份", tags = listOf("identity")))

        val results = store.searchByTags(listOf("preference"))
        assertEquals(1, results.size)
        assertEquals("偏好", results[0].fact)
    }

    @Test
    fun `searchByTags OR logic matches multiple tags`() = runTest {
        store.add(FactStore.Fact(fact = "A", tags = listOf("tag1")))
        store.add(FactStore.Fact(fact = "B", tags = listOf("tag2")))
        store.add(FactStore.Fact(fact = "C", tags = listOf("tag3")))

        val results = store.searchByTags(listOf("tag1", "tag2"))
        assertEquals("OR 逻辑应匹配 2 条", 2, results.size)
    }

    @Test
    fun `searchByTags returns matchCount for multi-tag facts`() = runTest {
        // 一条事实同时带 tag1 + tag2 → matchCount=2,应排在前面
        store.add(FactStore.Fact(fact = "双标签", tags = listOf("tag1", "tag2")))
        store.add(FactStore.Fact(fact = "单标签", tags = listOf("tag1")))

        val results = store.searchByTags(listOf("tag1", "tag2"))
        assertEquals(2, results.size)
        // matchCount=2 的应排在前(按 matchCount DESC)
        assertEquals("双标签", results[0].fact)
        assertEquals(2, results[0].matchCount)
    }

    // ──────────────────────────────────────────────
    //  applyDecay: 分数出场(行保留) + 关键事实永不衰减
    // ──────────────────────────────────────────────

    @Test
    fun `applyDecay preserves critical facts with importance 2`() = runTest {
        // 直接通过 DAO 插入一条 100 天前的关键事实
        val oldDate = Instant.now().minus(100, ChronoUnit.DAYS).toString()
        val id = dao.insert(
            FactEntity(
                fact = "青霉素过敏(关键)",
                tags = "[]",
                createdAt = oldDate,
                importance = 2, // 关键,永不衰减
                lastHitAt = null,
            ),
        )
        store.rebuildFtsIndex()

        val outOfRecall = store.applyDecay(MemoryConfig())
        assertEquals("不应让关键事实出场", 0, outOfRecall)
        assertEquals("关键事实应仍存在", 1, store.size())
        assertNull("关键事实不得被写出场标记", store.getById(id)!!.suppressedAt)
        assertTrue("关键事实应仍可召回", store.searchRelevantFacts("过敏", limit = 5).any { it.id == id })
    }

    @Test
    fun `applyDecay takes low score facts out of recall but keeps the row`() = runTest {
        // 默认阈值 4.5、λ=0.02:50 天前的普通事实 ≈ 10·e^-1 ≈ 3.68 → 低于阈值
        val oldDate = Instant.now().minus(50, ChronoUnit.DAYS).toString()
        val id = dao.insert(
            FactEntity(
                fact = "50天前的咖啡偏好",
                tags = "[]",
                createdAt = oldDate,
                importance = 0,
                lastHitAt = null,
            ),
        )
        store.rebuildFtsIndex()

        val outOfRecall = store.applyDecay(MemoryConfig())
        assertEquals("应至少有 1 条出场", 1, outOfRecall)
        assertEquals("行必须保留,衰变不再删数据", 1, store.size())
        assertNotNull("应写入出场时间", store.getById(id)!!.suppressedAt)
        assertTrue("低分事实不应再被自动召回", store.searchRelevantFacts("咖啡", limit = 5).isEmpty())
        // 记忆页仍能列出这一条 —— 出场只是不再参与召回
        assertEquals(1, store.getByScope("main").size)
    }

    @Test
    fun `applyDecay keeps recent facts in recall`() = runTest {
        // 插入 5 天前的普通事实(远小于出场所需时间)
        val recentDate = Instant.now().minus(5, ChronoUnit.DAYS).toString()
        val id = dao.insert(
            FactEntity(
                fact = "近期咖啡偏好",
                tags = "[]",
                createdAt = recentDate,
                importance = 0,
                lastHitAt = null,
            ),
        )
        store.rebuildFtsIndex()

        val outOfRecall = store.applyDecay(MemoryConfig())
        assertEquals("近期事实不应出场", 0, outOfRecall)
        assertNull(store.getById(id)!!.suppressedAt)
        assertTrue("分数应落库且高于阈值", store.getById(id)!!.score >= MemoryConfig().compileThreshold)
        assertTrue(store.searchRelevantFacts("咖啡", limit = 5).any { it.id == id })
    }

    @Test
    fun `applyDecay uses hitBonus to keep hit facts in recall`() = runTest {
        // 50天前创建、但 5 天前被命中过 → 时钟从命中时间起算,已经补过命中加成
        val oldCreated = Instant.now().minus(50, ChronoUnit.DAYS).toString()
        val recentHit = Instant.now().minus(5, ChronoUnit.DAYS).toString()
        val id = dao.insert(
            FactEntity(
                fact = "50天前创建但5天前命中的咖啡偏好",
                tags = "[]",
                createdAt = oldCreated,
                importance = 0,
                lastHitAt = recentHit,
            ),
        )
        store.rebuildFtsIndex()

        val outOfRecall = store.applyDecay(MemoryConfig())
        assertEquals("命中过的事实应享 hitBonus,不应出场", 0, outOfRecall)
        assertNull(store.getById(id)!!.suppressedAt)
        assertTrue(store.searchRelevantFacts("咖啡", limit = 5).any { it.id == id })
    }

    @Test
    fun `applyDecay persists score so repeated passes do not double decay`() = runTest {
        val recentDate = Instant.now().minus(10, ChronoUnit.DAYS).toString()
        val id = dao.insert(
            FactEntity(fact = "十天前的咖啡偏好", tags = "[]", createdAt = recentDate, importance = 0),
        )
        store.rebuildFtsIndex()

        store.applyDecay(MemoryConfig())
        val first = store.getById(id)!!.score
        // 期望值:10·e^(-0.02·10) ≈ 8.19
        assertEquals(8.19f, first, 0.05f)

        store.applyDecay(MemoryConfig())
        val second = store.getById(id)!!.score
        assertEquals("第二轮几乎无时间流逝,分数不应被重复打折", first, second, 0.01f)
    }

    @Test
    fun `applyDecay with scope only affects specified scope`() = runTest {
        val oldDate = Instant.now().minus(50, ChronoUnit.DAYS).toString()

        // main scope: 旧普通事实
        val mainId = dao.insert(
            FactEntity(
                fact = "main scope 旧咖啡事实",
                tags = "[]",
                createdAt = oldDate,
                importance = 0,
                lastHitAt = null,
                scope = "main",
            ),
        )
        // assistant-A scope: 同样旧的普通事实
        val assistantId = dao.insert(
            FactEntity(
                fact = "assistant-A scope 旧咖啡事实",
                tags = "[]",
                createdAt = oldDate,
                importance = 0,
                lastHitAt = null,
                scope = "assistant-A",
            ),
        )
        store.rebuildFtsIndex()

        // 仅衰减 main scope
        val outOfRecall = store.applyDecay(MemoryConfig(), scope = "main")
        assertEquals("应仅让 main scope 的 1 条出场", 1, outOfRecall)
        assertNotNull(store.getById(mainId)!!.suppressedAt)
        assertNull("其他作用域不应被动到", store.getById(assistantId)!!.suppressedAt)
        assertEquals("两行都应保留", 2, store.size())
    }

    @Test
    fun `applyDecay with scope and space only affects specified space`() = runTest {
        val oldDate = Instant.now().minus(50, ChronoUnit.DAYS).toString()
        val workId = dao.insert(
            FactEntity(fact = "work 旧咖啡", tags = "[]", createdAt = oldDate, scope = "main", spaceId = "work"),
        )
        val lifeId = dao.insert(
            FactEntity(fact = "life 旧咖啡", tags = "[]", createdAt = oldDate, scope = "main", spaceId = "life"),
        )
        store.rebuildFtsIndex()

        val outOfRecall = store.applyDecay(MemoryConfig(), scope = "main", spaceId = "work")

        assertEquals(1, outOfRecall)
        assertNotNull(store.getById(workId)!!.suppressedAt)
        assertNull(store.getById(lifeId)!!.suppressedAt)
        assertEquals("两行都应保留", 2, store.size())
    }

    @Test
    fun `explicit search revives a suppressed fact back into recall`() = runTest {
        val oldDate = Instant.now().minus(50, ChronoUnit.DAYS).toString()
        val id = dao.insert(
            FactEntity(fact = "50天前的咖啡偏好", tags = "[]", createdAt = oldDate, importance = 0),
        )
        store.rebuildFtsIndex()
        store.applyDecay(MemoryConfig())
        assertNotNull(store.getById(id)!!.suppressedAt)

        // 显式检索(记忆页 / search_memory)仍能命中已出场的行,并把它拉回召回
        val explicitHits = store.searchFullTextScoped("咖啡", scope = "main", spaceId = "default", limit = 5)
        assertTrue("显式检索应能看到已出场的记忆", explicitHits.any { it.id == id })

        val revived = store.getById(id)!!
        assertNull("访问后应清除出场标记", revived.suppressedAt)
        assertTrue("分数应回升到阈值以上", revived.score >= MemoryConfig().compileThreshold)
        assertTrue("应重新进入自动召回", store.searchRelevantFacts("咖啡", limit = 5).any { it.id == id })
    }

    @Test
    fun `re referenced fact is revived by the next decay pass`() = runTest {
        val oldDate = Instant.now().minus(50, ChronoUnit.DAYS).toString()
        val id = dao.insert(
            FactEntity(fact = "用户喜欢喝美式咖啡", tags = "[]", createdAt = oldDate, importance = 0),
        )
        store.rebuildFtsIndex()
        store.applyDecay(MemoryConfig())
        assertNotNull(store.getById(id)!!.suppressedAt)

        // 再次提到同一件事:写入侧合并到已有行(记一次命中),下一轮衰减应把它拉回
        store.add(FactStore.Fact(fact = "用户喜欢喝美式咖啡"))
        val outOfRecall = store.applyDecay(MemoryConfig())

        assertEquals("没有新事实需要出场", 0, outOfRecall)
        val revived = store.getById(id)!!
        assertNull("被重新引用后应回到召回", revived.suppressedAt)
        assertTrue(store.searchRelevantFacts("咖啡", limit = 5).any { it.id == id })
    }

    @Test
    fun `read methods keep suppressed facts by default and hide them on demand`() = runTest {
        val kept = store.add(FactStore.Fact(fact = "用户喜欢喝美式咖啡"))
        // v17: 已出场的记忆在列表/去重/UI 里照旧可见,只有注入上下文的召回传 includeSuppressed=false
        val suppressed = dao.insert(
            FactEntity(
                fact = "用户以前喝摩卡",
                tags = "[]",
                createdAt = Instant.now().toString(),
                importance = 0,
                suppressedAt = Instant.now().toString(),
            ),
        )

        val all = store.getByScope("main")
        assertEquals("默认应包含已出场记忆", setOf(kept, suppressed), all.map { it.id }.toSet())

        val recallable = store.getByScope("main", includeSuppressed = false)
        assertEquals("includeSuppressed=false 应排除已出场记忆", listOf(kept), recallable.map { it.id })

        assertEquals(2, store.getByScopeAndSpace("main", "default").size)
        assertEquals(1, store.getByScopeAndSpace("main", "default", includeSuppressed = false).size)
        assertEquals(2, store.getAll("main").size)
        assertEquals(1, store.getAll("main", includeSuppressed = false).size)
        assertEquals(2, store.getBySpace("default").size)
        assertEquals(1, store.getBySpace("default", includeSuppressed = false).size)
    }

    @Test
    fun `explicit cleanup still deletes rows`() = runTest {
        val oldDate = Instant.now().minus(50, ChronoUnit.DAYS).toString()
        val suppressed = dao.insert(
            FactEntity(fact = "已出场的旧咖啡偏好", tags = "[]", createdAt = oldDate, importance = 0),
        )
        store.add(FactStore.Fact(fact = "用户喜欢喝美式咖啡"))
        store.rebuildFtsIndex()
        store.applyDecay(MemoryConfig())
        assertNotNull(store.getById(suppressed)!!.suppressedAt)

        // 显式清理入口(清空记忆)仍是真的删除
        store.clearAll()

        assertEquals(0, store.size())
        assertNull("清空后不应剩行", store.getById(suppressed))
    }

    // ──────────────────────────────────────────────
    //  scope 隔离
    // ──────────────────────────────────────────────

    @Test
    fun `getAll with scope filter only returns facts from that scope`() = runTest {
        store.add(FactStore.Fact(fact = "main 事实"), scope = "main")
        store.add(FactStore.Fact(fact = "A 事实"), scope = "assistant-A")
        store.add(FactStore.Fact(fact = "B 事实"), scope = "assistant-B")

        val mainOnly = store.getAll(scope = "main")
        assertEquals(1, mainOnly.size)
        assertEquals("main 事实", mainOnly[0].fact)

        val allScopes = store.getAll(scope = null)
        assertEquals(3, allScopes.size)
    }

    @Test
    fun `add deduplication only merges within same scope`() = runTest {
        val fact = FactStore.Fact(fact = "用户喜欢深色模式")
        store.add(fact, scope = "main")
        store.add(fact, scope = "assistant-A") // 不同 scope,不应被合并

        assertEquals("不同 scope 应独立存储", 2, store.size())
        assertEquals(1, store.getByScope("main").size)
        assertEquals(1, store.getByScope("assistant-A").size)
    }

    // ──────────────────────────────────────────────
    //  其他: size / delete / setImportance / update
    // ──────────────────────────────────────────────

    @Test
    fun `size returns 0 for empty database`() = runTest {
        assertEquals(0, store.size())
    }

    @Test
    fun `delete removes fact and returns true`() = runTest {
        val id = store.add(FactStore.Fact(fact = "待删除"))
        assertEquals(1, store.size())

        val success = store.delete(id)
        assertTrue(success)
        assertEquals(0, store.size())
        assertNull(store.getById(id))
    }

    @Test
    fun `delete non-existent id returns false`() = runTest {
        assertFalse(store.delete(99999L))
    }

    @Test
    fun `setImportance updates importance and clamps to 0-2`() = runTest {
        val id = store.add(FactStore.Fact(fact = "测试", importance = 0))

        assertTrue(store.setImportance(id, 5)) // 超界应 clamp 到 2
        assertEquals(2, store.getById(id)!!.importance)

        assertTrue(store.setImportance(id, -3)) // 超界应 clamp 到 0
        assertEquals(0, store.getById(id)!!.importance)
    }

    @Test
    fun `update changes fact content`() = runTest {
        val id = store.add(FactStore.Fact(fact = "原始内容"))
        val success = store.update(id, "更新后内容")
        assertTrue(success)
        assertEquals("更新后内容", store.getById(id)!!.fact)
    }

    @Test
    fun `update with empty content returns false`() = runTest {
        val id = store.add(FactStore.Fact(fact = "原始"))
        assertFalse(store.update(id, "   "))
        assertEquals("原始", store.getById(id)!!.fact)
    }

    @Test
    fun `getBySession returns facts for given session`() = runTest {
        store.add(FactStore.Fact(fact = "A", sessionId = "session-1"))
        store.add(FactStore.Fact(fact = "B", sessionId = "session-2"))
        store.add(FactStore.Fact(fact = "C", sessionId = "session-1"))

        val result = store.getBySession("session-1")
        assertEquals(2, result.size)
    }

    @Test
    fun `clearAll empties the database`() = runTest {
        store.add(FactStore.Fact(fact = "A"))
        store.add(FactStore.Fact(fact = "B"))
        assertEquals(2, store.size())

        store.clearAll()
        assertEquals(0, store.size())
    }

    // ──────────────────────────────────────────────
    //  ensureFtsIndexConsistent / rebuildFtsIndex
    // ──────────────────────────────────────────────

    @Test
    fun `ensureFtsIndexConsistent is idempotent`() = runTest {
        store.add(FactStore.Fact(fact = "事实"))
        // 多次调用应无副作用
        store.ensureFtsIndexConsistent()
        store.ensureFtsIndexConsistent()
        store.ensureFtsIndexConsistent()
        // 仍能搜到
        assertTrue(store.searchFullText("事实").isNotEmpty())
    }

    @Test
    fun `rebuildFtsIndex fully rebuilds fts table`() = runTest {
        // 通过 store.add 正常插入(FTS 已建好)
        store.add(FactStore.Fact(fact = "测试 rebuild"))
        // 模拟 FTS 损坏: 手动 clearFts 后 rebuild
        // 注意: 直接调 store.rebuildFtsIndex 不需要 clear, 内部会 clear+重建
        store.rebuildFtsIndex()

        assertTrue(store.searchFullText("rebuild").isNotEmpty())
    }

    // ──────────────────────────────────────────────
    //  v1.x: 增强去重(bigram 相似 / 否定保护 / 全量去重)
    // ──────────────────────────────────────────────

    @Test
    fun `add does not merge negated opposite facts`() = runTest {
        store.add(FactStore.Fact(fact = "用户不吃香菜"))
        store.add(FactStore.Fact(fact = "用户吃香菜"))

        assertEquals("否定语义相反,不应合并", 2, store.size())
    }

    @Test
    fun `dedupPass merges existing duplicates and skips pinned`() = runTest {
        // 全遍历去重: 措辞插入/删词差异(候选 LIKE 查不到,仅前缀匹配也漏)也能合并
        store.add(FactStore.Fact(fact = "明天上午要考英语四级", time = "2026-08-08T09:00"))
        store.add(FactStore.Fact(fact = "明天上午考英语四级", time = "2026-08-08T09:00"))
        // 置顶记忆不参与合并
        val pinned = store.add(FactStore.Fact(fact = "用户喜欢喝美式咖啡"))
        dao.updatePinnedAt(pinned, Instant.now().toString())
        store.add(FactStore.Fact(fact = "用户爱喝美式咖啡"))

        val merged = store.dedupPass(scope = "main")

        assertTrue("应合并至少 1 条", merged >= 1)
        // 考试类应只剩 1 条
        val all = store.getAll("main")
        assertEquals("考试类应只剩 1 条", 1, all.count { it.fact.contains("四级") })
        // 置顶记忆本身仍保留(即使它与另一条相似)
        assertNotNull(store.getById(pinned))
    }

    // ──────────────────────────────────────────────
    //  审查修复 (2.0): 删除墓碑(B-07 双通道匹配 / B-10 上限裁剪)
    // ──────────────────────────────────────────────

    @Test
    fun `delete records tombstone and matches exact wording`() = runTest {
        val tombstoneFile = java.io.File(
            ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir,
            "tombstone-test-${System.nanoTime()}.json",
        )
        val storeWithTombstones = FactStore(dao, db, tombstoneFile)
        try {
            val id = storeWithTombstones.add(FactStore.Fact(fact = "用户对青霉素过敏"))
            storeWithTombstones.delete(id)
            assertFalse("new scoped tombstones must not be appended to the legacy global file", tombstoneFile.exists())

            val tombstones = storeWithTombstones.getTombstones()
            assertEquals("删除后应记录墓碑", 1, tombstones.size)
            assertTrue(tombstones[0].contains("青霉素过敏"))
            // 精确措辞命中
            assertTrue(FactStore.matchesTombstone("用户对青霉素过敏", tombstones[0]))
        } finally {
            tombstoneFile.delete()
        }
    }

    @Test
    fun `tombstone matches normalized whitespace and punctuation variants`() = runTest {
        val tombstoneFile = java.io.File(
            ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir,
            "tombstone-test-${System.nanoTime()}.json",
        )
        val storeWithTombstones = FactStore(dao, db, tombstoneFile)
        try {
            val id = storeWithTombstones.add(FactStore.Fact(fact = "用户喜欢喝美式咖啡"))
            storeWithTombstones.delete(id)
            val tombstones = storeWithTombstones.getTombstones()

            // B-07: 编译对象是另一 LLM 通道改写后的摘要,措辞几乎必然不同 —
            // 空白压缩 + 标点剥离双通道匹配应命中常见改写形态
            assertTrue(
                "空白差异应命中",
                FactStore.matchesTombstone("用户喜欢喝 美式咖啡", tombstones[0]),
            )
            assertTrue(
                "标点差异应命中",
                FactStore.matchesTombstone("用户喜欢喝美式咖啡。", tombstones[0]),
            )
        } finally {
            tombstoneFile.delete()
        }
    }

    @Test
    fun `tombstone list capped at max entries`() = runTest {
        val tombstoneFile = java.io.File(
            ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir,
            "tombstone-test-${System.nanoTime()}.json",
        )
        val storeWithTombstones = FactStore(dao, db, tombstoneFile)
        try {
            // B-10: 墓碑只增不删会长期膨胀(且每轮 compile 全量加载扫描) —
            // 超过上限时丢弃最旧条目,文件体积与过滤开销有界
            val cap = FactStore.TOMBSTONE_MAX_ENTRIES
            repeat(cap + 50) { i ->
                storeWithTombstones.delete(
                    storeWithTombstones.add(FactStore.Fact(fact = "测试事实编号 $i")),
                )
            }
            val tombstones = storeWithTombstones.getTombstones()
            assertTrue("墓碑数量不得超过上限", tombstones.size <= cap)
            assertEquals("应保留最新条目", "测试事实编号 ${cap + 49}", tombstones.last())
        } finally {
            tombstoneFile.delete()
        }
    }

    @Test
    fun `deletion tombstones are isolated by assistant database scope and space`() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val otherDb = Room.inMemoryDatabaseBuilder(context, FactDb::class.java)
            .allowMainThreadQueries()
            .build()
        val legacyFile = java.io.File(context.cacheDir, "legacy-tombstones-${System.nanoTime()}.json")
        legacyFile.writeText("[\"same preference\"]")
        val otherStore = FactStore(
            otherDb.factDao(),
            otherDb,
            tombstoneFile = legacyFile,
            assistantId = "assistant-b",
        )
        try {
            val deletedId = store.add(
                FactStore.Fact(fact = "same preference"),
                scope = "main",
                spaceId = "work",
            )
            store.add(FactStore.Fact(fact = "same preference"), scope = "main", spaceId = "personal")
            store.add(FactStore.Fact(fact = "same preference"), scope = "assistant-a", spaceId = "work")
            otherStore.add(FactStore.Fact(fact = "same preference"), scope = "main", spaceId = "work")

            assertFalse("wrong assistant ownership must refuse delete", store.delete(deletedId, "assistant-b", "main", "work"))
            assertFalse("wrong scope/space must refuse delete", store.delete(deletedId, "default", "main", "personal"))
            assertNotNull(store.getById(deletedId))
            assertTrue(store.delete(deletedId, "default", "main", "work"))

            assertEquals(listOf("same preference"), store.getTombstones("main", "work"))
            assertTrue(store.getTombstones("main", "personal").isEmpty())
            assertTrue(store.getTombstones("assistant-a", "work").isEmpty())
            assertTrue(
                "an active duplicate is exempt from an old shared text tombstone",
                otherStore.getTombstones("main", "work").isEmpty(),
            )
            assertEquals(
                "legacy tombstone still filters stale compiled text when no active exact fact exists",
                listOf("same preference"),
                otherStore.getTombstones("assistant-a", "work"),
            )
        } finally {
            legacyFile.delete()
            otherDb.close()
        }
    }

    @Test
    fun `expired duplicate rows do not mask deletion tombstones`() = runTest {
        val text = "expired preference"
        val expiredAt = Instant.now().minus(1, ChronoUnit.DAYS).toString()
        val originalId = store.add(FactStore.Fact(fact = text, expiresAt = expiredAt))
        assertTrue(store.delete(originalId, "default", "main", "default"))

        store.add(FactStore.Fact(fact = text, expiresAt = expiredAt))

        assertEquals(listOf(text), store.getTombstones("main", "default"))
    }

    @Test
    fun `tombstone insert failure leaves fact FTS and graph edges untouched`() = runTest {
        val id = store.add(FactStore.Fact(fact = "must remain if tombstone fails"))
        db.memoryLinkDao().insert(
            io.zer0.memory.ai.MemoryLinkEntity(
                sourceFactId = id,
                targetFactId = id + 100,
                sourceTitle = "source",
                targetTitle = "target",
                createdAt = Instant.now().toString(),
            ),
        )
        assertEquals(1, dao.countFts())
        assertEquals(1, db.memoryLinkDao().listByFactId(id).size)
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_fact_tombstone BEFORE INSERT ON fact_deletion_tombstones " +
                "BEGIN SELECT RAISE(ABORT, 'forced tombstone write failure'); END",
        )

        val result = runCatching { store.delete(id, "default", "main", "default") }

        assertTrue("delete must fail when its durable tombstone cannot be committed", result.isFailure)
        assertNotNull("Room transaction rollback must preserve the source fact", store.getById(id))
        assertEquals("FTS must remain intact", 1, dao.countFts())
        assertEquals("graph edges must remain intact", 1, db.memoryLinkDao().listByFactId(id).size)
        assertTrue("rollback must not leave a tombstone", store.getTombstones("main", "default").isEmpty())
    }

    @Test
    fun `fact row failure rolls back tombstone and prior FTS deletion`() = runTest {
        val id = store.add(FactStore.Fact(fact = "transaction should roll back all artifacts"))
        db.memoryLinkDao().insert(
            io.zer0.memory.ai.MemoryLinkEntity(
                sourceFactId = id,
                targetFactId = id + 200,
                sourceTitle = "source",
                targetTitle = "target",
                createdAt = Instant.now().toString(),
            ),
        )
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_fact_delete BEFORE DELETE ON facts " +
                "BEGIN SELECT RAISE(ABORT, 'forced fact row delete failure'); END",
        )

        val result = runCatching { store.delete(id, "default", "main", "default") }

        assertTrue("fact delete trigger must fail the operation", result.isFailure)
        assertNotNull("fact row must roll back", store.getById(id))
        assertEquals("FTS delete must roll back", 1, dao.countFts())
        assertEquals("graph edge delete must roll back", 1, db.memoryLinkDao().listByFactId(id).size)
        assertTrue("inserted tombstone must roll back", store.getTombstones("main", "default").isEmpty())
    }

    @Test
    fun `filterTombstonedLines drops tombstoned lines and keeps others`() {
        val text = "用户对青霉素过敏\n用户喜欢跑步\n用户住在北京"
        val filtered = FactStore.filterTombstonedLines(text, listOf("青霉素过敏"))
        assertEquals("用户喜欢跑步\n用户住在北京", filtered)
    }

    /** v18: 同一实体上相反的明确陈述 —— 新记忆记下它取代了哪条旧记忆。 */
    @Test
    fun `contradicting user statement records what it supersedes`() = runTest {
        val old = store.add(FactStore.Fact(fact = "张三喜欢喝咖啡", source = "user_explicit", entityKey = "张三"))
        val fresh = store.add(FactStore.Fact(fact = "张三讨厌喝咖啡", source = "user_explicit", entityKey = "张三"))

        assertNotEquals("相反断言不应被当成重复合并", old, fresh)
        assertEquals("新记忆应记下它取代的旧记忆", old, store.getById(fresh)!!.supersedesId)
        assertNull("旧记忆自己不再指向别的行", store.getById(old)!!.supersedesId)
    }

    /**
     * v18: 被取代的记忆退出上下文(编译段 + 自动召回),但整行仍在库里:
     * 记忆页可查、显式检索可命中,删掉新记忆就能反悔。
     */
    @Test
    fun `superseded fact leaves context but stays visible and returns when the new one is deleted`() = runTest {
        val old = store.add(FactStore.Fact(fact = "张三喜欢喝咖啡", source = "user_explicit", entityKey = "张三"))
        val fresh = store.add(FactStore.Fact(fact = "张三讨厌喝咖啡", source = "user_explicit", entityKey = "张三"))
        val oldText = store.getById(old)!!.fact
        val freshText = store.getById(fresh)!!.fact

        // 记忆页/列表照旧两条都在
        assertEquals(
            "被取代的记忆仍在库里",
            setOf(old, fresh),
            store.getByScopeAndSpace("main", "default").map { it.id }.toSet(),
        )
        // 编译产出的 FACTS 段只留新的那条
        assertEquals(freshText, FactsSectionProjector.project(store.getByScopeAndSpace("main", "default")))
        // 自动召回(<relevant_memory>)只剩新的
        assertFalse(
            "被取代的记忆不该再进自动召回",
            store.searchRelevantFacts("咖啡", limit = 8).any { it.id == old },
        )
        assertTrue(store.searchRelevantFacts("咖啡", limit = 8).any { it.id == fresh })
        // 显式检索(search_memory 工具/记忆页搜索)仍能查到旧记忆
        assertTrue(
            "显式检索应能查到被取代的记忆",
            store.searchFullTextScoped("喜欢喝咖啡", scope = "main", spaceId = "default", limit = 5).any { it.id == old },
        )

        // 用户反悔:删掉新记忆,旧记忆自动回到上下文
        store.delete(fresh)
        assertTrue(
            "指向方删除后旧记忆应回到召回",
            store.searchRelevantFacts("咖啡", limit = 8).any { it.id == old },
        )
        assertTrue(FactsSectionProjector.project(store.getByScopeAndSpace("main", "default")).contains(oldText))
    }

    /** v18: 用户改主意又改回来 —— 最新一条要取代中间那条,不能留着两条互斥断言。 */
    @Test
    fun `flip flopping back supersedes the intervening fact`() = runTest {
        store.add(FactStore.Fact(fact = "张三喜欢喝咖啡", source = "user_explicit", entityKey = "张三"))
        val dislike = store.add(FactStore.Fact(fact = "张三讨厌喝咖啡", source = "user_explicit", entityKey = "张三"))
        val likeAgain = store.add(FactStore.Fact(fact = "张三爱喝咖啡", source = "user_explicit", entityKey = "张三"))

        assertEquals("最新表态应取代中间那条", dislike, store.getById(likeAgain)!!.supersedesId)
        assertEquals(
            "上下文只留最新表态",
            store.getById(likeAgain)!!.fact,
            FactsSectionProjector.project(store.getByScopeAndSpace("main", "default")),
        )
        assertEquals("三条都还在库里(可查、可反悔)", 3, store.getByScopeAndSpace("main", "default").size)
    }

    /** v18: 同一实体但不矛盾的记忆不能被误标取代。 */
    @Test
    fun `same entity without contradiction is not marked as superseding`() = runTest {
        val coffee = store.add(FactStore.Fact(fact = "张三喜欢喝咖啡", source = "user_explicit", entityKey = "张三"))
        val city = store.add(FactStore.Fact(fact = "张三住在成都", source = "user_explicit", entityKey = "张三"))

        assertNotEquals(coffee, city)
        assertNull("不矛盾的记忆不应记 supersedes", store.getById(city)!!.supersedesId)
        assertEquals(
            "两条都应留在上下文",
            2,
            FactsSectionProjector.project(store.getByScopeAndSpace("main", "default")).lines().size,
        )
    }

    /** v18: 已仲裁的一对不再进“待确认”清单,未仲裁的照旧上报。 */
    @Test
    fun `pending contradiction list reflects arbitration`() = runTest {
        // 推断来的相反断言不参与仲裁(source 不是 user_explicit),仍应上报
        store.add(FactStore.Fact(fact = "张三喜欢喝咖啡", source = "user_explicit", entityKey = "张三"))
        store.add(FactStore.Fact(fact = "张三讨厌喝咖啡", source = "inferred", entityKey = "张三"))
        assertEquals("未仲裁的矛盾仍应上报", 1, store.detectContradictions("main", "default").size)
    }

    /** v18: 写入时就仲裁掉的一对,不再要求用户确认。 */
    @Test
    fun `arbitrated pair leaves the pending contradiction list`() = runTest {
        store.add(FactStore.Fact(fact = "张三喜欢喝咖啡", source = "user_explicit", entityKey = "张三"))
        store.add(FactStore.Fact(fact = "张三讨厌喝咖啡", source = "user_explicit", entityKey = "张三"))

        assertTrue(
            "已仲裁的一对不应再要求用户确认",
            store.detectContradictions("main", "default").isEmpty(),
        )
    }

    /** v18: 旧状态本身有歧义(两条都矛盾)时只标记不剔除 —— 谁被取代交给人判断。 */
    @Test
    fun `ambiguous old state is left alone instead of guessing`() = runTest {
        store.add(FactStore.Fact(fact = "张三喜欢喝咖啡", source = "user_explicit", entityKey = "张三"))
        store.add(FactStore.Fact(fact = "张三爱喝拿铁", source = "user_explicit", entityKey = "张三"))
        val fresh = store.add(FactStore.Fact(fact = "张三讨厌喝咖啡", source = "user_explicit", entityKey = "张三"))

        assertNull("旧记忆不止一条矛盾时不应武断指定取代对象", store.getById(fresh)!!.supersedesId)
        assertEquals(
            "三条都留在上下文",
            3,
            FactsSectionProjector.project(store.getByScopeAndSpace("main", "default")).lines().size,
        )
    }

    /** v18: 非用户明确陈述(推断/导入)不足以推翻已有事实。 */
    @Test
    fun `inferred statement never supersedes an explicit fact`() = runTest {
        val old = store.add(FactStore.Fact(fact = "张三喜欢喝咖啡", source = "user_explicit", entityKey = "张三"))
        val inferred = store.add(FactStore.Fact(fact = "张三讨厌喝咖啡", source = "inferred", entityKey = "张三"))

        assertNotEquals(old, inferred)
        assertNull("推断出来的相反断言不取代明确陈述", store.getById(inferred)!!.supersedesId)
    }
}
