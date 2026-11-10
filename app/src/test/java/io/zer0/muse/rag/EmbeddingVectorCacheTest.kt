package io.zer0.muse.rag

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * embedding 向量缓存。
 *
 * 记忆相关性重排每轮都要把「query + 全部候选事实」送去做一次 embedding。
 * 云端源下这是一次 HTTPS 往返，而候选事实几乎不变 —— 这里锁的就是
 * "事实向量只算一次、只上传一次"，以及"换了模型/Key 之后不许复用旧向量"。
 */
class EmbeddingVectorCacheTest {

    /** 记录每次批量调用的入参，用来断言"哪些文本真的进了请求"。 */
    private class RecordingProvider(
        private val vectorSize: Int = 4,
        private val failAfter: Int = Int.MAX_VALUE,
    ) : EmbeddingProvider {
        override val id: String = "recording"
        override val displayName: String = "Recording"
        override val dimension: Int = vectorSize
        override val modelName: String = "recording-model"
        val batches = mutableListOf<List<String>>()

        override suspend fun embed(texts: List<String>): List<FloatArray> {
            if (batches.size >= failAfter) error("stub failure")
            batches += texts
            return texts.map { text ->
                FloatArray(vectorSize) { index -> (text.length + index).toFloat() }
            }
        }
    }

    private fun store(): EmbeddingVectorStore = EmbeddingVectorStore()

    @Test
    fun `second_call_with_same_texts_hits_cache_without_any_request`() = runBlocking {
        val store = store()
        val provider = RecordingProvider()
        val texts = listOf("查询", "事实一", "事实二")

        val first = store.embed(provider, "fp", texts)
        assertNotNull(first)
        assertEquals(1, provider.batches.size)
        assertEquals("首次必须把全部文本一起送出去", texts, provider.batches[0])

        val second = store.embed(provider, "fp", texts)
        assertNotNull(second)
        assertEquals("第二次不应再发请求", 1, provider.batches.size)
        assertEquals(first!!.size, second!!.size)
    }

    @Test
    fun `only_uncached_texts_are_requested`() = runBlocking {
        val store = store()
        val provider = RecordingProvider()
        val facts = listOf("事实一", "事实二", "事实三")

        store.embed(provider, "fp", facts)
        assertEquals(3, provider.batches[0].size)

        // 只有 query 是新的：事实应全部命中，请求里只剩一条。
        store.embed(provider, "fp", listOf("新的查询") + facts)
        assertEquals(2, provider.batches.size)
        assertEquals("请求里只应有未缓存的 query", listOf("新的查询"), provider.batches[1])
    }

    @Test
    fun `different_fingerprint_does_not_reuse_vectors`() = runBlocking {
        val store = store()
        val provider = RecordingProvider()

        store.embed(provider, "fp-openai-3-small", listOf("事实"))
        store.embed(provider, "fp-openai-3-large", listOf("事实"))

        assertEquals("换模型指纹后必须重新取向量", 2, provider.batches.size)
    }

    @Test
    fun `provider_failure_returns_null_and_does_not_poison_cache`() = runBlocking {
        val store = store()
        val failing = RecordingProvider(failAfter = 0)

        assertNull("取向量失败必须返回 null 让调用方回退", store.embed(failing, "fp", listOf("事实")))
        assertEquals(0, store.hits)

        // 换成正常 provider 后，同样的文本仍应真的去请求（说明失败没有写缓存）
        val healthy = RecordingProvider()
        val vectors = store.embed(healthy, "fp", listOf("事实"))
        assertNotNull(vectors)
        assertEquals(1, healthy.batches.size)
    }

    @Test
    fun `result_length_mismatch_returns_null`() = runBlocking {
        val store = store()
        val liar = object : EmbeddingProvider {
            override val id: String = "liar"
            override val displayName: String = "Liar"
            override val dimension: Int = 2
            override val modelName: String = "liar-model"

            override suspend fun embed(texts: List<String>): List<FloatArray> = texts.dropLast(1).map { floatArrayOf(1f, 2f) }
        }
        assertNull(
            "条数不匹配时必须整体失败，不能把错位的向量配给别的文本",
            store.embed(liar, "fp", listOf("a", "b")),
        )
    }

    @Test
    fun `cache_is_bounded_and_evicts_least_recently_used`() {
        val cache = EmbeddingVectorCache(maxEntries = 3)
        repeat(5) { index -> cache.put(cache.key("fp", "text-$index"), floatArrayOf(index.toFloat())) }
        assertEquals(3, cache.size())
        // 最久未用的 text-0 已被淘汰
        assertNull(cache.get(cache.key("fp", "text-0")))
        assertNotNull(cache.get(cache.key("fp", "text-4")))
    }

    @Test
    fun `key_is_stable_and_content_sensitive`() {
        val cache = EmbeddingVectorCache()
        assertEquals(cache.key("fp", "同一段文本"), cache.key("fp", "同一段文本"))
        assertTrue(cache.key("fp", "文本A") != cache.key("fp", "文本B"))
        assertTrue(cache.key("fp1", "同一段文本") != cache.key("fp2", "同一段文本"))
    }
}
