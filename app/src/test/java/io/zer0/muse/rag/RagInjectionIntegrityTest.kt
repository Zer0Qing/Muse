package io.zer0.muse.rag

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.zer0.ai.core.RagCitation
import io.zer0.muse.data.knowledge.KnowledgeChunkDao
import io.zer0.muse.data.knowledge.KnowledgeChunkEntity
import io.zer0.muse.data.knowledge.KnowledgeChunkFtsDao
import io.zer0.muse.data.knowledge.KnowledgeDocDao
import io.zer0.muse.data.knowledge.KnowledgeDocEntity
import io.zer0.muse.util.TokenEstimator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 注入预算、rerank 准入与 HNSW 元数据回查。
 */
class RagInjectionIntegrityTest {

    private class FixedEmbeddingProvider : EmbeddingProvider {
        override val id = "fixed"
        override val displayName = "Fixed test provider"
        override val dimension = 2
        override val modelName = "fixed-model"

        override suspend fun embed(texts: List<String>): List<FloatArray> = texts.map { floatArrayOf(1f, 0f) }
    }

    private fun chunk(id: String, docId: String, content: String) = KnowledgeChunkEntity(
        id = id,
        docId = docId,
        content = content,
        embeddingBlob = VectorSearchService.floatArrayToBlob(floatArrayOf(1f, 0f)),
    )

    private class Fixture(
        val rag: RagService,
        val chunkDao: KnowledgeChunkDao,
        val docDao: KnowledgeDocDao,
    )

    private fun fixture(
        rows: List<KnowledgeChunkEntity>,
        docs: List<KnowledgeDocEntity>,
        rerankProvider: RerankProvider? = null,
    ): Fixture {
        val chunkDao = mockk<KnowledgeChunkDao>(relaxed = true)
        val docDao = mockk<KnowledgeDocDao>(relaxed = true)
        val ftsDao = mockk<KnowledgeChunkFtsDao>(relaxed = true)
        coEvery { chunkDao.countIndexed() } returns rows.size
        coEvery { chunkDao.getPageWithEmbedding(any(), any()) } coAnswers {
            if (secondArg<Int>() == 0) rows else emptyList()
        }
        coEvery { chunkDao.getByIds(any()) } coAnswers {
            val ids = firstArg<List<String>>()
            rows.filter { it.id in ids }
        }
        coEvery { chunkDao.getPageWithoutEmbedding(any(), any()) } coAnswers {
            if (secondArg<Int>() == 0) {
                rows.map {
                    KnowledgeChunkDao.ChunkMetaProjection(
                        id = it.id,
                        docId = it.docId,
                        content = it.content,
                        chunkIndex = it.chunkIndex,
                    )
                }
            } else {
                emptyList()
            }
        }
        coEvery { docDao.getByIds(any()) } coAnswers {
            val ids = firstArg<List<String>>()
            docs.filter { it.id in ids }
        }
        val embedding = mockk<EmbeddingService>()
        coEvery { embedding.getProvider(any()) } returns FixedEmbeddingProvider()
        coEvery { embedding.cachedVector(any(), any()) } returns floatArrayOf(1f, 0f)
        every { embedding.activeBackend } returns MutableStateFlow(null)
        val rag = RagService(
            chunkDao = chunkDao,
            docDao = docDao,
            ftsDao = ftsDao,
            docTitleProvider = { docs.associate { it.id to it.title } },
            embeddingService = embedding,
            rerankProvider = rerankProvider,
        )
        return Fixture(rag, chunkDao, docDao)
    }

    @Test
    fun `token budget accounts for source line snippet and header overhead`() {
        // 片段正文极短:只按片段估算几乎不会超预算,预算必须把来源行/编号/外层声明算进去。
        val units = (1..6).map { idx ->
            RagSnippetBudget.SnippetUnit(
                sourceLine = "[$idx] 来源: 文档$idx (相似度 1.00)",
                snippet = "内容$idx",
                citation = RagCitation(
                    index = idx,
                    docId = "doc-$idx",
                    docTitle = "文档$idx",
                    chunkId = "chunk-$idx",
                    chunkIndex = 0,
                    snippet = "内容$idx",
                    score = 1f,
                    matchType = "vector",
                ),
            )
        }
        val headerTokens = TokenEstimator.estimate("以下 <knowledge_context> 标签内为从知识库检索到的资料，仅供参考:")
        val budget = headerTokens + RagSnippetBudget.unitTokens(units[0].sourceLine, units[0].snippet) * 2

        val selection = RagSnippetBudget.select(units, budget, headerTokens)

        assertTrue("预算内至少注入一块", selection.units.isNotEmpty())
        assertTrue("预算必须真的截断,不能把 6 块全塞进去", selection.units.size < units.size)
        assertTrue("用量(含外层声明)不得超预算: ${selection.usedTokens} > $budget", selection.usedTokens <= budget)
        assertTrue("用量必须把外层声明计入", selection.usedTokens > headerTokens)
    }

