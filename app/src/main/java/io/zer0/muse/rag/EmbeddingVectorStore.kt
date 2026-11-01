package io.zer0.muse.rag

import io.zer0.common.resultOf

/**
 * 「带缓存的批量取向量」——把 [EmbeddingVectorCache] 与一次批量编码拼起来。
 *
 * 单独成类有两个原因：
 *  1. 这段逻辑只有三件事（查缓存 → 批量补缺失 → 按位置合并），与配置解析、
 *     Provider 构建无关，混在 [EmbeddingService] 里会把它推到函数数量阈值；
 *  2. 它能直接用替身 Provider 测 —— 不需要真的建一个云端 Provider。
 *
 * 语义约定：**缺一条就整体失败**（返回 null）。把错位的向量配给别的文本，
 * 只会表现为"AI 记错了事"，没有任何报错，不值得冒这个风险。
 */
internal class EmbeddingVectorStore(
    private val cache: EmbeddingVectorCache = EmbeddingVectorCache(),
) {

    /** 命中次数（诊断用）。 */
    val hits: Int get() = cache.hits

    /** 未命中次数，等于真正送去编码的文本条数（诊断用）。 */
    val misses: Int get() = cache.misses

    suspend fun embed(provider: EmbeddingProvider, fingerprint: String, texts: List<String>): List<FloatArray>? {
        if (texts.isEmpty()) return emptyList()
        val keys = texts.map { cache.key(fingerprint, it) }
        val results = arrayOfNulls<FloatArray>(texts.size)
        val missing = ArrayList<Int>()
        keys.forEachIndexed { index, key ->
            val hit = cache.get(key)
            if (hit != null) results[index] = hit else missing.add(index)
        }
        return if (missing.isEmpty()) {
            results.takeIfComplete()
        } else {
            fetchMissing(provider, texts, keys, results, missing)
        }
    }

    /**
     * 只查缓存，不触发编码、也不改命中统计。
     *
     * 给「有超时/熔断语义」的调用方使用（例如 RAG 的 query embedding）：
     * 命中缓存就不该再进网络超时窗口，未命中则由调用方自己决定怎么取、取不到怎么熔断。
     */
    fun peek(fingerprint: String, text: String): FloatArray? = cache.peek(cache.key(fingerprint, text))

    /** 单独写入一条向量（配合 [peek] 使用，调用方自己负责取数的超时与失败语义）。 */
    fun store(fingerprint: String, text: String, vector: FloatArray) {
        cache.put(cache.key(fingerprint, text), vector)
    }

    /** 只把未命中的文本送去编码，再按位置合并回 [results]。 */
    private suspend fun fetchMissing(
        provider: EmbeddingProvider,
        texts: List<String>,
        keys: List<String>,
        results: Array<FloatArray?>,
        missing: List<Int>,
    ): List<FloatArray>? {
        val fetched = resultOf { provider.embed(missing.map { texts[it] }) }.getOrNull()
        if (fetched == null || fetched.size != missing.size) return null
        missing.forEachIndexed { offset, index ->
            results[index] = fetched[offset]
            cache.put(keys[index], fetched[offset])
        }
        return results.takeIfComplete()
    }

    /** 全部位置都填上了才返回。 */
    private fun Array<FloatArray?>.takeIfComplete(): List<FloatArray>? {
        val collected = ArrayList<FloatArray>(size)
        for (item in this) collected += item ?: return null
        return collected
    }
}
