package io.zer0.memory.fact

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v12: 实体归一化键(EntityKey)去重测试。
 *
 * 目标场景: 用户反馈"同一用户名出现 3 条重复记忆"(如"张三"/"张先生"/"张三老师")。
 * 修复后同一 entity_key 下文本实质相同的事实应合并为一条,而不是新增三条。
 *
 * 覆盖:
 *  - 同 entityKey 下不同写法的同事实 → 只保留 1 条
 *  - 同 entityKey 下不同事实(如"喜欢咖啡" vs "讨厌咖啡")→ 不误合并
 *  - 不同 spaceId 下同 entityKey 同文本 → 不跨空间合并
 *  - update 后与已有事实重复 → 触发合并(更新路径接入去重)
 *  - 归一化: 全半角/大小写/空白差异 → 判为同一事实
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EntityKeyDedupTest {

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
    fun `same entity key different wordings merge into one fact`() = runTest {
        // 用户反馈场景: 同一实体 3 种写法,entity_key 均为"张三"
        val id1 = store.add(FactStore.Fact(fact = "张三喜欢摄影", entityKey = "张三"))
        val id2 = store.add(FactStore.Fact(fact = "张先生喜欢摄影", entityKey = "张三"))
        val id3 = store.add(FactStore.Fact(fact = "张三老师喜欢摄影", entityKey = "张三"))

        // 三条应合并为一条(后两条合并进第一条,返回同一 id)
        assertEquals("第二次写入应合并返回已有 id", id1, id2)
        assertEquals("第三次写入应合并返回已有 id", id1, id3)

        val all = store.getByScopeAndSpace("main", "default")
        assertEquals("同实体同事实应只剩 1 条", 1, all.size)
        assertEquals("保留完整表述", "张三老师喜欢摄影", all[0].fact)
        assertEquals("实体键应保留", "张三", all[0].entityKey)
    }

    @Test
    fun `same entity key different facts do not merge`() = runTest {
        val id1 = store.add(FactStore.Fact(fact = "张三喜欢喝咖啡", entityKey = "张三"))
        val id2 = store.add(FactStore.Fact(fact = "张三讨厌香菜", entityKey = "张三"))

        assertTrue("不同事实 id 应不同", id1 != id2)
        val all = store.getByScopeAndSpace("main", "default")
        assertEquals("同实体不同事实应保留 2 条", 2, all.size)
    }

    @Test
    fun `same entity key across spaces stays isolated`() = runTest {
        val work = store.add(FactStore.Fact(fact = "张三喜欢美式咖啡", entityKey = "张三"), spaceId = "work")
        val life = store.add(FactStore.Fact(fact = "张三喜欢美式咖啡", entityKey = "张三"), spaceId = "life")

        assertTrue("跨空间不合并,id 应不同", work != life)
        assertEquals("work 空间 1 条", 1, store.getByScopeAndSpace("main", "work").size)
        assertEquals("life 空间 1 条", 1, store.getByScopeAndSpace("main", "life").size)
    }

    @Test
    fun `update triggering dedup merges into existing fact`() = runTest {
        val id1 = store.add(FactStore.Fact(fact = "李四在学游泳", entityKey = "李四"))
        val id2 = store.add(FactStore.Fact(fact = "李四在学吉他", entityKey = "李四"))

        // 把 id2 改成与 id1 实质相同的内容 → 应合并(删除 id2,保留 id1)
        store.update(id2, "李四在学游泳", "main")

        val all = store.getByScopeAndSpace("main", "default")
        assertEquals("update 触发去重后应只剩 1 条", 1, all.size)
        assertNull("被合并的 id2 应已删除", store.getById(id2))
        assertNotNull("合并后 id1 仍存在", store.getById(id1))
    }

    @Test
    fun `normalization treats full-width case and space differences as identical`() = runTest {
        val id1 = store.add(FactStore.Fact(fact = "Zhang San likes coffee", entityKey = "zhang san"))
        // 全角字母 + 多余空格 + 大写差异,归一化后应与 id1 判为同一事实
        val id2 = store.add(FactStore.Fact(fact = "ｚｈａｎｇ　ｓａｎ  likes coffee", entityKey = "zhang san"))

        assertEquals("归一化后应合并返回同一 id", id1, id2)
        assertEquals(1, store.getByScopeAndSpace("main", "default").size)
    }

    @Test
    fun `explicit entity key wins over inferred`() = runTest {
        // 显式 entityKey 与文本推断不同时,以显式为准
        val id = store.add(FactStore.Fact(fact = "王五喜欢跑步", entityKey = "wang-wu"))
        val retrieved = store.getById(id)
        assertNotNull(retrieved)
        assertEquals("显式 entityKey 优先", "wang-wu", retrieved!!.entityKey)
    }

    @Test
    fun `inferred entity key from name placeholder`() = runTest {
        // PiiGuard 脱敏后的人名占位符应作为实体键
        val id = store.add(FactStore.Fact(fact = "{{name_0}} 喜欢摄影"))
        val retrieved = store.getById(id)
        assertNotNull(retrieved)
        assertEquals("脱敏占位符作为实体键", "{{name_0}}", retrieved!!.entityKey)
    }
}
