package io.zer0.memory.ai

import io.zer0.common.Logger
import io.zer0.common.ProcessWriteGate
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * AutoSave 幂等指纹缓存：有界 + 原子持久化。
 *
 * 为什么需要持久化:指纹只活一次进程，重启后同一段对话会被再抽一遍事实，
 * 落库侧虽然有相似度去重，但一次多余的大模型调用是实打实的钱和延迟。
 *
 * 三条约束:
 *  - **只存摘要哈希**：每条记录就是一个 32 位十六进制 MD5，不落任何对话原文，
 *    缓存文件泄露也不会带出用户说过的话。
 *  - **有界**：[maxEntries] 上界，超出按 LRU 淘汰，长期使用不会无限膨胀。
 *  - **原子**：先写 `.tmp` 再 rename 覆盖，掉电最多丢这次更新，不会留下半截 JSON。
 *
 * 与 DeepMemory 的关系:这里的水位**只**代表「这段历史已被 AutoSave 抽过」。
 * DeepMemory 走的是 Room summary 的 revision/snapshot，输入口径也不同（摘要 diff 而非原对话），
 * 两者是独立水位 —— 不能因为 AutoSave 处理过最近 30 条，就认为更早的消息不需要深度提取。
 * AutoSave 与 DeepMemory 之间真正需要消除的重复，由 FactStore 写入侧的去重负责。
 */
