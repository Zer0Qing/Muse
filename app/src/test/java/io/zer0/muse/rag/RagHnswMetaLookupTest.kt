package io.zer0.muse.rag

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.zer0.muse.data.knowledge.KnowledgeChunkDao
import io.zer0.muse.data.knowledge.KnowledgeChunkEntity
import io.zer0.muse.data.knowledge.KnowledgeChunkFtsDao
import io.zer0.muse.data.knowledge.KnowledgeDocDao
import io.zer0.muse.data.knowledge.KnowledgeDocEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HNSW 路径的元数据回查。
 *
 * chunkMetaCache 是 LRU(上限 2000 条),大库冷启动首批检索几乎必然未命中。
 * 未命中必须回查 DB,而不是把 ANN 候选直接丢掉 —— 否则库越大越搜不到。
 */
class RagHnswMetaLookupTest {

    private class FixedEmbeddingProvider : EmbeddingProvider {
        override val id = "fixed"
        override val displayName = "Fixed test provider"
        override val dimension = 2
        override val modelName = "fixed-model"

        override suspend fun embed(texts: List<String>): List<FloatArray> = texts.map { floatArrayOf(1f, 0f) }
    }

    private fun config() = RagConfig(
        hybridEnabled = false,
        rerankEnabled = false,
        threshold = 0f,
        topK = 8,
        markdownAware = false,
    )

    private fun chunk(id: String, docId: String, content: String) = KnowledgeChunkEntity(
        id = id,
        docId = docId,
        content = content,
        embeddingBlob = VectorSearchService.floatArrayToBlob(floatArrayOf(1f, 0f)),
    )

    private class Harness(
        val rag: RagService,
        val chunkDao: KnowledgeChunkDao,
        val indexedRows: MutableList<KnowledgeChunkEntity>,
    )

    /**
     * 构造一个「HNSW 索引里有向量、chunkMetaCache 是空的」场景:
     * 索引加载时元数据缓存按页重建,这里让那一页为空,等价于 LRU 淘汰后的冷启动状态。
     */
    private fun harness(docs: List<KnowledgeDocEntity>): Harness {
        val indexedRows = mutableListOf<KnowledgeChunkEntity>()
        val chunkDao = mockk<KnowledgeChunkDao>(relaxed = true)
        val docDao = mockk<KnowledgeDocDao>(relaxed = true)
        val ftsDao = mockk<KnowledgeChunkFtsDao>(relaxed = true)
        // countIndexed()==0 时加载不会判定「索引需全量重建」,HNSW 路径才可用。
        coEvery { chunkDao.countIndexed() } returns 0
        coEvery { chunkDao.getPageWithEmbedding(any(), any()) } coAnswers {
            if (secondArg<Int>() == 0) indexedRows.toList() else emptyList()
        }
        coEvery { chunkDao.getPageWithoutEmbedding(any(), any()) } returns emptyList()
        coEvery { chunkDao.getByIds(any()) } coAnswers {
            val ids = firstArg<List<String>>()
            indexedRows.filter { it.id in ids }
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
        )
        return Harness(rag, chunkDao, indexedRows)
    }

    /** 把向量直接塞进 HNSW 索引,模拟「索引已加载、元数据缓存是空的」大库状态。 */
    private fun seedVectorIndex(rag: RagService, rows: List<KnowledgeChunkEntity>) {
        val field = RagService::class.java.getDeclaredField("vectorIndex").apply { isAccessible = true }
        // RagService 构造时必定创建 HNSW 索引;拿不到就说明结构变了,直接让测试失败而不是静默跳过。
        val index = checkNotNull(field.get(rag) as? VectorIndex) { "RagService.vectorIndex 不可用,无法注入测试向量" }
        rows.forEach { index.add(it.id, floatArrayOf(1f, 0f)) }
        assertTrue("测试前提:HNSW 索引必须真的被写入", index.size == rows.size)
    }

    private val docs = listOf(
        KnowledgeDocEntity(id = "doc-a", title = "A"),
        KnowledgeDocEntity(id = "doc-b", title = "B"),
    )

    private val rows = listOf(
        chunk("chunk-a-0", "doc-a", "知识库甲的内容"),
        chunk("chunk-b-0", "doc-b", "知识库乙的内容"),
    )

    @Test
    fun `cache miss resolves chunk metadata from the database instead of dropping hits`() = runBlocking {
        val h = harness(docs)
        h.indexedRows.addAll(rows)
        h.rag.loadVectorIndexIfNeeded(config())
        seedVectorIndex(h.rag, rows)

        val results = h.rag.retrieve("查询", topK = 8, threshold = 0f, ragConfig = config())

        assertEquals(
            "缓存未命中必须回查 DB,不得把 topK 砍成 0",
            setOf("doc-a", "doc-b"),
            results.map { it.docId }.toSet(),
        )
        assertTrue("回查结果必须带正文", results.all { it.chunkContent.isNotBlank() })
    }

    @Test
    fun `metadata lookup failure falls back to the vector scan`() = runBlocking {
        val h = harness(docs)
        h.indexedRows.addAll(rows)
        h.rag.loadVectorIndexIfNeeded(config())
        seedVectorIndex(h.rag, rows)
        coEvery { h.chunkDao.getByIds(any()) } throws IllegalStateException("injected metadata lookup failure")
        coEvery { h.chunkDao.countIndexed() } returns rows.size

        val results = h.rag.retrieve("查询", topK = 8, threshold = 0f, ragConfig = config())

        assertEquals(
            "回查失败必须回退全量遍历,而不是返回空",
            setOf("doc-a", "doc-b"),
            results.map { it.docId }.toSet(),
        )
    }
}
