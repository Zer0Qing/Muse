package io.zer0.muse.perf

import android.graphics.Bitmap
import io.zer0.muse.ui.markdown.MarkdownBlock
import io.zer0.muse.ui.markdown.parseMarkdown

/**
 * 进程级渲染结果缓存。
 *
 * 为什么需要:聊天列表的 LazyColumn 滚出屏幕会销毁 item 的 composition,
 * 挂在 composition 生命周期上的 remember 缓存随之失联;滚回来时 Markdown 全量解析、
 * data-URI 图片 base64 解码都要从零重算 —— 这是滚动卡顿的直接来源。
 * 缓存挂在这里(进程内、与 composition 无关),滚出再滚回直接命中。
 */

/** 初始桶容量与装填因子,LinkedHashMap 默认值,不需要按业务调整。 */
private const val INITIAL_CAPACITY = 16
private const val LOAD_FACTOR = 0.75f

/**
 * 有上限的 LRU 缓存(线程安全)。
 *
 * @param maxSize 容量上限,单位由 [sizeOf] 决定(条数或字节数)
 * @param sizeOf 单条记录的容量占用;返回 0 的条目不会单独撑爆容量
 *
 * 语义:`accessOrder = true` 的 LinkedHashMap,最后写入/读取的条目在链表尾部,
 * 淘汰总是从头部(最久未使用)取。读写都要过 synchronized —— 渲染在主线程,
 * 解码在 IO 线程,两侧都会碰缓存。
 */
internal class BoundedLruCache<K : Any, V : Any>(
    private val maxSize: Int,
    private val sizeOf: (V) -> Int,
) {
    private val entries = LinkedHashMap<K, V>(INITIAL_CAPACITY, LOAD_FACTOR, true)
    private var currentSize = 0

    @Synchronized
    fun get(key: K): V? = entries[key]

    @Synchronized
    fun put(key: K, value: V) {
        val previous = entries.put(key, value)
        if (previous != null) currentSize -= sizeOf(previous)
        currentSize += sizeOf(value)
        trim()
    }

    @Synchronized
    fun containsKey(key: K): Boolean = entries.containsKey(key)

    @Synchronized
    fun size(): Int = entries.size

    @Synchronized
    fun totalSize(): Int = currentSize

    @Synchronized
    fun clear() {
        entries.clear()
        currentSize = 0
    }

    /** 从最久未使用的一端淘汰,直到容量回到上限内。 */
    private fun trim() {
        while (currentSize > maxSize && entries.isNotEmpty()) {
            val eldestKey = entries.keys.first()
            val removed = entries.remove(eldestKey) ?: continue
            currentSize -= sizeOf(removed)
        }
    }
}

/**
 * 命中则返回缓存值,未命中才调用 [decode] 并把结果写回缓存。
 *
 * 抽成独立函数是为了让「命中不重算」这条性质可以脱离 Android 位图直接验证。
 */
internal fun <T : Any> cachedDecode(
    cache: BoundedLruCache<String, T>,
    key: String,
    decode: () -> T?,
): T? {
    cache.get(key)?.let { return it }
    val decoded = decode() ?: return null
    cache.put(key, decoded)
    return decoded
}

/** Markdown 解析结果缓存条数上限:约等于「一屏两层 + 前后各一屏」的消息量。 */
internal const val MARKDOWN_CACHE_MAX_ENTRIES = 128

/**
 * 超过这个字符数的正文不进缓存。
 *
 * 单条解析结果的占用随块数增长,塞几条万字消息就会把 LRU 挤空,反而降低命中率;
 * 这类超长消息本身在列表里也很少,重算代价可以接受。
 */
internal const val MARKDOWN_CACHE_MAX_CHARS = 20_000

/**
 * 非流式 Markdown 的进程级解析缓存。
 *
 * key = 消息 id + 内容 hash([MessagePaginator.contentCacheKey]),
 * 拿不到消息 id 时退化为纯内容 hash(key 里带 `content#` 前缀做区分)。
 * 解析结果只由 content 决定,消息 id 只用来避免不同消息间无谓的互相淘汰,
 * 因此 id 缺失或重复都不会返回错误结果,最坏情况是命中率下降。
 */
