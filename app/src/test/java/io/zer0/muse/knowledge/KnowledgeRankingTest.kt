package io.zer0.muse.knowledge

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.data.knowledge.IndexGenerationState
import io.zer0.muse.data.knowledge.KnowledgeGenerationEntity
import io.zer0.muse.data.knowledge.KnowledgeGenChunkEntity
import io.zer0.muse.data.knowledge.KnowledgeGenLexicalEntity
import io.zer0.muse.data.knowledge.KnowledgeJobDao
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.knowledge.domain.KnowledgeScope
import io.zer0.muse.knowledge.domain.RetrievalPolicy
import io.zer0.muse.knowledge.retrieval.KnowledgeEntryType
import io.zer0.muse.knowledge.retrieval.KnowledgeSearchRequest
import io.zer0.muse.knowledge.retrieval.V2LexicalRetriever
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R6 步骤 2(计划 R6:「先写中文、英文、数字/代码…用例」「FTS4 用真实 MATCH/matchinfo 取候选
 * 并在兼容排名器打分；索引和查询规范化版本一致」)。
 *
 * 本测试用真实 in-memory Room 驱动 V2LexicalRetriever,覆盖中英/数字/代码四类查询
 * 与候选预算截断标记;真实 FTS4 vtable 能力见 LexicalSqliteIntegrationTest(设备待验)。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class KnowledgeRankingTest {

    private lateinit var db: MuseDb
    private lateinit var dao: KnowledgeJobDao
    private lateinit var retriever: V2LexicalRetriever

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, MuseDb::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.knowledgeJobDao()
        retriever = V2LexicalRetriever(dao)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun seedGeneration(generationId: String, docId: String, rows: List<Pair<String, String>>) {
        dao.insertGeneration(
            KnowledgeGenerationEntity(
                generationId = generationId,
                docId = docId,
                generationNo = 1,
                state = IndexGenerationState.ACTIVE.name,
                revisionId = "rev-$docId",
                chunkCount = rows.size,
            ),
        )
        dao.insertGenChunks(
            rows.mapIndexed { index, pair ->
                KnowledgeGenChunkEntity(
                    id = "$generationId#$index",
                    generationId = generationId,
                    chunkIndex = index,
                    content = pair.first,
                    locator = "p1s${index + 1}",
                    kind = "paragraph",
                )
            },
        )
        dao.insertGenLexical(
            rows.mapIndexed { index, pair ->
                KnowledgeGenLexicalEntity(
                    id = "$generationId#$index",
                    generationId = generationId,
                    chunkIndex = index,
                    lexicalText = pair.second,
                )
            },
        )
    }

    private fun request(query: String, topK: Int = 5) = KnowledgeSearchRequest(
        requestId = "req-ranking",
        query = query,
        entry = KnowledgeEntryType.DEBUG,
        scope = KnowledgeScope.Resolved(setOf("doc-1")),
        policy = RetrievalPolicy(
            topK = topK,
            threshold = 0.35f,
            mmrLambda = 0.5f,
            hybridEnabled = false,
            hybridBm25Weight = 1f,
            hybridVectorWeight = 1f,
            rerankEnabled = false,
            rerankProvider = "",
            rerankModel = "",
            tokenBudget = 0,
        ),
        datasetEpoch = 1L,
        deadlineMs = 5000L,
    )

    @Test
    fun `chinese query matches normalized lexical text`() = runBlocking {
        seedGeneration(
            "gen-1",
            "doc-1",
            listOf(
                "知识库检索使用倒排索引" to "知识库检索使用倒排索引",
                "无关内容" to "无关内容",
            ),
        )
        val recall = retriever.search(request("检索"), listOf("gen-1"), 5)
        assertEquals(1, recall.hits.size)
        assertEquals("知识库检索使用倒排索引", recall.hits[0].text)
    }

    @Test
    fun `english query is case insensitive`() = runBlocking {
        seedGeneration(
            "gen-1",
            "doc-1",
            listOf("Knowledge Base Retrieval" to "knowledge base retrieval"),
        )
        val recall = retriever.search(request("KNOWLEDGE"), listOf("gen-1"), 5)
        assertEquals(1, recall.hits.size)
    }

    @Test
    fun `numeric identifier matches exactly`() = runBlocking {
        seedGeneration(
            "gen-1",
            "doc-1",
            listOf("订单号 20261111001 已发货" to "订单号 20261111001 已发货"),
        )
        val recall = retriever.search(request("20261111001"), listOf("gen-1"), 5)
        assertEquals(1, recall.hits.size)
    }

    @Test
    fun `code identifier matches`() = runBlocking {
        seedGeneration(
            "gen-1",
            "doc-1",
            listOf("调用 KnowledgeRetriever.search 方法" to "调用 knowledgeretriever.search 方法"),
        )
        val recall = retriever.search(request("KnowledgeRetriever.search"), listOf("gen-1"), 5)
        assertEquals(1, recall.hits.size)
    }

    @Test
    fun `out of scope generation is not searched`() = runBlocking {
        seedGeneration("gen-1", "doc-1", listOf("alpha content" to "alpha content"))
        seedGeneration("gen-2", "doc-2", listOf("alpha content" to "alpha content"))
        val recall = retriever.search(request("alpha"), listOf("gen-1"), 5)
        assertEquals("只搜活动代列表内的代", 1, recall.hits.size)
        assertEquals("gen-1", recall.hits[0].generationId)
    }

    @Test
    fun `candidate budget truncation is marked incomplete`() = runBlocking {
        val rows = (1..20).map { "alpha row $it" to "alpha row $it" }
        seedGeneration("gen-1", "doc-1", rows)
        val recall = retriever.search(request("alpha", topK = 1), listOf("gen-1"), 1)
        // oversample=4 -> 预算 4;20 行超过预算 -> incomplete
        assertTrue("达到候选预算必须标记不完整", recall.incomplete)
        assertEquals(4, recall.hits.size)
    }

    @Test
    fun `match beyond candidate budget is still found`() = runBlocking {
        // 回归:旧实现先取前 N 行再内存过滤,排在后段的命中会丢;现在 SQL 侧先过滤。
        val rows = (1..50).map { "filler row $it" to "filler row $it" } +
            ("needle-only row" to "needle-only row")
        seedGeneration("gen-1", "doc-1", rows)
        val recall = retriever.search(request("needle", topK = 1), listOf("gen-1"), 1)
        assertEquals("排在候选预算之后的命中必须可召回", 1, recall.hits.size)
        assertTrue(recall.hits[0].text.contains("needle"))
    }

    @Test
    fun `ranking prefers more term hits`() = runBlocking {
        seedGeneration(
            "gen-1",
            "doc-1",
            listOf(
                "alpha beta gamma delta" to "alpha beta gamma delta",
                "alpha only" to "alpha only",
            ),
        )
        val recall = retriever.search(request("alpha beta gamma"), listOf("gen-1"), 5)
        assertEquals(2, recall.hits.size)
        assertEquals("命中词更多的排前", "alpha beta gamma delta", recall.hits[0].text)
    }
}