internal class AutoSaveFingerprintCache(
    /** 持久化文件；null 时退化为纯内存缓存（测试/无 filesDir 环境）。 */
    private val file: File? = null,
    private val maxEntries: Int = MAX_ENTRIES,
) {

    /**
     * 结构化幂等键。
     *
     * 会话 id 在导入、多助手、多空间场景下会重复出现；只按 sessionId 去重会把
     * 「同一个会话在另一个库里的内容」误判成已处理。四个维度一起才是完整身份。
     */
    data class Key(
        val assistantId: String,
        val scope: String,
        val spaceId: String,
        val sessionId: String,
    ) {
        /** 持久化用的一维键。\u001F 不会出现在 id 里，用它做分隔比 `|` 更安全。 */
        fun encode(): String = listOf(assistantId, scope, spaceId, sessionId).joinToString(FIELD_SEPARATOR)
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val mapSerializer = MapSerializer(String.serializer(), String.serializer())

    private val lock = Any()

    /** LRU：accessOrder=true + removeEldestEntry 实现有界淘汰。 */
    private val entries = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > maxEntries
    }

    /** 加载时看到的恢复代数；与当前代数不一致说明期间发生过库替换。 */
    private var seenRestoreGeneration: Long = ProcessWriteGate.generation

    init {
        load()
    }

    /** 该内容是否已经被处理过。 */
    fun isProcessed(key: Key, fingerprint: String): Boolean = synchronized(lock) {
        entries[key.encode()] == fingerprint
    }

    /** 记录「这段历史已成功处理」。写入后立即落盘。 */
    fun markProcessed(key: Key, fingerprint: String) {
        synchronized(lock) {
            entries[key.encode()] = fingerprint
            persistLocked()
        }
    }

    /** 记忆重置等主动作废入口。 */
    fun invalidate() {
        synchronized(lock) {
            entries.clear()
            seenRestoreGeneration = ProcessWriteGate.generation
            persistLocked()
        }
    }

    /**
     * 作废某个会话的全部指纹（会话被删除时）。
     *
     * 只比键里的 sessionId 段：删掉的会话若以同一 id 再被导入/恢复回来，
     * 它不应该因为「上一轮已经处理过同一段历史」而被跳过一次提取。
     *
     * @return 被移除的条目数
     */
    fun invalidateSession(sessionId: String): Int {
        val id = sessionId.trim()
        if (id.isEmpty()) return 0
        return synchronized(lock) {
            val stale = entries.keys.filter { sessionIdOf(it) == id }
            stale.forEach { entries.remove(it) }
            if (stale.isNotEmpty()) persistLocked()
            stale.size
        }
    }

    /**
     * 作废某个助手的全部指纹（助手被删除时）。
     *
     * 只比键里的 assistantId 段；会话行还在但拥有者已失联时，旧指纹不该让重新绑定的
     * 历史跳过提取。
     *
     * @return 被移除的条目数
     */
    fun invalidateAssistant(assistantId: String): Int {
        val id = assistantId.trim()
        if (id.isEmpty()) return 0
        return synchronized(lock) {
            val stale = entries.keys.filter { assistantIdOf(it) == id }
            stale.forEach { entries.remove(it) }
            if (stale.isNotEmpty()) persistLocked()
            stale.size
        }
    }

    /**
     * 恢复窗口会把库整体替换，旧指纹不再代表「这段历史已经写进当前库」。
     * 每次访问前对齐一次代数：变了就整体作废（内存 + 磁盘）。
     *
     * @return true 表示本次检测到恢复并已作废
     */
    fun invalidateIfRestored(): Boolean = synchronized(lock) {
        val current = ProcessWriteGate.generation
        if (current == seenRestoreGeneration) return false
        seenRestoreGeneration = current
        entries.clear()
        persistLocked()
        Logger.i(TAG, "检测到备份恢复,已作废 AutoSave 指纹缓存")
        true
    }

    /** 当前条目数（测试/诊断用）。 */
    fun size(): Int = synchronized(lock) { entries.size }

    /** 当前快照（测试用，只含哈希）。 */
    fun snapshot(): Map<String, String> = synchronized(lock) { entries.toMap() }

    /** 取键里的 sessionId 段；格式不符（旧格式/损坏）返回 null，按「不匹配」处理。 */
    private fun sessionIdOf(rawKey: String): String? {
        val parts = rawKey.split(FIELD_SEPARATOR)
        return if (parts.size == 4) parts[3] else null
    }

    /** 取键里的 assistantId 段；格式不符（旧格式/损坏）返回 null，按「不匹配」处理。 */
    private fun assistantIdOf(rawKey: String): String? {
        val parts = rawKey.split(FIELD_SEPARATOR)
        return if (parts.size == 4) parts[0] else null
    }

    private fun load() {
        val target = file ?: return
        if (!target.isFile) return
        val parsed = runCatching {
            json.decodeFromString(mapSerializer, target.readText())
        }.getOrElse { err ->
            // 文件损坏不是故障：丢弃后从头积累即可，下一次写入会覆盖它。
            Logger.w(TAG, "AutoSave 指纹缓存读取失败,按空缓存继续: ${err.message}")
            return
        }
        synchronized(lock) {
            entries.clear()
            parsed.forEach { (rawKey, value) ->
                // 只接受「四段键 + 32 位十六进制摘要」，防止旧格式/损坏内容混进来。
                if (rawKey.split(FIELD_SEPARATOR).size != 4) return@forEach
                if (!HASH_PATTERN.matches(value)) return@forEach
                entries[rawKey] = value
            }
            seenRestoreGeneration = ProcessWriteGate.generation
        }
    }

    /** 调用方必须持有 [lock]。 */
    @Suppress("TooGenericExceptionCaught")
    private fun persistLocked() {
        val target = file ?: return
        val payload = json.encodeToString(mapSerializer, LinkedHashMap(entries))
        val directory = target.parentFile
        if (directory != null && !directory.isDirectory && !directory.mkdirs()) {
            Logger.w(TAG, "AutoSave 指纹缓存目录创建失败: ${directory.path}")
            return
        }
        val tmp = File(target.absolutePath + ".tmp")
        try {
            tmp.writeText(payload)
            moveAtomically(tmp, target)
        } catch (err: Exception) {
            // 缓存只是省一次模型调用的优化，落盘失败不能影响记忆写入本身。
            Logger.w(TAG, "AutoSave 指纹缓存落盘失败(内存缓存仍有效): ${err.message}", err)
            tmp.delete()
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun moveAtomically(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (err: Exception) {
            // 某些文件系统不支持 atomic move（跨卷/旧 FS），退回普通覆盖仍好过半截文件。
            Logger.d(TAG, "原子替换不可用,退回普通覆盖: ${err.message}")
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        private const val TAG = "AutoSaveFingerprints"

        /** 条目上限：256 个会话 × 32 字节摘要，文件始终在几十 KB 量级。 */
        const val MAX_ENTRIES = 256

        private const val FIELD_SEPARATOR = "\u001F"
        private val HASH_PATTERN = Regex("^[0-9a-f]{32}$")
    }
}