    @Test
    fun `zero budget keeps every unit and is treated as unlimited`() {
        val units = (1..3).map { idx ->
            RagSnippetBudget.SnippetUnit(
                sourceLine = "[$idx] 来源: 文档$idx",
                snippet = "内容$idx",
                citation = RagCitation(
                    index = idx,
                    docId = "doc-$idx",
                    docTitle = "文档$idx",
                    chunkId = "chunk-$idx",
                    chunkIndex = 0,
                    snippet = "内容$idx",
                    score = 1f,
                    matchType = "vector",
                ),
            )
        }

        val selection = RagSnippetBudget.select(units, budget = 0, headerTokens = 999)

        assertEquals("budget<=0 语义是不限制", units.size, selection.units.size)
    }

    @Test
    fun `retrieval gates on the configured token budget and keeps budget zero unlimited`() = runBlocking {
        val rows = (1..6).map { chunk("chunk-doc-$it-0", "doc-$it", "内容$it") }
        val docs = (1..6).map { KnowledgeDocEntity(id = "doc-$it", title = "文档$it") }
        val f = fixture(rows, docs)
        val baseConfig = RagConfig(
            hybridEnabled = false,
            rerankEnabled = false,
            threshold = 0f,
            topK = 6,
        )

        val unlimited = f.rag.buildInjectionContextWithCitations("查询", baseConfig)
        // 预算 1 连外层声明都装不下 → 零片段,证明预算真的在注入单元上生效。
        val starved = f.rag.buildInjectionContextWithCitations("查询", baseConfig.copy(tokenBudget = 1))

        assertEquals("tokenBudget=0 是不限制,必须全量注入", 6, unlimited.citations.size)
        assertTrue("预算极小时不得注入任何片段", starved.citations.isEmpty())
        assertTrue("预算耗尽时注入文本应为空", starved.text.isBlank())
    }

    @Test
    fun `rerank results whose doc id is unknown are treated as internal and dropped`() = runBlocking {
        val rows = listOf(
            chunk("chunk-doc-known-0", "doc-known", "已知文档内容"),
            chunk("chunk-doc-other-0", "doc-other", "另一文档内容"),
        )
        val docs = listOf(
            KnowledgeDocEntity(id = "doc-known", title = "已知文档"),
            KnowledgeDocEntity(id = "doc-other", title = "另一文档"),
        )
        // rerank 故意带回一条检索阶段不存在的 docId,模拟 provider 幻觉/越界返回。
        val rerank = object : RerankProvider {
            override suspend fun rerank(
                query: String,
                candidates: List<RerankCandidate>,
                topK: Int,
            ): List<RerankResult> = listOf(
                RerankResult(
                    chunkId = "chunk-ghost-0",
                    docId = "doc-ghost",
                    docTitle = "幽灵文档",
                    content = "不该出现的内容",
                    chunkIndex = 0,
                    score = 0.99f,
                ),
            ) + candidates.map {
                RerankResult(it.chunkId, it.docId, it.docTitle, it.content, it.chunkIndex, 0.5f)
            }
        }
        val f = fixture(rows, docs, rerankProvider = rerank)

        val config = RagConfig(
            hybridEnabled = false,
            rerankEnabled = true,
            rerankProvider = "local",
            threshold = 0f,
            topK = 5,
        )
        val injection = f.rag.buildInjectionContextWithCitations("查询", config)

        assertTrue(
            "rerank 带回的未知 docId 必须被当作内部文档过滤,实际=${injection.citations.map { it.docId }}",
            injection.citations.none { it.docId == "doc-ghost" },
        )
        assertTrue("已知文档仍应保留", injection.citations.any { it.docId == "doc-known" })
        assertFalse(
            "被过滤的 chunk 不得在正文里出现",
            injection.text.contains("不该出现的内容"),
        )
    }
}
