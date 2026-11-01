package io.zer0.muse.rag

import java.security.MessageDigest

/**
 * embedding 向量缓存（进程级 LRU）。
 *
 * ## 为什么需要它
 *
 * 记忆相关性的「向量重排」每轮都要调一次 `provider.embed(query + 所有候选事实)`。
 * 云端源下这是一次 HTTPS 往返，而**候选事实在轮次之间几乎不变** —— 每轮都把它们
 * 重新编码一遍，等于把同样的输入反复上传、让服务端重复算同一批向量。
 *
 * 缓存后典型情况变成：命中 N 条事实向量，只对 query 发一次请求；用户重试/重新生成
 * 同一句话时，连 query 也命中，一次网络调用都不发。
 *
 * ## 键与失效
 *
 * 键 = Provider 指纹 + 文本内容摘要。指纹里包含 apiKey/baseUrl/模型，所以换模型或
 * 换 key 之后旧向量自然不再命中（不会拿另一套模型的向量去算余弦）。
 *
 * 用 SHA-256 前 16 字节而不是 `String.hashCode()`：向量算错会导致注入的记忆排序错，
 * 这类错误没有任何报错，只能表现为"AI 好像记错了事"，不值得为省几个字节冒碰撞风险。
 *
 * 容量按条数封顶，超出即淘汰最久未用的（[LinkedHashMap] 访问序）。
 */
internal class EmbeddingVectorCache(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {

    private val entries = object : LinkedHashMap<String, FloatArray>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>): Boolean = size > maxEntries
    }

    /** 命中次数（诊断用）。 */
    var hits: Int = 0
        private set

    /** 未命中次数（诊断用）。 */
    var misses: Int = 0
        private set

    fun key(fingerprint: String, text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        val hex = digest.take(DIGEST_BYTES).joinToString(separator = "") { byte -> "%02x".format(byte) }
        return "$fingerprint|$hex"
    }

    fun get(key: String): FloatArray? = synchronized(entries) {
        val hit = entries[key]
        if (hit == null) misses += 1 else hits += 1
        hit
    }

    /** 只读缓存，不计命中/未命中统计（调用方拿它做"命不命中都合法"的前置判断）。 */
    fun peek(key: String): FloatArray? = synchronized(entries) { entries[key] }

    fun put(key: String, vector: FloatArray) {
        synchronized(entries) { entries[key] = vector }
    }

    fun size(): Int = synchronized(entries) { entries.size }

    fun clear() = synchronized(entries) { entries.clear() }

    companion object {
        private const val DIGEST_BYTES = 16
        private const val DEFAULT_MAX_ENTRIES = 512
    }
}
