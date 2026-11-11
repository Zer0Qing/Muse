package io.zer0.muse.knowledge

import io.zer0.muse.knowledge.domain.KnowledgeScope
import io.zer0.muse.knowledge.domain.RetrievalPolicy
import io.zer0.muse.knowledge.retrieval.DenseHit
import io.zer0.muse.knowledge.retrieval.DenseRecall
import io.zer0.muse.knowledge.retrieval.DenseRetriever
import io.zer0.muse.knowledge.retrieval.EvidenceCandidate
import io.zer0.muse.knowledge.retrieval.KnowledgeEntryType
import io.zer0.muse.knowledge.retrieval.KnowledgeRetriever
import io.zer0.muse.knowledge.retrieval.KnowledgeSearchRequest
import io.zer0.muse.knowledge.retrieval.LexicalHit
import io.zer0.muse.knowledge.retrieval.LexicalRecall
import io.zer0.muse.knowledge.retrieval.LexicalRetriever
import io.zer0.muse.knowledge.retrieval.RerankProvider
import io.zer0.muse.knowledge.retrieval.RetrievalOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R6 步骤 1/3/5/7(计划 R6:「先写中文、英文、数字/代码、全局匹配很强但不在范围、
 * 词法独占命中、未索引和内部文档挤占配额等用例」「范围先于候选」「错误与零命中分开」)。
 *
 * 用可注入的假后端驱动合同,断言类型化结果的语义边界;真实 SQLite 排名见
 * KnowledgeRankingTest 与 LexicalSqliteIntegrationTest。
 */
class KnowledgeRetrieverContractTest {

    private fun policy(topK: Int = 3, hybrid: Boolean = false, rerank: Boolean = false) = RetrievalPolicy(
        topK = topK,
        threshold = 0.35f,
        mmrLambda = 0.5f,
        hybridEnabled = hybrid,
        hybridBm25Weight = 1f,
        hybridVectorWeight = 1f,
        rerankEnabled = rerank,
        rerankProvider = "",
        rerankModel = "",
        tokenBudget = 0,
    )

    private fun request(
        scope: KnowledgeScope = KnowledgeScope.Resolved(setOf("doc-1")),
        policy: RetrievalPolicy = policy(),
    ) = KnowledgeSearchRequest(
        requestId = "req-1",
        query = "alpha",
        entry = KnowledgeEntryType.CHAT,
        scope = scope,
        policy = policy,
        datasetEpoch = 1L,
        deadlineMs = 5000L,
    )

    private class FakeLexical(
        private val hits: List<LexicalHit> = emptyList(),
        private val degraded: String? = null,
        private val throwError: Boolean = false,
    ) : LexicalRetriever {
        var lastGenerations: List<String> = emptyList()
        override suspend fun search(
            request: KnowledgeSearchRequest,
            activeGenerationIds: List<String>,
            limit: Int,
        ): LexicalRecall {
            lastGenerations = activeGenerationIds
            if (throwError) error("lexical backend down")
            return LexicalRecall(hits = hits, degradedReason = degraded)
        }
    }

    private class FakeDense(
        private val hits: List<DenseHit> = emptyList(),
        private val throwError: Boolean = false,
        private val degraded: String? = null,
    ) : DenseRetriever {
        override suspend fun search(
            request: KnowledgeSearchRequest,
            activeGenerationIds: List<String>,
            limit: Int,
        ): DenseRecall {
            if (throwError) error("dense backend down")
            return DenseRecall(hits = hits, degradedReason = degraded)
        }
    }

    private fun hit(id: String, rank: Int, score: Float = 1f) = LexicalHit(
        chunkId = id,
        docId = "doc-1",
        generationId = "gen-1",
        text = "text-$id",
        locator = "p1s1",
        score = score,
        rank = rank,
    )

    @Test
    fun `empty scope never expands to all`() = runBlocking {
        var called = false
        val lexical = object : LexicalRetriever {
            override suspend fun search(
                request: KnowledgeSearchRequest,
                activeGenerationIds: List<String>,
                limit: Int,
            ): LexicalRecall {
                called = true
                return LexicalRecall(hits = listOf(hit("a", 0)))
            }
        }
        val retriever = KnowledgeRetriever(
            lexicalRetriever = lexical,
            generationResolver = { listOf("gen-all") },
        )
        for (scope in listOf(KnowledgeScope.None, KnowledgeScope.Invalid, KnowledgeScope.Failed("db error"))) {
            val outcome = retriever.search(request(scope = scope))
            assertTrue("空/无效/失败范围必须是 EmptyScope", outcome is RetrievalOutcome.EmptyScope)
        }
        assertEquals("范围为空时不得触发任何召回", false, called)
    }

