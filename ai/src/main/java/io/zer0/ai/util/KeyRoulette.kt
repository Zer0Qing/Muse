package io.zer0.ai.util

import io.zer0.common.Logger
import java.util.concurrent.ConcurrentHashMap

/**
 * API Key 轮换器。
 *
 * 支持每个 Provider 配置多个 API key(逗号或换行分隔)，按最近使用记录分摊负载：
 * 优先选择未使用过的 key，全部用过时选择最久未使用的 key。
 *
 * v1.0.1 增强：
 *  - [pickNext]: 429 限流时主动切换到下一个 key(跳过当前 key)
 *  - 临时黑名单：被 pickNext 跳过的 key 在 [BLOCK_TTL_MS] 内不再优先选择，
 *    避免限流的 key 被反复选中；TTL 过期后自动恢复候选
 *  - [markFailed]: 显式标记某个 key 失败(如 401 鉴权失败)，TTL 内完全排除
 *
 * 用法：
 * ```kotlin
 * val keyRoulette = KeyRoulette()
 * // 首次选 key
 * val key = keyRoulette.pick("provider-123", "key1,key2,key3")
 * // 429 限流时切换到下一个 key
 * val nextKey = keyRoulette.pickNext("provider-123", "key1,key2,key3", currentKey = key)
 * ```
 */
class KeyRoulette {

    companion object {
        private const val TAG = "KeyRoulette"
        private const val MAX_RECORDS_PER_PROVIDER = 100
        private const val USAGE_EXPIRY_MS = 24 * 60 * 60 * 1000L // 24 小时
        /** v1.0.1: 限流 key 的临时黑名单 TTL(60 秒,与多数 Provider 的 Retry-After 推荐值一致)。 */
        private const val BLOCK_TTL_MS = 60 * 1000L
    }

    private data class UsageRecord(
        val key: String,
        val lastUsedAt: Long,
    )

    /** v1.0.1: 黑名单条目(key → 过期时间戳)。 */
    private data class BlockRecord(
        val key: String,
        val expiresAt: Long,
        /** 是否完全排除(true=完全排除,如 401;false=降优先级,如 429)。 */
        val hardBlock: Boolean,
    )

    /** providerId → 最近使用记录（最旧的在前） */
    private val usage = ConcurrentHashMap<String, MutableList<UsageRecord>>()

    /** v1.0.1: providerId → 黑名单条目 */
    private val blocks = ConcurrentHashMap<String, MutableList<BlockRecord>>()

    /**
     * 从逗号/换行分隔的 key 字符串中选取一个 key。
     * 优先选择最近未使用的 key。
     *
     * @param providerId Provider 的唯一标识
     * @param keysString 逗号或换行分隔的 API key
     * @return 选中的 key；若只有一个 key 则返回原始字符串
     */
    fun pick(providerId: String, keysString: String): String {
        val keys = parseKeys(keysString)
        if (keys.size <= 1) return keysString.trim()

        val now = System.currentTimeMillis()
        val records = usage.getOrPut(providerId) { mutableListOf() }
        val blocksForProvider = blocks.getOrPut(providerId) { mutableListOf() }

        // 清理过期黑名单
        synchronized(blocksForProvider) {
            blocksForProvider.removeAll { it.expiresAt < now }
        }

        // 优先选未在硬黑名单中的 key
        val hardBlocked = blocksForProvider.filter { it.hardBlock }.map { it.key }.toSet()
        val candidates = keys.filter { it !in hardBlocked }

        // 所有 key 都在硬黑名单 → 退化为返回第一个(让 Provider 自己报错)
        if (candidates.isEmpty()) {
            Logger.w(TAG, "pick: all keys hard-blocked for $providerId, returning first key")
            return keys.first()
        }

        val selected = selectKey(candidates, records, now, blocksForProvider)
        recordUsage(records, selected, now)
        return selected
    }

    /**
     * v1.0.1: 429 限流时主动切换到下一个 key。
     *
     * 把 [currentKey] 加入软黑名单(60s 内降优先级),然后 pick 下一个 key。
     * 如果只有一个 key,返回原 key(无法切换,由 Provider 走指数退避)。
     *
     * @param providerId Provider 标识
     * @param keysString 全部 key 字符串
     * @param currentKey 当前触发 429 的 key
     * @return 下一个 key(可能与 currentKey 相同,如果只有一个 key)
     */
    fun pickNext(providerId: String, keysString: String, currentKey: String): String {
        val keys = parseKeys(keysString)
        if (keys.size <= 1) return currentKey

        val now = System.currentTimeMillis()
        val blocksForProvider = blocks.getOrPut(providerId) { mutableListOf() }

        // 把当前 key 加入软黑名单(60s)
        synchronized(blocksForProvider) {
            blocksForProvider.removeAll { it.key == currentKey }
            blocksForProvider.add(BlockRecord(
                key = currentKey,
                expiresAt = now + BLOCK_TTL_MS,
                hardBlock = false,
            ))
            // 防止黑名单膨胀
            while (blocksForProvider.size > MAX_RECORDS_PER_PROVIDER) blocksForProvider.removeAt(0)
        }

        Logger.i(TAG, "pickNext: 429 限流,把 key=${maskKey(currentKey)} 加入软黑名单 60s,切换到下一个 key")

        // 重新 pick(会跳过软黑名单中的 key,除非没有其他选择)
        return pick(providerId, keysString)
    }

