package io.zer0.muse.rag

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * P2-33: 大库定向检索的载入量受限。
 *
 * 缺陷场景:@mention / 助手绑定 KB 的定向检索把 `Int.MAX_VALUE` 传给 provider,
 * provider 落成 `getByDocIds(...).drop/take` —— 整个检索范围(大库可 >2000 chunk)
 * 一次性载入内存,再按页丢弃。
 *
 * 修复后:按 [VectorSearchService.BATCH_SIZE] 分页拉取,单次 provider 调用的载入量
 * 有上界,且检索结果与「一次性全量载入」严格等价(集合与排序一致)。
 */
class VectorSearchServicePagingTest {

    private val dim = 8

    private fun vec(seed: Int): FloatArray {
        val random = java.util.Random(seed.toLong() * 7919L + 13L)
        return FloatArray(dim) { random.nextFloat() * 2f - 1f }
    }

    private fun chunk(index: Int, docId: String = "doc-1") = VectorSearchService.ChunkWithDoc(
        chunkId = "chunk-$index",
        docId = docId,
        docTitle = "title-$docId",
        content = "content-$index",
        embedding = "",
        embeddingBlob = VectorSearchService.floatArrayToBlob(vec(index)),
        chunkIndex = index,
    )

    /** 记录每次调用请求的 limit,并断言单次载入不超过 BATCH_SIZE。 */
    private class PagedProbe(private val all: List<VectorSearchService.ChunkWithDoc>) {
        val requestedLimits = java.util.Collections.synchronizedList(mutableListOf<Int>())
        val calls = AtomicInteger(0)

        suspend fun page(@Suppress("UNUSED_PARAMETER") docIds: List<String>, limit: Int, offset: Int): List<VectorSearchService.ChunkWithDoc> {
            calls.incrementAndGet()
            requestedLimits.add(limit)
            return all.drop(offset).take(limit)
        }
    }

    @Test
    fun `large scoped search pages the provider and never over-loads`() = runBlocking {
        val scopeChunks = (0 until 2_500).map { chunk(it) } // > CACHE_THRESHOLD(2000)
        val probe = PagedProbe(scopeChunks)
        val service = VectorSearchService(
            chunkPageProvider = { _, _ -> error("定向检索不得走全量 provider") },
            chunkCountProvider = { error("定向检索不得读取全库计数") },
            chunkPageByDocIdsProvider = { docIds, limit, offset -> probe.page(docIds, limit, offset) },
        )

        val results = service.search(
            queryVector = vec(7),
            topK = 5,
            threshold = 0.0f,
            mmrLambda = 1.0f,
            scopeDocIds = listOf("doc-1"),
        )

        assertEquals(5, results.size)
        assertTrue("定向检索必须分页拉取", probe.calls.get() >= 5)
        assertTrue(
            "单次 provider 调用的载入量必须有上界(实际=${probe.requestedLimits}),不得出现 Int.MAX_VALUE",
            probe.requestedLimits.all { it <= VectorSearchService.BATCH_SIZE },
        )
        assertFalse(results.any { it.docId != "doc-1" })

        // 与全量载入 + 稳定排序 + take(topK) 的参考实现等价
        val expected = referenceTopK(scopeChunks, vec(7), topK = 5)
        assertEquals(expected.map { it.first }, results.map { it.chunkId })
        assertScoresClose(expected.map { it.second }, results.map { it.score })
    }

    @Test
    fun `paged scoped search is equivalent to a single full load`() = runBlocking {
        val scopeChunks = (0 until 2_500).map { chunk(it) }
        val probe = PagedProbe(scopeChunks)
        val paged = VectorSearchService(
            chunkPageProvider = { _, _ -> error("unused") },
            chunkCountProvider = { error("unused") },
            chunkPageByDocIdsProvider = { docIds, limit, offset -> probe.page(docIds, limit, offset) },
        )
        // 旧语义替身:无视 limit,第一次调用即返回整个检索范围(等价于旧 Int.MAX_VALUE 载入)
        val legacy = VectorSearchService(
            chunkPageProvider = { _, _ -> error("unused") },
            chunkCountProvider = { error("unused") },
            chunkPageByDocIdsProvider = { _, _, offset -> if (offset == 0) scopeChunks else emptyList() },
        )
        val query = vec(11)

        listOf(1.0f, 0.7f).forEach { lambda ->
            listOf(0.0f, 0.2f).forEach { threshold ->
                val pagedResult = paged.search(query, topK = 8, threshold = threshold, mmrLambda = lambda, scopeDocIds = listOf("doc-1"))
                val legacyResult = legacy.search(query, topK = 8, threshold = threshold, mmrLambda = lambda, scopeDocIds = listOf("doc-1"))
                assertEquals(
                    "λ=$lambda threshold=$threshold 的候选集合/排序必须与全量载入一致",
                    legacyResult.map { it.chunkId },
                    pagedResult.map { it.chunkId },
                )
                assertScoresClose(legacyResult.map { it.score }, pagedResult.map { it.score })
            }
        }
    }

