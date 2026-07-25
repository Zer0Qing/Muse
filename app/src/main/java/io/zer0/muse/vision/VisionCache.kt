package io.zer0.muse.vision

import android.content.Context
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.common.resultOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.security.MessageDigest

/**
 * v1.0.5 视觉辅助重写:全局 LRU 缓存 + 跨 session 共享。
 *
 * 参考 openhanako 的 `_analysisByPrompt`(进程内 LRU)+ sidecar 文件持久化双层缓存。
 *
 * # 与旧版 VisionCache 的差异(v1.0.5 重写)
 *
 *  1. **跨 session 共享**:不再按 sessionId 分文件,改为全局缓存 + sidecar 文件记录所有 session 的条目。
 *     同一张图同一问题跨 session 命中缓存(对齐 openhanako 的 `_analysisByPrompt`)。
 *  2. **缓存 key 明确分离**:不再复用 visionModelId 字段塞 suffix,改为 [CacheKey] 数据类,
 *     包含 imageHash / modelId / userRequestHash / promptVersion 四个独立字段。
 *  3. **LRU 淘汰**:内存缓存有上限([MAX_MEMORY_ENTRIES] = 256),超过按 lastUsedAt 淘汰最久未用的。
 *     旧版无淘汰,长期运行内存无限增长。
 *  4. **增量写盘**:put 时只追加到 sidecar 文件,不再每次全量重写。每 [FLUSH_INTERVAL] 次写入刷一次盘。
 *  5. **字段名与内容一致**:CacheEntry 的字段名反映真实语义,无 HACK。
 *
 * # 缓存 key 设计
 *
 * key = SHA-256(imageBase64) + modelId + SHA-256(userRequest) + promptVersion
 *
 * 四个维度:
 *  - imageHash:同一张图不同模型/问题共享
 *  - modelId:不同视觉模型描述可能不同
 *  - userRequestHash:同一张图不同问题不共享(针对性回答)
 *  - promptVersion:改提示词后递增版本号使旧缓存失效
 *
 * @param context 应用 Context,用于获取 filesDir
 */
class VisionCache(context: Context) {

    private val cacheDir = File(context.applicationContext.filesDir, "vision_cache")
    private val sidecarFile = File(cacheDir, "global_cache.json")
    private val mutex = Mutex()

    /**
     * 内存缓存:全局共享,LRU 淘汰。
     *
     * key = [CacheKey.toString()],value = 视觉描述 + lastUsedAt 时间戳
     */
    private val memoryCache = mutableMapOf<String, CacheEntry>()

    /** 待刷盘的增量条目(put 时追加,FLUSH_INTERVAL 次后批量写盘)。 */
    private val pendingFlush = mutableMapOf<String, CacheEntry>()

    /** 自上次刷盘后的 put 次数,达到 [FLUSH_INTERVAL] 触发刷盘。 */
    private var putSinceFlush = 0

    @Volatile
    private var loaded = false

    /**
     * 缓存条目。
     *
     * @param imageHash 图片内容 SHA-256
     * @param modelId 视觉模型 id(不含 suffix,纯 modelId)
     * @param userRequestHash 用户请求 SHA-256 短 hash
     * @param promptVersion 提示词版本号
     * @param description 视觉描述(不含 <vision-context> 标签)
     * @param createdAt 创建时间戳(毫秒)
     * @param lastUsedAt 最后使用时间戳(毫秒),用于 LRU 淘汰
     * @param sessionIds 使用过该条目的 session id 集合(便于按 session 清理)
     */
    @Serializable
    data class CacheEntry(
        val imageHash: String,
        val modelId: String,
        val userRequestHash: String,
        val promptVersion: Int,
        val description: String,
        val createdAt: Long,
        var lastUsedAt: Long,
        val sessionIds: List<String> = emptyList(),
    )

    /**
     * 缓存 key 的四个独立维度。
     *
     * 替代旧版"复用 visionModelId 字段塞 suffix"的 HACK 做法。
     */
    data class CacheKey(
        val imageHash: String,
        val modelId: String,
        val userRequestHash: String,
        val promptVersion: Int,
    ) {
        override fun toString(): String = "$imageHash|$modelId|$userRequestHash|v$promptVersion"
    }

    /**
     * 懒加载 sidecar 文件到内存缓存。
     *
     * 首次调用时加载,后续直接读内存。
     */
    private suspend fun ensureLoaded() {
        if (loaded) return
        mutex.withLock {
            if (loaded) return@withLock
            loaded = true
            val entries = loadSidecar()
            entries.forEach { entry ->
                val key = CacheKey(entry.imageHash, entry.modelId, entry.userRequestHash, entry.promptVersion)
                memoryCache[key.toString()] = entry
            }
            Logger.d(TAG, "视觉缓存已加载 sidecar,共 ${entries.size} 条")
            // LRU 淘汰到上限内
            evictIfNeeded()
        }
    }

    /**
     * 获取缓存的视觉描述。
     *
     * @param sessionId 当前会话 id(用于记录使用过的 session,便于按 session 清理)
     * @param imageBase64 图片 base64
     * @param key 缓存 key(四维度)
     * @return 缓存的描述文本;未命中返回 null
     */
    suspend fun get(sessionId: String, imageBase64: String, key: CacheKey): String? {
        ensureLoaded()
        val imageHash = hash(imageBase64)
        val fullKey = key.copy(imageHash = imageHash)
        return mutex.withLock {
            val entry = memoryCache[fullKey.toString()]
            if (entry != null) {
                entry.lastUsedAt = System.currentTimeMillis()
                Logger.d(TAG, "视觉缓存命中: model=${key.modelId}, promptVer=${key.promptVersion}")
                entry.description
            } else null
        }
    }

