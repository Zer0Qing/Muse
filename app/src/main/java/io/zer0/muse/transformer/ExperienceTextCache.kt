package io.zer0.muse.transformer

/**
 * 经验条目的小写文本缓存。
 *
 * 经验库段每轮都要参与「按当前问题相关性排序」，而打分的做法是把
 * `title + " " + content` 整段小写成一份副本再做子串扫描。经验条数是用户积累出来的，
 * 内容也可能很长 —— 每轮为每条经验新分配一份完整小写副本，是纯重复劳动。
 *
 * 键用 `id + updatedAt`：经验被编辑后 `updatedAt` 必变，旧副本自然失效，
 * 不需要监听写入事件。容量按条数封顶，超出淘汰最久未用的。
 *
 * 只缓存小写副本本身（不缓存打分结果）：打分依赖当前问题，问题每轮都不同。
 */
internal class ExperienceTextCache(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {

    private val entries = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean = size > maxEntries
    }

    /** 命中次数（诊断用）。 */
    var hits: Int = 0
        private set

    /** 未命中次数（诊断用）。 */
    var misses: Int = 0
        private set

    fun key(id: String, updatedAt: Long): String = "$id@$updatedAt"

    fun get(key: String): String? = synchronized(entries) {
        val hit = entries[key]
        if (hit == null) misses += 1 else hits += 1
        hit
    }

    fun put(key: String, lowered: String) {
        synchronized(entries) { entries[key] = lowered }
    }

    fun size(): Int = synchronized(entries) { entries.size }

    fun clear() = synchronized(entries) { entries.clear() }

    companion object {
        private const val DEFAULT_MAX_ENTRIES = 256
    }
}