    @Test
    fun `metadata filtered search pages the provider`() = runBlocking {
        val all = (0 until 2_500).map { chunk(it).copy(metadataJson = """{"tag":"rag"}""") }
        val requestedLimits = java.util.Collections.synchronizedList(mutableListOf<Int>())
        val service = VectorSearchService(
            chunkPageProvider = { _, _ -> error("unused") },
            chunkCountProvider = { error("unused") },
            chunkPageByDocIdsProvider = null,
            chunkPageByMetadataProvider = { _, limit, offset ->
                requestedLimits.add(limit)
                all.drop(offset).take(limit)
            },
        )

        val results = service.search(
            queryVector = vec(3),
            topK = 4,
            threshold = 0.0f,
            mmrLambda = 1.0f,
            metadataFilter = VectorSearchService.MetadataFilter(tag = "rag"),
        )

        assertEquals(4, results.size)
        assertTrue(requestedLimits.size >= 5)
        assertTrue("metadata 路径同样不得一次性载入(实际=$requestedLimits)", requestedLimits.all { it <= VectorSearchService.BATCH_SIZE })
    }

    @Test
    fun `equal scores keep scan order like a stable sort`() = runBlocking {
        // 相同向量 + 同向查询 → 分数完全相同(cos=1.0,不会被 threshold 过滤掉):
        // 有界 top-K 必须与稳定排序一致(保留更早扫描到的)。
        // 注意查询向量必须与块向量同向,否则余弦可能为负而被 threshold=0.0 全部剔除。
        val aligned = FloatArray(dim) { 0.5f }
        val same = VectorSearchService.floatArrayToBlob(aligned)
        val chunks = (0 until 40).map { index ->
            chunk(index).copy(embeddingBlob = same)
        }
        val service = VectorSearchService(
            chunkPageProvider = { _, _ -> error("unused") },
            chunkCountProvider = { error("unused") },
            chunkPageByDocIdsProvider = { _, limit, offset -> chunks.drop(offset).take(limit) },
        )

        val results = service.search(aligned, topK = 5, threshold = 0.0f, mmrLambda = 1.0f, scopeDocIds = listOf("doc-1"))
        assertEquals(listOf("chunk-0", "chunk-1", "chunk-2", "chunk-3", "chunk-4"), results.map { it.chunkId })
        // 并列分数必须全部通过 threshold,避免本条用例退化成「空结果互相相等」的空转断言
        assertTrue("并列分数不得被 threshold 过滤(实际得分=${results.map { it.score }})", results.all { it.score >= 0f })
    }

    /** 参考实现:一次性全量载入 + 打分 + 稳定降序 + take(topK)(threshold=0 时保留非负分数)。 */
    private fun referenceTopK(
        chunks: List<VectorSearchService.ChunkWithDoc>,
        query: FloatArray,
        topK: Int,
    ): List<Pair<String, Float>> {
        val queryNorm = kotlin.math.sqrt(query.sumOf { (it * it).toDouble() }).toFloat()
        return chunks.mapNotNull { c ->
            val vector = FloatArray(dim) { i -> java.nio.ByteBuffer.wrap(c.embeddingBlob!!).asFloatBuffer().get(i) }
            val norm = kotlin.math.sqrt(vector.sumOf { (it * it).toDouble() }).toFloat()
            if (norm == 0f) return@mapNotNull null
            var dot = 0f
            for (i in 0 until dim) dot += vector[i] * query[i]
            val score = dot / (norm * queryNorm)
            if (score < 0f) return@mapNotNull null
            c.chunkId to score
        }
            .sortedByDescending { it.second }
            .take(topK)
    }

    /** 分数比较:两侧浮点累加顺序不同,允许 1e-4 级误差。 */
    private fun assertScoresClose(expected: List<Float>, actual: List<Float>) {
        assertEquals(expected.size, actual.size)
        expected.indices.forEach { i ->
            assertTrue(
                "score[$i] 期望=${expected[i]} 实际=${actual[i]}",
                kotlin.math.abs(expected[i] - actual[i]) < 1e-4f,
            )
        }
    }
}
