package io.zer0.muse.rag

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * v1.55: HNSW 向量索引单元测试。
 *
 * 覆盖:
 *  - add + search 正确性(与暴力遍历结果对比 top-1 召回率)
 *  - save + load 一致性(序列化/反序列化后检索结果一致)
 *  - remove(删除后该 id 不再出现在搜索结果中,size 同步减少)
 *  - 边界条件(空索引、k=0、k 超过 size、单元素索引)
 */
class HnswVectorIndexTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "hnsw_test_${System.nanoTime()}")
        tempDir.mkdirs()
    }

    @After
    fun tearDown() {
        tempDir.listFiles()?.forEach { it.delete() }
        tempDir.delete()
    }

    /** 构造一个高 efSearch 的索引(测试场景下确保高召回,避免近似误差干扰断言)。 */
    private fun newTestIndex(
        m: Int = 16,
        efConstruction: Int = 200,
        efSearch: Int = 100,
    ): HnswVectorIndex = HnswVectorIndex(
        M = m,
        efConstruction = efConstruction,
        efSearch = efSearch,
        seed = 42L,
    )

    /** 生成 n 个 dim 维随机向量(固定种子,可复现)。 */
    private fun genVectors(n: Int, dim: Int, seed: Long = 42L): List<Pair<String, FloatArray>> {
        val rng = Random(seed)
        return (0 until n).map { i ->
            val vec = FloatArray(dim) { rng.nextFloat() * 2f - 1f }  // [-1, 1]
            "vec-$i" to vec
        }
    }

    /** 暴力遍历 top-k(参考实现,用于验证 HNSW 结果)。 */
    private fun bruteForceSearch(
        query: FloatArray,
        vectors: List<Pair<String, FloatArray>>,
        k: Int,
    ): List<Pair<String, Float>> {
        return vectors
            .map { (id, vec) -> id to cosineSim(query, vec) }
            .sortedByDescending { it.second }
            .take(k)
    }

    /** 余弦相似度(与 HNSW 内部 distance 互逆:score = 1 - distance)。 */
    private fun cosineSim(a: FloatArray, b: FloatArray): Float {
        val n = minOf(a.size, b.size)
        if (n == 0) return 0f
        var dot = 0f
        var na = 0f
        var nb = 0f
        for (i in 0 until n) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        val d = sqrt(na) * sqrt(nb)
        return if (d == 0f) 0f else dot / d
    }

    // ── add + search 正确性 ──

    @Test
    fun `空索引 search 返回空列表`() {
        val index = newTestIndex()
        assertTrue(index.size == 0)
        val results = index.search(FloatArray(64), k = 5)
        assertTrue("空索引搜索应返回空列表", results.isEmpty())
    }

    @Test
    fun `k 小于等于 0 返回空列表`() {
        val index = newTestIndex()
        val vectors = genVectors(n = 10, dim = 32)
        vectors.forEach { (id, vec) -> index.add(id, vec) }
        assertTrue(index.search(vectors[0].second, k = 0).isEmpty())
        assertTrue(index.search(vectors[0].second, k = -1).isEmpty())
    }

    @Test
    fun `k 超过 size 时返回全部可用结果`() {
        val index = newTestIndex()
        val vectors = genVectors(n = 5, dim = 32)
        vectors.forEach { (id, vec) -> index.add(id, vec) }

        val results = index.search(vectors[0].second, k = 100)
        assertTrue("k > size 时应返回 size 条结果", results.size <= 5)
        // 不强制等于 5(HNSW 可能因图遍历不到所有节点而略少),但至少应返回查询向量自身
        assertTrue("至少应返回 1 条结果", results.isNotEmpty())
    }

    @Test
    fun `单元素索引 search 返回该元素`() {
        val index = newTestIndex()
        val vec = FloatArray(16) { it.toFloat() }
        index.add("only", vec)

        val results = index.search(vec, k = 1)
        assertEquals(1, results.size)
        assertEquals("only", results[0].id)
        // 自相似度应为 1.0(余弦距离 0)
        assertEquals(1.0f, results[0].score, 1e-4f)
        assertEquals(0.0f, results[0].distance, 1e-4f)
    }

    @Test
    fun `add 后 size 同步增加`() {
        val index = newTestIndex()
        val vectors = genVectors(n = 50, dim = 32)
        for ((i, pair) in vectors.withIndex()) {
            index.add(pair.first, pair.second)
            assertEquals(i + 1, index.size)
        }
    }

    @Test
    fun `add 同 id 覆盖旧向量 size 不变`() {
        val index = newTestIndex()
        val vec1 = FloatArray(16) { 1f }
        val vec2 = FloatArray(16) { 2f }
        index.add("dup", vec1)
        assertEquals(1, index.size)
        index.add("dup", vec2)
        assertEquals("同 id 覆盖后 size 不应增加", 1, index.size)

        // 验证覆盖后用 vec2 查询应得 score=1(vec1 已被替换)
        val results = index.search(vec2, k = 1)
        assertEquals(1, results.size)
        assertEquals("dup", results[0].id)
        assertEquals(1.0f, results[0].score, 1e-4f)
    }

    @Test
    fun `search top-1 与暴力遍历一致 - 小规模 100 向量`() {
        val n = 100
        val dim = 64
        val vectors = genVectors(n = n, dim = dim)
        val index = newTestIndex(efSearch = 200)  // 高 efSearch 确保高召回
        vectors.forEach { (id, vec) -> index.add(id, vec) }
        assertEquals(n, index.size)

        // 用前 20 个向量作为查询(应为自身的 top-1,余弦相似度 = 1.0)
        var matched = 0
        val total = 20
        for (i in 0 until total) {
            val query = vectors[i].second
            val expected = bruteForceSearch(query, vectors, k = 1).first()
            val actual = index.search(query, k = 1).firstOrNull() ?: continue
            if (actual.id == expected.first) matched++
        }
        // 至少 90% 召回(HNSW 是近似算法,允许小概率 miss)
        assertTrue("top-1 召回率 ${matched.toDouble() / total} 应 >= 0.9", matched.toDouble() / total >= 0.9)
    }

    @Test
    fun `search top-1 与暴力遍历一致 - 中规模 500 向量`() {
        val n = 500
        val dim = 64
        val vectors = genVectors(n = n, dim = dim)
        val index = newTestIndex(efSearch = 200)
        vectors.forEach { (id, vec) -> index.add(id, vec) }
        assertEquals(n, index.size)

        // 用 50 个随机查询向量测试召回率
        val rng = Random(123L)
        var matched = 0
        val total = 50
        repeat(total) {
            val query = FloatArray(dim) { rng.nextFloat() * 2f - 1f }
            val expected = bruteForceSearch(query, vectors, k = 1).firstOrNull() ?: return@repeat
            val actual = index.search(query, k = 1).firstOrNull() ?: return@repeat
            if (actual.id == expected.first) matched++
        }
        // 中规模至少 85% 召回
        assertTrue("500 向量 top-1 召回率 ${matched.toDouble() / total} 应 >= 0.85", matched.toDouble() / total >= 0.85)
    }

    @Test
    fun `search 返回结果按相似度降序`() {
        val index = newTestIndex()
        val vectors = genVectors(n = 30, dim = 32)
        vectors.forEach { (id, vec) -> index.add(id, vec) }

        val query = vectors[0].second
        val results = index.search(query, k = 10)
        assertTrue(results.size >= 2)

        for (i in 1 until results.size) {
            assertTrue(
                "结果应按 score 降序:results[${i - 1}].score=${results[i - 1].score} >= results[$i].score=${results[i].score}",
                results[i - 1].score >= results[i].score,
            )
        }
    }

    @Test
    fun `score 与 distance 互为 1 互补`() {
        val index = newTestIndex()
        val vectors = genVectors(n = 10, dim = 32)
        vectors.forEach { (id, vec) -> index.add(id, vec) }

        val results = index.search(vectors[0].second, k = 5)
        for (r in results) {
            assertEquals("score + distance 应 = 1", 1.0f, r.score + r.distance, 1e-4f)
        }
    }

    // ── save + load 一致性 ──

    @Test
    fun `save 后 load 检索结果一致`() {
        val vectors = genVectors(n = 80, dim = 32)
        val index1 = newTestIndex(efSearch = 200)
        vectors.forEach { (id, vec) -> index1.add(id, vec) }
        assertEquals(80, index1.size)

        // 保存
        val file = File(tempDir, "save_load_test.bin")
        index1.save(file)
        assertTrue("索引文件应已创建", file.exists())
        assertTrue("索引文件不应为空", file.length() > 0)

        // 加载到新索引
        val index2 = newTestIndex(efSearch = 200)
        assertEquals(0, index2.size)
        index2.load(file)
        assertEquals("load 后 size 应与原索引一致", index1.size, index2.size)

        // 对比检索结果(用 10 个查询向量)
        val rng = Random(456L)
        repeat(10) {
            val query = FloatArray(32) { rng.nextFloat() * 2f - 1f }
            val r1 = index1.search(query, k = 5)
            val r2 = index2.search(query, k = 5)
            assertEquals("load 后结果数应一致", r1.size, r2.size)
            for ((a, b) in r1.zip(r2)) {
                assertEquals("load 后 id 应一致", a.id, b.id)
                assertEquals("load 后 score 应一致", a.score, b.score, 1e-4f)
                assertEquals("load 后 distance 应一致", a.distance, b.distance, 1e-4f)
            }
        }
    }

    @Test
    fun `load 不存在的文件不抛异常且索引保持空`() {
        val index = newTestIndex()
        val nonexistent = File(tempDir, "does_not_exist.bin")
        // 不应抛异常
        index.load(nonexistent)
        assertEquals(0, index.size)
    }

    @Test
    fun `load magic 不匹配的文件不抛异常`() {
        val index = newTestIndex()
        val badFile = File(tempDir, "bad_magic.bin")
        // 写入错误的 magic header
        badFile.writeBytes("BADMAG".toByteArray(Charsets.US_ASCII) + ByteArray(20))
        // 不应抛异常,索引保持空
        index.load(badFile)
        assertEquals("magic 不匹配应跳过加载,索引保持空", 0, index.size)
    }

    @Test
    fun `save 包含已删除节点 - load 后 size 一致`() {
        val vectors = genVectors(n = 50, dim = 32)
        val index = newTestIndex()
        vectors.forEach { (id, vec) -> index.add(id, vec) }
        assertEquals(50, index.size)

        // 删除 10 个
        for (i in 0 until 10) {
            index.remove("vec-$i")
        }
        assertEquals(40, index.size)

        // 保存 → 加载
        val file = File(tempDir, "with_deletes.bin")
        index.save(file)
        val loaded = newTestIndex()
        loaded.load(file)
        // load 后应剔除已删除节点,size 应等于存活节点数
        assertEquals("save/load 应压缩剔除已删除节点", 40, loaded.size)

        // 已删除的 id 不应出现在 search 结果中
        for (i in 0 until 10) {
            val results = loaded.search(vectors[i].second, k = 5)
            assertFalse("已删除的 vec-$i 不应出现", results.any { it.id == "vec-$i" })
        }
    }

    // ── remove ──

    @Test
    fun `remove 已存在的 id 后 size 减少`() {
        val index = newTestIndex()
        val vectors = genVectors(n = 20, dim = 32)
        vectors.forEach { (id, vec) -> index.add(id, vec) }
        assertEquals(20, index.size)

        index.remove("vec-5")
        assertEquals(19, index.size)
        index.remove("vec-10")
        assertEquals(18, index.size)
    }

    @Test
    fun `remove 不存在的 id 不抛异常 size 不变`() {
        val index = newTestIndex()
        val vectors = genVectors(n = 5, dim = 32)
        vectors.forEach { (id, vec) -> index.add(id, vec) }
        assertEquals(5, index.size)

        // 不抛异常,size 不变
        index.remove("nonexistent")
        assertEquals(5, index.size)
    }

    @Test
    fun `remove 后该 id 不再出现在 search 结果中`() {
        val index = newTestIndex(efSearch = 200)
        val vectors = genVectors(n = 30, dim = 32)
        vectors.forEach { (id, vec) -> index.add(id, vec) }

        // 先验证 vec-0 是其自身的 top-1
        val beforeRemove = index.search(vectors[0].second, k = 1)
        assertTrue("删除前 vec-0 应在 top-1", beforeRemove.any { it.id == "vec-0" })

        // 删除 vec-0
        index.remove("vec-0")
        assertEquals(29, index.size)

        // 删除后 vec-0 不应再出现在结果中
        val afterRemove = index.search(vectors[0].second, k = 10)
        assertFalse("删除后 vec-0 不应出现", afterRemove.any { it.id == "vec-0" })
        assertTrue("删除后仍应返回结果(其他向量)", afterRemove.isNotEmpty())
    }

    @Test
    fun `remove 入口点后仍可正常 search`() {
        val index = newTestIndex(efSearch = 200)
        val vectors = genVectors(n = 50, dim = 32)
        vectors.forEach { (id, vec) -> index.add(id, vec) }

        // 反复删除多个节点(可能命中入口点),验证 search 仍可正常工作
        for (i in 0 until 20) {
            index.remove("vec-$i")
        }
        assertEquals(30, index.size)

        // search 不应抛异常,应返回结果
        val rng = Random(789L)
        repeat(5) {
            val query = FloatArray(32) { rng.nextFloat() * 2f - 1f }
            val results = index.search(query, k = 5)
            assertNotNull(results)
            assertTrue(results.size <= 5)
        }
    }

    // ── VectorIndexFactory ──

    @Test
    fun `Factory createIndex BRUTE_FORCE 返回 null`() {
        val index = VectorIndexFactory.createIndex(VectorIndexFactory.IndexStrategy.BRUTE_FORCE)
        assertEquals(null, index)
    }

    @Test
    fun `Factory createIndex HNSW 返回 HnswVectorIndex 实例`() {
        val index = VectorIndexFactory.createIndex(VectorIndexFactory.IndexStrategy.HNSW)
        assertNotNull(index)
        assertTrue("应为 HnswVectorIndex 实例", index is HnswVectorIndex)
    }

    @Test
    fun `Factory autoStrategy 阈值正确`() {
        assertEquals(
            VectorIndexFactory.IndexStrategy.BRUTE_FORCE,
            VectorIndexFactory.autoStrategy(0),
        )
        assertEquals(
            VectorIndexFactory.IndexStrategy.BRUTE_FORCE,
            VectorIndexFactory.autoStrategy(4999),
        )
        assertEquals(
            VectorIndexFactory.IndexStrategy.HNSW,
            VectorIndexFactory.autoStrategy(5000),
        )
        assertEquals(
            VectorIndexFactory.IndexStrategy.HNSW,
            VectorIndexFactory.autoStrategy(100_000),
        )
    }

    @Test
    fun `Factory createAutoIndex 大规模返回 HNSW 实例`() {
        val index = VectorIndexFactory.createAutoIndex(50_000)
        assertNotNull(index)
        assertTrue(index is HnswVectorIndex)
    }

    @Test
    fun `Factory createAutoIndex 小规模返回 null`() {
        val index = VectorIndexFactory.createAutoIndex(100)
        assertEquals(null, index)
    }
}
