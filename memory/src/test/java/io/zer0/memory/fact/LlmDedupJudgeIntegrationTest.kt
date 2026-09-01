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

/**
 * v12: LLM 去重判定层测试。
 *
 * 核心场景: 算法层(字符相似度/实体键)无法确定的模糊候选对,交给大模型判断。
 *  - 语义等价但表述差异大的两条(算法层 miss)→ judge 判定 same → 合并
 *  - 同实体下不同事实 → judge 判定 not same → 不合并
 *  - judge 不可用/低置信 → 宁可不合并,不阻塞写入
 *  - 同一事实对只问一次 judge(缓存)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LlmDedupJudgeIntegrationTest {

    private lateinit var db: FactDb
    private lateinit var dao: FactDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, FactDb::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.factDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** 可控 fake judge — 记录调用次数,按预设返回。 */
    private class FakeJudge(
        private val verdict: DedupVerdict,
    ) : FactDedupJudge {
        var calls = 0
        var lastA: String? = null
        var lastB: String? = null

        override suspend fun judge(
            a: String,
            b: String,
            entityKeyA: String?,
            entityKeyB: String?,
        ): DedupVerdict {
            calls++
            lastA = a
            lastB = b
            return verdict
        }
    }

    @Test
    fun `algorithm miss but judge says same merges`() = runTest {
        val judge = FakeJudge(DedupVerdict(same = true, confidence = 0.95f, reason = "语义等价"))
        val store = FactStore(dao, db, dedupJudge = judge)

        val id1 = store.add(FactStore.Fact(fact = "用户养了一只柯基", entityKey = "用户"))
        // 算法层: bigram 相似度低判不相似;LLM 判定同一
        val id2 = store.add(FactStore.Fact(fact = "他养了只柯基犬", entityKey = "用户"))

        assertEquals("LLM 判定同一后应合并", id1, id2)
        assertTrue("judge 应被调用", judge.calls >= 1)
        val all = store.getByScopeAndSpace("main", "default")
        assertEquals("应只剩 1 条", 1, all.size)
    }

    @Test
    fun `judge says not same keeps both`() = runTest {
        val judge = FakeJudge(DedupVerdict(same = false, confidence = 0.9f, reason = "不同事实"))
        val store = FactStore(dao, db, dedupJudge = judge)

        store.add(FactStore.Fact(fact = "张三喜欢喝咖啡", entityKey = "张三"))
        val id2 = store.add(FactStore.Fact(fact = "张三讨厌香菜", entityKey = "张三"))

        assertTrue("不同事实不应合并", id2 > 1)
        assertEquals(2, store.getByScopeAndSpace("main", "default").size)
    }

    @Test
    fun `low confidence same does not merge`() = runTest {
        val judge = FakeJudge(DedupVerdict(same = true, confidence = 0.3f, reason = "不太确定"))
        val store = FactStore(dao, db, dedupJudge = judge)

        store.add(FactStore.Fact(fact = "用户喜欢早起", entityKey = "用户"))
        val id2 = store.add(FactStore.Fact(fact = "他习惯五点起床", entityKey = "用户"))

        assertTrue("低置信不应合并", id2 > 1)
        assertEquals(2, store.getByScopeAndSpace("main", "default").size)
    }

    @Test
    fun `judge exception degrades to not same without blocking`() = runTest {
        val judge = object : FactDedupJudge {
            override suspend fun judge(
                a: String,
                b: String,
                entityKeyA: String?,
                entityKeyB: String?,
            ): DedupVerdict = throw RuntimeException("LLM timeout")
        }
        val store = FactStore(dao, db, dedupJudge = judge)

        store.add(FactStore.Fact(fact = "用户喜欢早起", entityKey = "用户"))
        val id2 = store.add(FactStore.Fact(fact = "他习惯五点起床", entityKey = "用户"))

        assertTrue("judge 异常应降级不合并", id2 > 1)
        assertEquals(2, store.getByScopeAndSpace("main", "default").size)
    }

    @Test
    fun `same pair judged only once via cache`() = runTest {
        val judge = FakeJudge(DedupVerdict(same = false, confidence = 0.9f, reason = "不同"))
        val store = FactStore(dao, db, dedupJudge = judge)

        // 同 entityKey 下两次写入同文本,触发 judge 后第二次走缓存
        store.add(FactStore.Fact(fact = "李四喜欢跑步", entityKey = "李四"))
        store.add(FactStore.Fact(fact = "李四喜欢游泳", entityKey = "李四"))
        store.add(FactStore.Fact(fact = "李四喜欢游泳", entityKey = "李四"))

        assertTrue("同一事实对最多问一次", judge.calls <= 2)
    }

    @Test
    fun `noop judge never calls llm and behaves like before`() = runTest {
        val store = FactStore(dao, db) // 默认 NoopFactDedupJudge
        store.add(FactStore.Fact(fact = "张三喜欢摄影", entityKey = "张三"))
        val id2 = store.add(FactStore.Fact(fact = "张三喜欢摄影", entityKey = "张三"))
        assertEquals("Noop 下精确重复仍由算法层合并", 1L, id2)
        assertEquals(1, store.getByScopeAndSpace("main", "default").size)
    }
}
