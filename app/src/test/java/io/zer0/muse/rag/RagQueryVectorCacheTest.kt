package io.zer0.muse.rag

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 向量缓存的「只查 / 只写」入口，以及 RAG 检索必须保留的超时+熔断语义。
 *
 * `RagService` 的 query embedding 与记忆重排的区别很关键：
 *  - 记忆重排：失败只是排序退化为词面顺序 → 可以静默返回 null；
 *  - RAG 检索：端点"接受连接但永不响应"必须由 `withTimeout` 打断，并由**异常**触发
 *    5 分钟熔断，否则用户在发消息关键路径上会等到 OkHttp 的 readTimeout。
 *
 * 所以缓存层只提供「查」与「写」两个独立动作，取数仍由调用方带着超时自己做。
 * 这组用例把这两点锁住，防止后来者"顺手"改成 embedWithCache 而悄悄去掉熔断。
 */
class RagQueryVectorCacheTest {

    @Test
    fun `peek_returns_null_before_store_and_hit_after`() {
        val store = EmbeddingVectorStore()
        assertNull("没写过的文本必须查不到", store.peek("fp", "问题"))
        store.store("fp", "问题", floatArrayOf(0.5f, 0.25f))
        assertNotNull("写入后必须能查到", store.peek("fp", "问题"))
    }

    @Test
    fun `peek_does_not_pollute_hit_miss_statistics`() {
        val store = EmbeddingVectorStore()
        store.peek("fp", "未命中")
        assertTrue("只查缓存不应计入未命中", store.misses == 0)
        assertTrue("只查缓存不应计入命中", store.hits == 0)
    }

    @Test
    fun `stored_vector_is_reused_by_batch_embed_without_extra_call`() = runBlockingTest {
        val store = EmbeddingVectorStore()
        var calls = 0
        val provider = object : EmbeddingProvider {
            override val id: String = "stub"
            override val displayName: String = "Stub"
            override val dimension: Int = 2
            override val modelName: String = "stub"
            override suspend fun embed(texts: List<String>): List<FloatArray> {
                calls += 1
                return texts.map { floatArrayOf(1f, 1f) }
            }
        }
        store.store("fp", "事实", floatArrayOf(0.1f, 0.2f))
        val vectors = store.embed(provider, "fp", listOf("事实"))
        assertNotNull(vectors)
        assertTrue("命中缓存后不应再调用 provider", calls == 0)
    }

    @Test
    fun `different_fingerprint_does_not_see_stored_vector`() {
        val store = EmbeddingVectorStore()
        store.store("fp-a", "问题", floatArrayOf(1f))
        assertNull("指纹不同必须视为未命中（换模型/换 Key）", store.peek("fp-b", "问题"))
    }

    // ── 接线契约 ──────────────────────────────────────────────

    private fun source(relative: String): String =
        File("src/main/java/io/zer0/muse/$relative").readText()

    @Test
    fun `rag_query_embedding_keeps_timeout_and_circuit_breaker`() {
        val src = source("rag/RagService.kt")
        assertTrue("必须先查缓存", src.contains("embeddingService.cachedVector(ragConfig, query)"))
        assertTrue("未命中仍要走 withTimeout", src.contains("withTimeout(EMBED_QUERY_TIMEOUT_MS) { provider.embed(query) }"))
        assertTrue(
            "熔断仍由异常触发",
            src.contains("embeddingFailureUntil = System.currentTimeMillis() + EMBEDDING_CIRCUIT_BREAKER_MS"),
        )
        assertTrue("取到后写回缓存", src.contains("embeddingService.rememberVector(ragConfig, query, queryVector)"))
        assertTrue(
            "不得改成 embedWithCache（它把异常吞成 null，会去掉熔断）",
            !src.contains("embedWithCache(ragConfig, listOf(query))"),
        )
    }
}

/** 与项目其它用例一致：需要协程体但不需要 TestDispatcher 的场景。 */
private fun runBlockingTest(block: suspend () -> Unit) = kotlinx.coroutines.runBlocking { block() }
