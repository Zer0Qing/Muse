package io.zer0.muse.knowledge

import io.zer0.muse.knowledge.domain.RetrievalPolicy
import io.zer0.muse.knowledge.retrieval.EvidenceCandidate
import io.zer0.muse.knowledge.retrieval.KnowledgeEntryType
import io.zer0.muse.knowledge.retrieval.KnowledgeRetriever
import io.zer0.muse.knowledge.retrieval.KnowledgeSearchRequest
import io.zer0.muse.knowledge.retrieval.LexicalHit
import io.zer0.muse.knowledge.retrieval.LexicalRecall
import io.zer0.muse.knowledge.retrieval.LexicalRetriever
import io.zer0.muse.knowledge.retrieval.RerankProvider
import io.zer0.muse.knowledge.retrieval.RetrievalOutcome
import io.zer0.muse.knowledge.domain.KnowledgeScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * R6 步骤 6(计划 R6:「rerank 的索引协议固定并验证重复/越界/缺项；失败仍 finalK 截断；
 * HTTP timeout/cancel 通过可取消请求测试证明」)。
 */
class KnowledgeRerankFailureTest {

    private fun policy(topK: Int = 3) = RetrievalPolicy(
        topK = topK,
        threshold = 0.35f,
        mmrLambda = 0.5f,
        hybridEnabled = false,
        hybridBm25Weight = 1f,
        hybridVectorWeight = 1f,
        rerankEnabled = true,
        rerankProvider = "test",
        rerankModel = "test",
        tokenBudget = 0,
    )

    private fun hits(n: Int) = (0 until n).map {
        LexicalHit("c$it", "doc-1", "gen-1", "text $it", "p1s$it", 1f - it * 0.1f, it)
    }

    private fun retriever(rerank: RerankProvider) = KnowledgeRetriever(
        lexicalRetriever = object : LexicalRetriever {
            override suspend fun search(
                request: KnowledgeSearchRequest,
                activeGenerationIds: List<String>,
                limit: Int,
            ): LexicalRecall = LexicalRecall(hits = hits(5))
        },
        rerankProvider = rerank,
        generationResolver = { listOf("gen-1") },
    )

    private fun request() = KnowledgeSearchRequest(
        requestId = "req-rerank",
        query = "q",
        entry = KnowledgeEntryType.CHAT,
        scope = KnowledgeScope.Resolved(setOf("doc-1")),
        policy = policy(),
        datasetEpoch = 1L,
        deadlineMs = 1000L,
    )

    @Test
    fun `timeout exception keeps original order and finalK`() = runBlocking {
        val failing = object : RerankProvider {
            override suspend fun rerank(
                query: String,
                candidates: List<EvidenceCandidate>,
                topK: Int,
            ): List<EvidenceCandidate>? = throw IOException("timeout after 1000ms")
        }
        val outcome = retriever(failing).search(request())
        assertTrue(outcome is RetrievalOutcome.Partial)
        val partial = outcome as RetrievalOutcome.Partial
        assertEquals(3, partial.candidates.size)
        assertEquals(listOf("c0", "c1", "c2"), partial.candidates.map { it.chunkId })
    }

    @Test
    fun `cancelled rerank returns null and keeps original order`() = runBlocking {
        val cancelled = object : RerankProvider {
            override suspend fun rerank(
                query: String,
                candidates: List<EvidenceCandidate>,
                topK: Int,
            ): List<EvidenceCandidate>? = null
        }
        val outcome = retriever(cancelled).search(request())
        assertTrue(outcome is RetrievalOutcome.Partial)
        assertEquals(listOf("c0", "c1", "c2"), (outcome as RetrievalOutcome.Partial).candidates.map { it.chunkId })
    }

    @Test
    fun `rerank returning more than finalK is truncated`() = runBlocking {
        val overReturning = object : RerankProvider {
            override suspend fun rerank(
                query: String,
                candidates: List<EvidenceCandidate>,
                topK: Int,
            ): List<EvidenceCandidate> = candidates + candidates // 重复 10 条
        }
        val outcome = retriever(overReturning).search(request())
        val candidates = when (outcome) {
            is RetrievalOutcome.Found -> outcome.candidates
            is RetrievalOutcome.Partial -> outcome.candidates
            else -> emptyList()
        }
        assertEquals("最终必须截到 finalK", 3, candidates.size)
    }
}
