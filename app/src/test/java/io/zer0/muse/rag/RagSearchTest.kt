package io.zer0.muse.rag

import io.mockk.coEvery
import io.mockk.mockk
import io.zer0.muse.data.knowledge.KnowledgeChunkFtsDao
import io.zer0.muse.data.knowledge.KnowledgeChunkFtsHit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/**
 * P5-1: RAG 核心逻辑测试 — 此前 HybridSearchService/VectorSearchService 无任何测试。
 *
 * 覆盖: FTS 查询构造、RRF 双路融合排序、BM25-only 命中保留(P2-31 回归)、向量 BLOB 序列化。
 */
class RagSearchTest {

    // ── FTS 查询构造 ────────────────────────────────────────────────────────

    @Test
    fun `buildFtsQuery strips punctuation and keeps alnum tokens`() {
        assertEquals("Muse 记忆 rag", HybridSearchService.buildFtsQuery("Muse 记忆, rag!"))
    }

    @Test
    fun `buildFtsQuery filters single-char tokens`() {
        assertEquals("记忆 系统", HybridSearchService.buildFtsQuery("记 忆 系 统 记忆 系统"))
    }

    @Test
    fun `buildFtsQuery dedups tokens`() {
        assertEquals("搜索 召回", HybridSearchService.buildFtsQuery("搜索 搜索 召回"))
    }

    @Test
    fun `buildFtsQuery escapes fts special chars`() {
        assertTrue(!HybridSearchService.buildFtsQuery("\"muse\" rag").contains("\""))
        assertEquals("muse rag", HybridSearchService.buildFtsQuery("\"muse\" rag"))
    }

    @Test
    fun `buildFtsQuery long query falls back to OR`() {
        val query = "记忆 系统 检索 向量 知识 混合 融合 测试 语料 句子 模型"
        val fts = HybridSearchService.buildFtsQuery(query)
        assertTrue("长查询应降级 OR,实际: $fts", fts.contains(" OR "))
    }

    @Test
    fun `buildFtsQuery blank query falls back to quoted phrase`() {
        assertEquals("\"。。。\"", HybridSearchService.buildFtsQuery("。。。"))
    }

    // ── RRF 融合 ────────────────────────────────────────────────────────────

    private fun searchResult(
        chunkId: String,
        score: Float,
        docId: String = "doc-$chunkId",
    ) = VectorSearchService.SearchResult(
        docId = docId,
        docTitle = "title-$chunkId",
        chunkContent = "content-$chunkId",
        score = score,
        chunkIndex = 1,
        chunkId = chunkId,
    )

    @Test
    fun `rrf fusion ranks both-hit result above single-hit`() = runBlocking {
        val fts = mockk<KnowledgeChunkFtsDao>(relaxed = true)
        val vector = mockk<VectorSearchService>(relaxed = true)
        coEvery { vector.search(any(), any(), any(), any(), any(), any()) } returns listOf(
            searchResult("a", 0.9f),
            searchResult("b", 0.7f),
        )
        coEvery { fts.searchBm25(any(), any()) } returns listOf(
            KnowledgeChunkFtsHit(chunkId = "b", score = -12.0),
        )

        val service = HybridSearchService(fts, vector)
        val results = service.hybridSearch("query", floatArrayOf(1f, 0f), topK = 5, threshold = 0.3f, mmrLambda = 0f)

        assertTrue("双路命中 chunk b 应排第一: ${results.map { it.chunkId }}", results.first().chunkId == "b")
        assertTrue(results.first().bothHit)
        assertEquals("a", results[1].chunkId)
        assertTrue("both-hit 分数应更高", results.first().rrfScore > results[1].rrfScore)
    }

    @Test
    fun `bm25 only hits are retained when resolver injected`() = runBlocking {
        val fts = mockk<KnowledgeChunkFtsDao>(relaxed = true)
        val vector = mockk<VectorSearchService>(relaxed = true)
        coEvery { vector.search(any(), any(), any(), any(), any(), any()) } returns listOf(
            searchResult("a", 0.9f),
        )
        coEvery { fts.searchBm25(any(), any()) } returns listOf(
            KnowledgeChunkFtsHit(chunkId = "c", score = -20.0),
        )
        val service = HybridSearchService(fts, vector, bm25MetaResolver = { ids ->
            ids.associateWith { id ->
                HybridSearchService.ChunkMeta(
                    docId = "doc-c", docTitle = "精确命中文档", content = "BM25-only 精确命中内容", chunkIndex = 2,
                )
            }
        })

        val results = service.hybridSearch("专有名词", floatArrayOf(0f, 1f), topK = 5, threshold = 0.3f, mmrLambda = 0f)

        assertEquals("BM25-only 命中不得被丢弃(P2-31)", setOf("a", "c"), results.map { it.chunkId }.toSet())
        val c = results.first { it.chunkId == "c" }
        assertEquals("doc-c", c.docId)
    }

    @Test
    fun `bm25 only hits dropped when no resolver and no vector meta`() = runBlocking {
        val fts = mockk<KnowledgeChunkFtsDao>(relaxed = true)
        val vector = mockk<VectorSearchService>(relaxed = true)
        coEvery { vector.search(any(), any(), any(), any(), any(), any()) } returns emptyList()
        coEvery { fts.searchBm25(any(), any()) } returns listOf(
            KnowledgeChunkFtsHit(chunkId = "c", score = -20.0),
        )
        val service = HybridSearchService(fts, vector)  // resolver = null
        val results = service.hybridSearch("x", floatArrayOf(0f), topK = 5, threshold = 0.3f, mmrLambda = 0f)
        assertTrue("无可解析元数据时应跳过(旧行为)", results.isEmpty())
    }

    // ── 向量 BLOB 序列化 ────────────────────────────────────────────────────

    @Test
    fun `floatArrayToBlob roundtrips dimension and values`() {
        val input = floatArrayOf(0.5f, -1.25f, 3.125f, 0f)
        val blob = VectorSearchService.floatArrayToBlob(input)
        assertEquals("每个 float 4 字节", input.size * 4, blob.size)
        val buffer = ByteBuffer.wrap(blob).asFloatBuffer()
        val out = FloatArray(input.size)
        buffer.get(out)
        for (i in input.indices) {
            assertEquals("bit-identical float @$i", input[i], out[i])
        }
    }
}
