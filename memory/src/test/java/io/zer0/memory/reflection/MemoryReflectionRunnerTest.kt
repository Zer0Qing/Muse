package io.zer0.memory.reflection

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.memory.fact.FactDb
import io.zer0.memory.fact.FactStore
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v12 (T3-1/3-2/3-3): 记忆反思任务测试。
 *  - 回填历史实体键(entity_key 为 null 的存量数据)
 *  - 合并同实体重复(存量"同名 3 条"清洗)
 *  - 矛盾检测(同实体相反断言)
 *  - 重复确认晋升(同实体多条事实 → 重要度提升)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MemoryReflectionRunnerTest {

    private lateinit var db: FactDb
    private lateinit var store: FactStore
    private lateinit var runner: MemoryReflectionRunner

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, FactDb::class.java)
            .allowMainThreadQueries()
            .build()
        store = FactStore(db.factDao(), db)
        runner = MemoryReflectionRunner(store)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `reflection backfills entity keys and merges duplicates`() = runTest {
        // 模拟 v11 存量数据(entityKey=null): mask 占位符保留实体区分度,可安全回填
        val now = java.time.Instant.now().toString()
        db.factDao().insert(io.zer0.memory.fact.FactEntity(fact = "[NAME_1]喜欢摄影", createdAt = now))
        db.factDao().insert(io.zer0.memory.fact.FactEntity(fact = "张三老师喜欢摄影", createdAt = now))

        val result = runner.runReflection()

        assertTrue("占位符数据应回填实体键", result.backfilled >= 1)
        // 有键的两条占位符同事实应合并(回填后 entityKey 一致 → 合并)
        store.add(FactStore.Fact(fact = "[NAME_1]喜欢摄影", entityKey = "[NAME_1]"))
        val all = store.getByScopeAndSpace("main", "default")
        // [NAME_1] 两条(存量+新写入)合并为 1,中文名保守保留
        val name1Count = all.count { it.fact.contains("NAME_1") }
        assertEquals("占位符同实体重复应合并为 1 条", 1, name1Count)
    }

    @Test
    fun `reflection merges same entity duplicates in legacy data`() = runTest {
        // 直接插入带 entityKey 的存量重复(模拟已回填的历史数据)
        val now = java.time.Instant.now().toString()
        db.factDao().insert(io.zer0.memory.fact.FactEntity(fact = "[NAME_1]喜欢摄影", createdAt = now, entityKey = "[NAME_1]"))
        db.factDao().insert(io.zer0.memory.fact.FactEntity(fact = "张三老师喜欢摄影", createdAt = now, entityKey = "[NAME_1]"))

        val result = runner.runReflection()

        assertEquals("应合并 1 条重复", 1, result.merged)
        assertEquals("合并后只剩 1 条", 1, store.getByScopeAndSpace("main", "default").size)
    }

    @Test
    fun `reflection leaves unbackfillable chinese names untouched`() = runTest {
        val now = java.time.Instant.now().toString()
        db.factDao().insert(io.zer0.memory.fact.FactEntity(fact = "张三喜欢摄影", createdAt = now))
        db.factDao().insert(io.zer0.memory.fact.FactEntity(fact = "张三老师喜欢摄影", createdAt = now))

        runner.runReflection()

        // 中文名无 LLM 时保守不回填、不误合并(宁漏不错)
        assertEquals("中文名存量保守保留", 2, store.getByScopeAndSpace("main", "default").size)
    }

    @Test
    fun `reflection detects contradictions without deleting`() = runTest {
        store.add(FactStore.Fact(fact = "张三喜欢喝咖啡", entityKey = "张三"))
        store.add(FactStore.Fact(fact = "张三讨厌喝咖啡", entityKey = "张三"))

        val contradictions = store.detectContradictions("main", "default")

        assertEquals("应检测到 1 对矛盾", 1, contradictions.size)
        // 矛盾不自动删除
        assertEquals("矛盾事实仍保留", 2, store.getByScopeAndSpace("main", "default").size)
    }

    @Test
    fun `reflection promotes repeated entity facts`() = runTest {
        // 同一实体 3 条不同事实 → 重复确认,重要度提升
        store.add(FactStore.Fact(fact = "李四在学游泳", entityKey = "李四"))
        store.add(FactStore.Fact(fact = "李四在学吉他", entityKey = "李四"))
        store.add(FactStore.Fact(fact = "李四喜欢跑步", entityKey = "李四"))

        val promoted = store.promoteRepeatedFacts("main", "default", minConfirmations = 2)

        assertTrue("应晋升至少 1 条", promoted >= 1)
        val all = store.getByScopeAndSpace("main", "default")
        assertTrue("晋升后存在 importance ≥ 1 的事实", all.any { it.importance >= 1 })
    }

    @Test
    fun `reflection does not merge different facts of same entity`() = runTest {
        store.add(FactStore.Fact(fact = "张三喜欢喝咖啡", entityKey = "张三"))
        store.add(FactStore.Fact(fact = "张三喜欢跑步", entityKey = "张三"))

        runner.runReflection()

        assertEquals("不同事实不合并", 2, store.getByScopeAndSpace("main", "default").size)
    }

    @Test
    fun `single entity fact does not promote`() = runTest {
        store.add(FactStore.Fact(fact = "王五喜欢摄影", entityKey = "王五"))

        val promoted = store.promoteRepeatedFacts("main", "default", minConfirmations = 2)
        assertEquals("单条事实不晋升", 0, promoted)
    }
}