    @Test
    fun `scope is applied before candidate quota`() = runBlocking {
        val lexical = FakeLexical(hits = listOf(hit("a", 0)))
        val retriever = KnowledgeRetriever(
            lexicalRetriever = lexical,
            generationResolver = { scope ->
                when (scope) {
                    is KnowledgeScope.Resolved -> listOf("gen-only-${scope.docIds.first()}")
                    else -> emptyList()
                }
            },
        )
        retriever.search(request(scope = KnowledgeScope.Resolved(setOf("doc-9"))))
        assertEquals("活动代必须按范围解析", listOf("gen-only-doc-9"), lexical.lastGenerations)
    }

    @Test
    fun `zero match is no match not failure`() = runBlocking {
        val retriever = KnowledgeRetriever(
            lexicalRetriever = FakeLexical(hits = emptyList()),
            generationResolver = { listOf("gen-1") },
        )
        val outcome = retriever.search(request())
        assertTrue(outcome is RetrievalOutcome.NoMatch)
    }

    @Test
    fun `lexical failure with dense success yields partial not unavailable`() = runBlocking {
        val retriever = KnowledgeRetriever(
            lexicalRetriever = FakeLexical(throwError = true),
            denseRetriever = FakeDense(
                hits = listOf(DenseHit("d1", "doc-1", "gen-1", "t", "p1", 0.5f, 0)),
            ),
            generationResolver = { listOf("gen-1") },
        )
        val outcome = retriever.search(request(policy = policy(hybrid = true)))
        assertTrue("一路可用应为 Partial", outcome is RetrievalOutcome.Partial)
        assertEquals(1, (outcome as RetrievalOutcome.Partial).candidates.size)
    }

    @Test
    fun `both paths down is unavailable`() = runBlocking {
        val retriever = KnowledgeRetriever(
            lexicalRetriever = FakeLexical(throwError = true),
            denseRetriever = FakeDense(throwError = true),
            generationResolver = { listOf("gen-1") },
        )
        val outcome = retriever.search(request(policy = policy(hybrid = true)))
        assertTrue(outcome is RetrievalOutcome.Unavailable)
    }

    @Test
    fun `scores stay separated per path`() = runBlocking {
        val retriever = KnowledgeRetriever(
            lexicalRetriever = FakeLexical(hits = listOf(hit("x", 0, score = 2.5f))),
            denseRetriever = FakeDense(
                hits = listOf(DenseHit("x", "doc-1", "gen-1", "t", "p1", 0.88f, 0)),
            ),
            generationResolver = { listOf("gen-1") },
        )
        val outcome = retriever.search(request(policy = policy(hybrid = true)))
        val candidate = (outcome as RetrievalOutcome.Found).candidates.first()
        assertEquals(2.5f, candidate.lexicalScore)
        assertEquals(0.88f, candidate.denseScore)
        assertTrue("融合排名独立", candidate.fusionRank != null)
    }

    @Test
    fun `rerank failure keeps original order and finalK`() = runBlocking {
        val hits = listOf(hit("a", 0), hit("b", 1), hit("c", 2), hit("d", 3))
        val failingRerank = object : RerankProvider {
            override suspend fun rerank(
                query: String,
                candidates: List<EvidenceCandidate>,
                topK: Int,
            ): List<EvidenceCandidate>? = error("rerank timeout")
        }
        val retriever = KnowledgeRetriever(
            lexicalRetriever = FakeLexical(hits = hits),
            rerankProvider = failingRerank,
            generationResolver = { listOf("gen-1") },
        )
        val outcome = retriever.search(request(policy = policy(topK = 3, rerank = true)))
        assertTrue("重排失败应降级为 Partial", outcome is RetrievalOutcome.Partial)
        val partial = outcome as RetrievalOutcome.Partial
        assertEquals("保留原序并截 finalK", 3, partial.candidates.size)
        assertEquals(listOf("a", "b", "c"), partial.candidates.map { it.chunkId })
    }

    @Test
    fun `rerank reorders within given candidates`() = runBlocking {
        val hits = listOf(hit("a", 0), hit("b", 1), hit("c", 2))
        val reversing = object : RerankProvider {
            override suspend fun rerank(
                query: String,
                candidates: List<EvidenceCandidate>,
                topK: Int,
            ): List<EvidenceCandidate> = candidates.reversed().take(topK)
        }
        val retriever = KnowledgeRetriever(
            lexicalRetriever = FakeLexical(hits = hits),
            rerankProvider = reversing,
            generationResolver = { listOf("gen-1") },
        )
        val outcome = retriever.search(request(policy = policy(topK = 3, rerank = true)))
        val found = outcome as RetrievalOutcome.Found
        assertEquals(listOf("c", "b", "a"), found.candidates.map { it.chunkId })
    }

    @Test
    fun `trace contains counts but not query text`() = runBlocking {
        val retriever = KnowledgeRetriever(
            lexicalRetriever = FakeLexical(hits = listOf(hit("a", 0))),
            generationResolver = { listOf("gen-1") },
        )
        val outcome = retriever.search(request(policy = policy()))
        val trace = (outcome as RetrievalOutcome.Found).trace
        assertEquals("CHAT", trace.entry)
        assertEquals(1, trace.lexicalCount)
        assertEquals(false, trace.toString().contains("alpha"))
    }
}
