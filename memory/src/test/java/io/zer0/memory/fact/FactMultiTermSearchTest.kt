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
 * 多词全文检索：两段式（精确优先 + 任一命中兜底）+ LIKE 拆词兜底。
 *
 * 覆盖：分散在多行事实里的关键词能一起被找回、精确命中足够时不走放宽轮次、
 * 整串 LIKE 不命中时按词命中、OR 语义确实能捞回 AND 漏掉的行、查询串不含单字 token。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FactMultiTermSearchTest {

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

    private suspend fun addFact(text: String, importance: Int = 0): Long =
        store.add(FactStore.Fact(fact = text, importance = importance), "main", "default")

    @Test
    fun `scattered keywords are all found in one two stage search`() = runTest {
        val zhiqi = addFact("子奇喜欢在周末进山徒步")
        val developer = addFact("开发者把这个项目当成长期爱好")
        val assistant = addFact("助手习惯在深夜回复消息")

        val hits = store.searchFullTextScopedTwoStage("子奇 开发者 助手", "main", "default", limit = 10)

        assertEquals("三个词分散在三行,都应被找回: ${hits.map { it.fact }}", 3, hits.size)
        assertEquals(setOf(zhiqi, developer, assistant), hits.map { it.id }.toSet())
    }

    @Test
    fun `whole string like misses while per word like hits`() = runTest {
        addFact("子奇喜欢在周末进山徒步")
        addFact("开发者把这个项目当成长期爱好")

        // EXACT 段先走 FTS(AND) 再走整串 LIKE,两者都落空时按词逐个 LIKE
        val hits = store.searchFullTextScoped("子奇 开发者", "main", "default", limit = 10)

        assertEquals("整串不命中,拆词后应各命中一条: ${hits.map { it.fact }}", 2, hits.size)
    }

    @Test
    fun `single word query returns the same hits as before`() = runTest {
        val target = addFact("子奇喜欢在周末进山徒步")
        addFact("开发者把这个项目当成长期爱好")

        val exact = store.searchFullTextScoped("子奇", "main", "default", limit = 10)
        val twoStage = store.searchFullTextScopedTwoStage("子奇", "main", "default", limit = 10)

        assertEquals(listOf(target), exact.map { it.id })
        assertEquals(exact.map { it.id }, twoStage.map { it.id })
    }

    @Test
    fun `or semantics recalls rows that and semantics drops`() = runTest {
        addFact("子奇喜欢在周末进山徒步")
        addFact("开发者把这个项目当成长期爱好")

        val andHits = dao.searchFtsBySpace(FactFtsManager.toMatchQuery("子奇 开发者"), 10, "main", "default")
        val orHits = dao.searchFtsBySpace(
            FactFtsManager.toMatchQuery("子奇 开发者", orSemantics = true),
            10,
            "main",
            "default",
        )

        assertEquals("同一行里没有两个词时 AND 应零命中", 0, andHits.size)
        assertEquals("OR 应把两行都捞回来", 2, orHits.size)
    }

    @Test
    fun `exact hits stop the search before the broad stage`() = runTest {
        val allInOne = addFact("子奇 开发者 助手 三人一起吃了顿饭")
        val onlyOne = addFact("助手凌晨三点还在回消息", importance = 2)

        val limited = store.searchFullTextScopedTwoStage("子奇 开发者 助手", "main", "default", limit = 1)

        // 放宽轮次若被执行,importance=2 的那条会排在前面,结果就不是 allInOne
        assertEquals("精确命中已凑满 limit,不该再走放宽轮次", listOf(allInOne), limited.map { it.id })

        val widened = store.searchFullTextScopedTwoStage("子奇 开发者 助手", "main", "default", limit = 3)
        assertEquals("精确命中在前,放宽结果补在后面", allInOne, widened.first().id)
        assertTrue("两条都应被返回: ${widened.map { it.id }}", widened.map { it.id }.contains(onlyOne))
    }

    @Test
    fun `match query drops the trailing single character token`() {
        val query = FactFtsManager.toMatchQuery("子奇 开发者 助手")
        val tokens = query.split(' ').map { it.trim('"') }.filter { it.isNotEmpty() }

        assertTrue("查询串里不该有单字 token: $query", tokens.all { it.length >= 2 })
        assertTrue("多词默认仍是 AND 连接: $query", !query.contains(" OR "))

        val broad = FactFtsManager.toMatchQuery("子奇 开发者 助手", orSemantics = true)
        assertTrue("放宽语义应输出 OR 连接: $broad", broad.contains(" OR "))
    }

    @Test
    fun `index ngram still carries the trailing single character`() {
        // 索引侧行为不变:末位单字仍在,否则奇数长度的片段末尾字符会搜不到
        assertEquals("你好 好世 世界 界", FactFtsManager.toNgram("你好世界"))
        assertEquals("你好 好世 世界", FactFtsManager.toNgram("你好世界", includeTrailingTailChar = false))
    }

    @Test
    fun `query terms split on whitespace and punctuation`() {
        assertEquals(listOf("子奇", "开发者", "助手"), FactFtsManager.toQueryTerms("子奇，开发者;助手"))
        assertEquals(listOf("GPT", "4"), FactFtsManager.toQueryTerms("GPT-4"))
    }
}