    /**
     * v1.0.1: 显式标记某个 key 失败(如 401 鉴权失败、key 已失效)。
     *
     * 与 [pickNext] 的区别:
     *  - [markFailed] 默认 hardBlock=true,完全排除该 key(直到 TTL 过期)
     *  - [pickNext] 是软黑名单,仅在有多 key 时降优先级
     *
     * @param providerId Provider 标识
     * @param key 失败的 key
     * @param hardBlock true=完全排除(默认,如 401),false=降优先级(如 429)
     * @param ttlMs 黑名单 TTL,默认 5 分钟(hardBlock 场景比 429 更长)
     */
    fun markFailed(
        providerId: String,
        key: String,
        hardBlock: Boolean = true,
        ttlMs: Long = 5 * 60 * 1000L,
    ) {
        val blocksForProvider = blocks.getOrPut(providerId) { mutableListOf() }
        val now = System.currentTimeMillis()
        synchronized(blocksForProvider) {
            blocksForProvider.removeAll { it.key == key }
            blocksForProvider.add(BlockRecord(
                key = key,
                expiresAt = now + ttlMs,
                hardBlock = hardBlock,
            ))
            while (blocksForProvider.size > MAX_RECORDS_PER_PROVIDER) blocksForProvider.removeAt(0)
        }
        Logger.w(TAG, "markFailed: key=${maskKey(key)} 标记为失败(hardBlock=$hardBlock, ttl=${ttlMs}ms)")
    }

    /** 清除所有缓存的 key 使用数据。 */
    fun clear() {
        usage.clear()
        blocks.clear()
    }

    /**
     * v1.0.1: 清除指定 Provider 的黑名单(用于"重试"按钮或配置变更后)。
     */
    fun clearBlacklist(providerId: String) {
        blocks.remove(providerId)
    }

    // ---------- 内部方法 ----------

    private fun parseKeys(keysString: String): List<String> {
        return keysString.split(",", "\n", "\r\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private fun selectKey(
        candidates: List<String>,
        records: MutableList<UsageRecord>,
        now: Long,
        blocksForProvider: MutableList<BlockRecord>,
    ): String {
        // 软黑名单中的 key 降优先级(放最后选)
        val softBlocked = blocksForProvider.filter { !it.hardBlock }.map { it.key }.toSet()
        val unblocked = candidates.filter { it !in softBlocked }

        if (unblocked.isNotEmpty()) {
            return lruPick(unblocked, records, now)
        }

        // 所有候选都在软黑名单(罕见,如所有 key 都刚限流),退化为 LRU
        return lruPick(candidates, records, now)
    }

    private fun lruPick(candidates: List<String>, records: MutableList<UsageRecord>, now: Long): String {
        // 审计修复 (3.1): 读操作与 recordUsage 的 synchronized(records) 互斥,
        // 避免并发请求时遍历期间被 removeAll/add 结构性修改而抛 ConcurrentModificationException
        val usedKeys = synchronized(records) {
            records
                .filter { now - it.lastUsedAt < USAGE_EXPIRY_MS }
                .map { it.key }
                .toSet()
        }

        val unusedKeys = candidates.filter { it !in usedKeys }
        return if (unusedKeys.isNotEmpty()) {
            unusedKeys.random()
        } else {
            // 所有 key 最近都使用过，在候选中选最久未用的
            val oldest = synchronized(records) {
                records.filter { it.key in candidates }.minByOrNull { it.lastUsedAt }
            }
            oldest?.key?.takeIf { it in candidates } ?: candidates.random()
        }
    }

    private fun recordUsage(records: MutableList<UsageRecord>, selected: String, now: Long) {
        synchronized(records) {
            records.removeAll { it.key == selected }
            records.add(UsageRecord(selected, now))
            while (records.size > MAX_RECORDS_PER_PROVIDER) {
                records.removeAt(0)
            }
        }
    }

    private fun maskKey(key: String): String {
        return when {
            key.length <= 12 && key.isNotEmpty() -> "****" + key.takeLast(2)
            key.length > 12 -> key.take(4) + "***" + key.takeLast(4)
            else -> ""
        }
    }
}