internal object MarkdownParseCache {
    private val cache = BoundedLruCache<String, List<MarkdownBlock>>(MARKDOWN_CACHE_MAX_ENTRIES) { 1 }

    /**
     * 取缓存,未命中则用 [parse] 解析并写回。
     *
     * @param messageId 消息 id(聊天场景传消息 id;无 id 场景传 null)
     * @param content 待解析正文
     * @param parse 解析函数,默认全量解析
     */
    fun getOrParse(
        messageId: String?,
        content: String,
        parse: (String) -> List<MarkdownBlock> = ::parseMarkdown,
    ): List<MarkdownBlock> {
        val key = keyOf(messageId, content)
        cache.get(key)?.let { return it }
        val parsed = parse(content)
        if (content.length <= MARKDOWN_CACHE_MAX_CHARS) cache.put(key, parsed)
        return parsed
    }

    /** 计算缓存 key;测试与调用方共用同一套算法,避免两边漂移。 */
    fun keyOf(messageId: String?, content: String): String =
        if (messageId.isNullOrBlank()) {
            "content#${content.hashCode()}"
        } else {
            MessagePaginator.contentCacheKey(messageId, content)
        }

    fun containsKey(key: String): Boolean = cache.containsKey(key)

    fun size(): Int = cache.size()

    fun clear() = cache.clear()
}

/** 位图缓存按堆大小自适应:堆越宽裕留得越多,但不超过 [IMAGE_CACHE_MAX_BYTES_CEILING]。 */
private const val IMAGE_CACHE_MAX_BYTES_CEILING = 48 * 1024 * 1024
private const val IMAGE_CACHE_HEAP_FRACTION = 8

/**
 * data-URI 图片的解码结果缓存(进程级,按字节计上限)。
 *
 * 回收策略:缓存持有的位图**不**主动 recycle。原先 produceState 退出时 recycle,
 * 是因为那时位图的生命周期完全绑在 composition 上;现在缓存可能仍持有同一对象,
 * recycle 后再用会抛 "Canvas: trying to use a recycled bitmap"。位图改由 GC 回收,
 * 容量由本缓存的字节上限控制,不会无限增长。
 *
 * 不复用 Coil 的 MemoryCache:data-URI 走的是 BitmapFactory 直接解码(绕开 Coil 的
 * DataUriFetcher,它在部分设备上静默失败),而 Coil 2 的 MemoryCache 只对 ImageLoader
 * 内部 pipeline 开放写入,没有对外插入任意位图的入口;硬接进去会多一层适配且踩到
 * 版本内部实现,不如自己拿字节上限兜住。
 */
internal object DataUriImageCache {
    private val bitmaps = BoundedLruCache<String, Bitmap>(maxBytes()) { it.byteCount.coerceAtLeast(1) }

    private fun maxBytes(): Int = minOf(
        IMAGE_CACHE_MAX_BYTES_CEILING,
        (Runtime.getRuntime().maxMemory() / IMAGE_CACHE_HEAP_FRACTION).toInt(),
    )

    /**
     * 图片缓存 key:整条 data-URI 的长度 + hash。
     *
     * 不用 base64 段做 key,是为了让调用方不必为了算 key 再切一次几 MB 的字符串;
     * hashCode 会被 String 内部缓存,同一实例只算一次。
     */
    fun keyOf(dataUri: String): String = "${dataUri.length}:${dataUri.hashCode()}"

    fun get(key: String): Bitmap? = bitmaps.get(key)

    /** 命中直接返回,未命中才解码;解码结果写回缓存供下次组合直接复用。 */
    fun decodeOrGet(key: String, decode: () -> Bitmap?): Bitmap? = cachedDecode(bitmaps, key, decode)

    fun size(): Int = bitmaps.size()

    fun clear() = bitmaps.clear()
}