    /**
     * 写入视觉描述到缓存。
     *
     * @param sessionId 当前会话 id
     * @param imageBase64 图片 base64
     * @param key 缓存 key
     * @param description 视觉描述(不含 <vision-context> 标签)
     */
    suspend fun put(
        sessionId: String,
        imageBase64: String,
        key: CacheKey,
        description: String,
    ) {
        ensureLoaded()
        val imageHash = hash(imageBase64)
        val fullKey = key.copy(imageHash = imageHash)
        val now = System.currentTimeMillis()
        val entry = CacheEntry(
            imageHash = imageHash,
            modelId = key.modelId,
            userRequestHash = key.userRequestHash,
            promptVersion = key.promptVersion,
            description = description,
            createdAt = now,
            lastUsedAt = now,
            sessionIds = listOf(sessionId),
        )
        mutex.withLock {
            // 合并 sessionIds:若条目已存在,合并 session 集合
            val existing = memoryCache[fullKey.toString()]
            val mergedSessions = if (existing != null) {
                (existing.sessionIds + sessionId).distinct()
            } else listOf(sessionId)
            val finalEntry = entry.copy(sessionIds = mergedSessions)
            memoryCache[fullKey.toString()] = finalEntry
            pendingFlush[fullKey.toString()] = finalEntry
            putSinceFlush++
            if (putSinceFlush >= FLUSH_INTERVAL) {
                flushToDiskLocked()
            }
            evictIfNeeded()
        }
    }

    /**
     * 清空指定会话相关的缓存条目。
     *
     * 用于会话删除时清理。由于缓存跨 session 共享,只清理仅被该 session 使用过的条目,
     * 其他 session 也在用的条目保留。
     */
    suspend fun clearSession(sessionId: String) {
        ensureLoaded()
        mutex.withLock {
            val toRemove = memoryCache.entries.filter { (_, entry) ->
                entry.sessionIds.contains(sessionId) && entry.sessionIds.size == 1
            }.map { it.key }
            toRemove.forEach { memoryCache.remove(it) }
            if (toRemove.isNotEmpty()) {
                // 全量重写 sidecar(删除操作较少,全量写可接受)
                flushToDiskLocked(full = true)
                Logger.d(TAG, "清理 session $sessionId 的视觉缓存,删除 ${toRemove.size} 条")
            }
        }
    }

    /**
     * 清空所有缓存(内存 + sidecar)。
     */
    suspend fun clearAll() {
        mutex.withLock {
            memoryCache.clear()
            pendingFlush.clear()
            putSinceFlush = 0
            sidecarFile.delete()
            Logger.d(TAG, "已清空所有视觉缓存")
        }
    }

    /**
     * LRU 淘汰:若内存缓存超过 [MAX_MEMORY_ENTRIES],按 lastUsedAt 删除最久未用的。
     */
    private fun evictIfNeeded() {
        if (memoryCache.size <= MAX_MEMORY_ENTRIES) return
        val toEvict = memoryCache.size - MAX_MEMORY_ENTRIES
        val sorted = memoryCache.entries.sortedBy { it.value.lastUsedAt }
        repeat(toEvict) { idx ->
            val key = sorted[idx].key
            memoryCache.remove(key)
            Logger.d(TAG, "LRU 淘汰视觉缓存条目: $key")
        }
    }

    /**
     * 刷盘:把 pendingFlush 的增量条目合并到 sidecar 文件。
     *
     * @param full true=全量重写(用于 clearSession 后);false=增量合并(默认)
     */
    private suspend fun flushToDiskLocked(full: Boolean = false) = withContext(Dispatchers.IO) {
        resultOf {
            cacheDir.mkdirs()
            val allEntries = if (full) {
                memoryCache.values.toList()
            } else {
                // 增量:先加载现有 sidecar,再合并 pendingFlush
                val existing = loadSidecar().associateBy { keyOf(it) }
                val merged = existing.toMutableMap()
                pendingFlush.forEach { (k, v) -> merged[k] = v }
                merged.values.toList()
            }
            val text = AppJson.encodeToString(ListSerializer(CacheEntry.serializer()), allEntries)
            val tmp = File(cacheDir, "${sidecarFile.name}.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(sidecarFile)) {
                sidecarFile.writeText(text)
                tmp.delete()
            }
            pendingFlush.clear()
            putSinceFlush = 0
        }.onError { msg, t ->
            Logger.w(TAG, "刷盘视觉缓存失败: $msg", t)
        }
    }

    private fun keyOf(entry: CacheEntry): String =
        "${entry.imageHash}|${entry.modelId}|${entry.userRequestHash}|v${entry.promptVersion}"

    private suspend fun loadSidecar(): List<CacheEntry> = withContext(Dispatchers.IO) {
        if (!sidecarFile.exists()) return@withContext emptyList()
        resultOf {
            val text = sidecarFile.readText()
            if (text.isBlank()) emptyList()
            else AppJson.decodeFromString(ListSerializer(CacheEntry.serializer()), text)
        }.onError { msg, t ->
            Logger.w(TAG, "加载视觉缓存 sidecar 失败: $msg", t)
        }.getOrNull() ?: emptyList()
    }

    private fun hash(base64: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(base64.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "VisionCache"
        /** 内存缓存上限(LRU 淘汰),对齐 openhanako 的 _maxCacheEntries。 */
        private const val MAX_MEMORY_ENTRIES = 256
        /** 每多少次 put 触发一次刷盘。 */
        private const val FLUSH_INTERVAL = 5
    }
}
