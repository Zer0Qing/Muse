package io.zer0.muse.rag

import io.zer0.muse.rag.VectorSearchService.ChunkWithDoc
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * 向量缓存：交替的检索条件不得互相打穿。
 *
 * 背景：库里 ≤2000 chunk 时会把向量整表载入并缓存。旧实现只留**一份**，键随时变；
 * 而实际使用中最常见的是「不带过滤（主对话走全库）」与「某个固定过滤（@mention / 定向）」
 * 交替出现 —— 单条目缓存会在这两者之间来回整表重载。
 *
 * 这里用两种过滤条件交替，断言：
 *  - A → B → A 之后，A 不应再次整表载入；
 *  - 条目上限生效（不会无界增长成内存黑洞）。
 *
 * 注意：定向检索（docIds）在注入 `chunkPageByDocIdsProvider` 时走分页路径、根本不进缓存，
 * 所以这里用 metadata 过滤（生产未注入 metadata provider，正会落到缓存路径）。
 */
class VectorSearchCacheTest {

    private val dim = 4

    private fun chunk(index: Int, tag: String) = ChunkWithDoc(
        chunkId = "c-$index",
        docId = "doc-1",
        docTitle = "t",
        content = "content-$index",
        embedding = "",
        embeddingBlob = VectorSearchService.floatArrayToBlob(FloatArray(dim) { 0.5f }),
        chunkIndex = index,
        metadataJson = """{"tag":"$tag"}""",
        createdAt = 0L,
    )

    private class CountingProvider(val chunks: List<ChunkWithDoc>) {
        val pageCalls = AtomicInteger(0)
        val rowsLoaded = AtomicInteger(0)

        suspend fun page(limit: Int, offset: Int): List<ChunkWithDoc> {
            pageCalls.incrementAndGet()
            val page = chunks.drop(offset).take(limit)
            rowsLoaded.addAndGet(page.size)
            return page
        }
    }

    private fun service(provider: CountingProvider) = VectorSearchService(
        chunkPageProvider = { limit, offset -> provider.page(limit, offset) },
        chunkCountProvider = { provider.chunks.size },
    )

    private fun filterOf(tag: String): VectorSearchService.MetadataFilter = VectorSearchService.MetadataFilter(tag = tag)

    @Test
    fun `alternating_filters_do_not_thrash_the_cache`() = runBlocking {
        val provider = CountingProvider(List(20) { chunk(it, if (it % 2 == 0) "a" else "b") })
        val service = service(provider)
        val query = FloatArray(dim) { 0.5f }

        service.search(query, topK = 3, threshold = -1f, metadataFilter = filterOf("a"))
        val afterFirst = provider.rowsLoaded.get()
        assertTrue("首次必须整表载入", afterFirst >= 20)

        service.search(query, topK = 3, threshold = -1f, metadataFilter = filterOf("b"))
        val afterSecond = provider.rowsLoaded.get()
        assertTrue("换一个过滤条件需要另载一次（缓存只按条件区分）", afterSecond > afterFirst)

        service.search(query, topK = 3, threshold = -1f, metadataFilter = filterOf("a"))
        assertEquals(
            "A → B → A 之后 A 必须命中缓存，不得再整表载入",
            afterSecond,
            provider.rowsLoaded.get(),
        )
    }

    @Test
    fun `repeated_same_filter_hits_cache`() = runBlocking {
        val provider = CountingProvider(List(20) { chunk(it, "a") })
        val service = service(provider)
        val query = FloatArray(dim) { 0.5f }

        service.search(query, topK = 3, threshold = -1f, metadataFilter = filterOf("a"))
        val rows = provider.rowsLoaded.get()
        service.search(query, topK = 3, threshold = -1f, metadataFilter = filterOf("a"))
        assertEquals("同一条件重复查询不得重载", rows, provider.rowsLoaded.get())
    }

    @Test
    fun `cache_entry_count_is_bounded`() = runBlocking {
        val provider = CountingProvider(List(20) { chunk(it, "a") })
        val service = service(provider)
        val query = FloatArray(dim) { 0.5f }

        listOf("a", "b", "c", "d").forEach { tag ->
            service.search(query, topK = 3, threshold = -1f, metadataFilter = filterOf(tag))
        }
        // 上限 2：再回到最早的 "a" 必然需要重载（证明没有无界增长）
        val before = provider.rowsLoaded.get()
        service.search(query, topK = 3, threshold = -1f, metadataFilter = filterOf("a"))
        assertTrue("超出上限后最旧条目应被淘汰", provider.rowsLoaded.get() > before)
        assertTrue("条目上限应为 2", VectorSearchService.MAX_CACHE_ENTRIES == 2)
    }

    @Test
    fun `invalidate_cache_forces_reload`() = runBlocking {
        val provider = CountingProvider(List(20) { chunk(it, "a") })
        val service = service(provider)
        val query = FloatArray(dim) { 0.5f }

        service.search(query, topK = 3, threshold = -1f, metadataFilter = filterOf("a"))
        val rows = provider.rowsLoaded.get()
        service.invalidateCache()
        service.search(query, topK = 3, threshold = -1f, metadataFilter = filterOf("a"))
        assertTrue("失效后必须重新载入", provider.rowsLoaded.get() > rows)
    }
}
