package io.zer0.memory.fact

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.memory.ticker.MemoryConfig
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
    //  applyDecay: 衰减与永不衰减
    // ──────────────────────────────────────────────

    @Test
    fun `applyDecay preserves critical facts with importance 2`() = runTest {
        // 直接通过 DAO 插入一条 100 天前的关键事实
        val oldDate = Instant.now().minus(100, ChronoUnit.DAYS).toString()
        dao.insert(FactEntity(
            fact = "青霉素过敏(关键)",
            tags = "[]",
            createdAt = oldDate,
            importance = 2, // 关键,永不衰减
            lastHitAt = null,
        ))
        store.rebuildFtsIndex()

        val deleted = store.applyDecay(MemoryConfig())
        assertEquals("不应删除关键事实", 0, deleted)
        assertEquals("关键事实应仍存在", 1, store.size())
    }

    @Test
    fun `applyDecay deletes old non-critical facts beyond cutoff`() = runTest {
        // 默认 cutoffDays ≈ 40 天,插入 50 天前的普通事实
        val oldDate = Instant.now().minus(50, ChronoUnit.DAYS).toString()
        dao.insert(FactEntity(
            fact = "50天前的普通事实",
            tags = "[]",
            createdAt = oldDate,
            importance = 0,
            lastHitAt = null,
        ))
        store.rebuildFtsIndex()

        val deleted = store.applyDecay(MemoryConfig())
        assertTrue("应至少删除 1 条旧普通事实", deleted >= 1)
        assertEquals(0, store.size())
    }

    @Test
    fun `applyDecay keeps recent facts within cutoff`() = runTest {
        // 插入 5 天前的普通事实(远小于 40 天 cutoff)
        val recentDate = Instant.now().minus(5, ChronoUnit.DAYS).toString()
        dao.insert(FactEntity(
            fact = "近期普通事实",
            tags = "[]",
            createdAt = recentDate,
            importance = 0,
            lastHitAt = null,
        ))
        store.rebuildFtsIndex()

        val deleted = store.applyDecay(MemoryConfig())
        assertEquals("近期事实不应被衰减", 0, deleted)
        assertEquals(1, store.size())
    }

    @Test
    fun `applyDecay uses hitBonus to extend retention for hit facts`() = runTest {
        // 50天前的普通事实,但 lastHitAt 设为 5 天前(命中过)
        // 默认 hitBonus=5 → cutoff ≈ 60 天,50 < 60 应保留
        val oldCreated = Instant.now().minus(50, ChronoUnit.DAYS).toString()
        val recentHit = Instant.now().minus(5, ChronoUnit.DAYS).toString()
        dao.insert(FactEntity(
            fact = "50天前创建但5天前命中",
            tags = "[]",
            createdAt = oldCreated,
            importance = 0,
            lastHitAt = recentHit, // 已命中,按 lastHitAt 判断
        ))
        store.rebuildFtsIndex()

        val deleted = store.applyDecay(MemoryConfig())
        assertEquals("命中过的事实应享 hitBonus 延长保留", 0, deleted)
        assertEquals(1, store.size())
    }

    @Test
    fun `applyDecay with scope only affects specified scope`() = runTest {
        val oldDate = Instant.now().minus(50, ChronoUnit.DAYS).toString()

        // main scope: 旧普通事实
        dao.insert(FactEntity(
            fact = "main scope 旧事实",
            tags = "[]",
            createdAt = oldDate,
            importance = 0,
            lastHitAt = null,
            scope = "main",
        ))
        // assistant-A scope: 同样旧的普通事实
        dao.insert(FactEntity(
            fact = "assistant-A scope 旧事实",
            tags = "[]",
            createdAt = oldDate,
            importance = 0,
            lastHitAt = null,
            scope = "assistant-A",
        ))
        store.rebuildFtsIndex()

        // 仅衰减 main scope
        val deleted = store.applyDecay(MemoryConfig(), scope = "main")
        assertEquals("应仅删除 main scope 的 1 条", 1, deleted)
        assertEquals("assistant-A scope 应仍存在", 1, store.getByScope("assistant-A").size)
        assertEquals("main scope 应为空", 0, store.getByScope("main").size)
    }

    @Test
    fun `applyDecay with scope and space only affects specified space`() = runTest {
        val oldDate = Instant.now().minus(50, ChronoUnit.DAYS).toString()
        dao.insert(FactEntity(fact = "work old", tags = "[]", createdAt = oldDate, scope = "main", spaceId = "work"))
        dao.insert(FactEntity(fact = "life old", tags = "[]", createdAt = oldDate, scope = "main", spaceId = "life"))
        store.rebuildFtsIndex()

        val deleted = store.applyDecay(MemoryConfig(), scope = "main", spaceId = "work")

        assertEquals(1, deleted)
        assertEquals(0, store.getByScopeAndSpace("main", "work").size)
        assertEquals(1, store.getByScopeAndSpace("main", "life").size)
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

        assertTrue(store.setImportance(id, 5))  // 超界应 clamp 到 2
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
    fun `filterTombstonedLines drops tombstoned lines and keeps others`() {
        val text = "用户对青霉素过敏\n用户喜欢跑步\n用户住在北京"
        val filtered = FactStore.filterTombstonedLines(text, listOf("青霉素过敏"))
        assertEquals("用户喜欢跑步\n用户住在北京", filtered)
    }
}
