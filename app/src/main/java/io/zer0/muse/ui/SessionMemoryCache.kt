package io.zer0.muse.ui

import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import java.util.LinkedHashMap

/**
 * 会话消息内存 LRU 缓存。
 *
 * 背景:v1.93 把 [ChatViewModel] 改为 Koin single 单例后,会话消息列表永不释放,
 * 长时间使用会累积较高内存占用。本缓存以 LRU(最近最少使用)策略保留最近 N 个会话
 * 的消息内存副本,超出上限后自动驱逐最久未访问的会话,被驱逐的会话再次切换进入时
 * 从 DB 重新加载。
 *
 * 设计要点:
 * - 基于 [LinkedHashMap](accessOrder = true) + [removeEldestEntry] 实现访问序 LRU:
 *   每次 [get]/[put] 命中的条目会被移到链表尾部,链表头部即为最久未访问的条目(eldest)。
 * - 通过 [Synchronized] 保证线程安全:消息列表在流式输出、切页、加载更多等多线程
 *   并发场景下会被读写,必须加锁。
 * - [evictListener]:条目被驱逐时回调,默认记录 [Logger.d] 便于调试与性能追踪。
 *
 * 数据安全性:缓存的是会话消息列表的内存快照(引用),流式输出已同步落库
 * (见 [ChatViewModel] 内 sessionRepository.appendMessage/upsertMessage 调用),
 * 因此即便缓存条目被驱逐或失效,数据也不会丢失,只是下次进入时多一次 DB 查询。
 *
 * 注:存储的是 [List] 引用而非拷贝,依赖 [ChatViewModel] 内对 messages 的
 * "复制即更新"模式(每次修改都通过 _state.update { it.copy(messages = ...) } 产生新列表),
 * 不会就地修改已缓存的列表,因此引用共享是安全的。
 */
class SessionMemoryCache(
    private val maxSize: Int = MAX_CACHE_SIZE,
) {
    companion object {
        /**
         * 默认 LRU 缓存上限(保留最近 5 个会话的消息内存副本)。
         *
         * TODO: 后续通过 [io.zer0.muse.data.SettingsRepository] 暴露为高级设置项,
         *       支持用户自定义(需同时扩展 ChatSettingsPage UI)。当前先以常量形式提供,
         *       避免一次性改动过大。
         */
        const val MAX_CACHE_SIZE = 5

        private const val TAG = "SessionCache"
    }

    /**
     * 访问序 LRU 底层 Map。
     *
     * accessOrder = true 时,[get] 与 [put] 会把命中条目移到链表尾部,
     * [removeEldestEntry] 在 [put] 后触发,返回 true 则驱逐链表头部(最久未访问)条目。
     */
    private val cache = object : LinkedHashMap<String, List<UIMessage>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, List<UIMessage>>): Boolean {
            // 注意:此处 size 是插入新条目后的容量,超过 maxSize 才驱逐
            val shouldEvict = size > maxSize
            if (shouldEvict) {
                evictListener?.invoke(eldest.key, eldest.value)
            }
            return shouldEvict
        }
    }

    /**
     * 条目被驱逐时的回调(在 [put] 触发 [removeEldestEntry] 时同步调用)。
     *
     * 默认实现记录 [Logger.d]。注意:回调在 [Synchronized] 锁内执行,
     * 实现内请勿再回调本缓存的同步方法(JVM synchronized 可重入,但为清晰起见避免嵌套)。
     */
    var evictListener: ((sessionId: String, messages: List<UIMessage>) -> Unit)? = null

    init {
        // 默认驱逐监听器:记录被驱逐会话的 id 与消息数,便于调试内存治理效果。
        // 注:removeEldestEntry 触发时 eldest 尚未真正移除,故 cache.size 含被驱逐条目,
        // 驱逐后实际大小为 size - 1。
        evictListener = { sessionId, messages ->
            Logger.d(TAG, "LRU 驱逐会话: id=$sessionId, 消息数=${messages.size}, 驱逐后缓存大小=${cache.size - 1}")
        }
    }

    /**
     * 写入或更新会话消息快照。
     *
     * 已存在则刷新值并提升为最近访问;新条目若导致超限则驱逐最久未访问的条目。
     */
    @Synchronized
    fun put(sessionId: String, messages: List<UIMessage>) {
        cache[sessionId] = messages
    }

    /**
     * 读取会话消息快照。命中则提升为最近访问(LRU 语义),未命中返回 null。
     */
    @Synchronized
    fun get(sessionId: String): List<UIMessage>? = cache[sessionId]

    /**
     * 判断是否缓存了指定会话。
     *
     * 注意:[containsKey] 不会改变访问序(不提升为最近访问),仅作存在性判断。
     */
    @Synchronized
    fun contains(sessionId: String): Boolean = cache.containsKey(sessionId)

    /**
     * 移除指定会话的缓存条目(会话被删除/归档时调用)。
     */
    @Synchronized
    fun remove(sessionId: String) {
        cache.remove(sessionId)
    }

    /**
     * 清空全部缓存条目。
     */
    @Synchronized
    fun clear() {
        cache.clear()
    }

    /**
     * 当前缓存条目数(调试/监控用)。
     */
    @Synchronized
    fun size(): Int = cache.size
}
