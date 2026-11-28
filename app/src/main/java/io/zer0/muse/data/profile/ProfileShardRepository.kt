package io.zer0.muse.data.profile

import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.memory.runtime.MemoryScopeKey
import io.zer0.muse.data.knowledge.KnowledgeDocDao
import io.zer0.muse.rag.RagService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 用户画像分域独立本地仓库（不进入用户知识库与全库 RAG/HNSW 索引）。
 *
 * 将「关于你」非核心域事实按五大维度（生活习惯、医疗健康、待人处事、工作与学习、喜好风格）
 * 独立保存为应用私有目录下的 `<storageDir>/<assistantId>.json`，
 * 与知识库 / 记忆中心持久化完全解耦；并在首次同步或清理时幂等清扫历史上误写入
 * `knowledge_docs`（`fileType = "profile_shard"`）的旧分片记录，避免全库检索乱引用或卡顿。
 */
class ProfileShardRepository(
    private val storageDir: File? = null,
    private val legacyKnowledgeDocDao: KnowledgeDocDao? = null,
    private val legacyRagService: RagService? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val currentFacts: (suspend (MemoryScopeKey) -> List<ProfileShardEngine.ProfileFactInput>)? = null,
) {
    @Serializable
    private data class StoredAssistantShards(
        val updatedAt: Long = 0L,
        val domains: Map<String, String> = emptyMap(),
    )

    private val syncMutex = Mutex()
    private val cache = ConcurrentHashMap<String, Map<ProfileShardEngine.ProfileDomain, String>>()
    private val cleanedLegacyAssistants = ConcurrentHashMap.newKeySet<String>()

    /** 按助手增量同步五大分域文件；内容未变化时零磁盘 I/O。 */
    suspend fun syncShards(assistantId: String, facts: List<ProfileShardEngine.ProfileFactInput>) = syncMutex.withLock {
        val safeAssistantId = normalizeAssistantId(assistantId)
        val desiredDocs = ProfileShardEngine.buildDomainDocuments(facts)

        withContext(Dispatchers.IO) {
            cleanupLegacyKnowledgeShardsIfNeeded(safeAssistantId)
            val existing = loadOrGetCachedLocked(safeAssistantId)
            if (existing == desiredDocs) {
                return@withContext
            }
            if (desiredDocs.isEmpty()) {
                cache.remove(safeAssistantId)
                shardFileFor(safeAssistantId)?.delete()
            } else {
                cache[safeAssistantId] = desiredDocs.toMap()
                writeShardFileLocked(safeAssistantId, desiredDocs)
            }
        }
    }

    /** 按本轮用户输入从独立分域存储中检索最相关片段（预算 ≤ [maxTokens]），纯内存计算、不走知识库 RAG。 */
    suspend fun retrieveRelevantShards(
        assistantId: String,
        query: String,
        maxTokens: Int = ProfileShardEngine.DEFAULT_SHARD_MAX_TOKENS,
        memoryScope: String = if (assistantId == "default") "main" else assistantId,
        spaceId: String = "default",
    ): String {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank() || maxTokens <= 0) return ""
        val safeAssistantId = normalizeAssistantId(assistantId)
        // The active memory scope is authoritative for recall because a persisted shard can lag a delete or restore.
        val domainDocs = if (currentFacts != null) {
            if (io.zer0.common.ProcessWriteGate.restoring) return ""
            ProfileShardEngine.buildDomainDocuments(
                currentFacts.invoke(MemoryScopeKey(safeAssistantId, memoryScope, spaceId.ifBlank { "default" })),
            )
        } else {
            cache[safeAssistantId] ?: syncMutex.withLock {
                withContext(Dispatchers.IO) {
                    loadOrGetCachedLocked(safeAssistantId)
                }
            }
        }
        if (domainDocs.isEmpty()) return ""
        return ProfileShardEngine.selectLocalRelevant(
            query = cleanQuery,
            domainDocs = domainDocs,
            maxTokens = maxTokens,
        )
    }

    /** 清空某个分域的独立存储，并顺带清理历史遗留的同名知识库分片。 */
    suspend fun deleteDomainShard(assistantId: String, domain: ProfileShardEngine.ProfileDomain) = syncMutex.withLock {
        val safeAssistantId = normalizeAssistantId(assistantId)
        withContext(Dispatchers.IO) {
            cleanupLegacyKnowledgeShardsIfNeeded(safeAssistantId)
            val current = loadOrGetCachedLocked(safeAssistantId).toMutableMap()
            val removed = current.remove(domain) != null
            if (removed) {
                if (current.isEmpty()) {
                    cache.remove(safeAssistantId)
                    shardFileFor(safeAssistantId)?.delete()
                } else {
                    cache[safeAssistantId] = current.toMap()
                    writeShardFileLocked(safeAssistantId, current)
                }
            }
        }
    }

    private suspend fun cleanupLegacyKnowledgeShardsIfNeeded(safeAssistantId: String) {
        if (!cleanedLegacyAssistants.add(safeAssistantId)) return
        val dao = legacyKnowledgeDocDao ?: return
        val kbId = kbIdFor(safeAssistantId)
        val staleIds = runCatching {
            dao.getInternalByKb(kbId)
                .filter { it.fileType == FILE_TYPE }
                .map { it.id }
        }.onFailure { error ->
            Logger.w(TAG, "检查旧知识库画像分片失败 ($kbId): ${error.message}")
        }.getOrNull().orEmpty()
        if (staleIds.isEmpty()) return

        val rag = legacyRagService
        if (rag != null) {
            runCatching { rag.deleteDocuments(staleIds) }
                .onFailure { error ->
                    Logger.w(TAG, "批量清理旧画像分片索引失败: ${error.message}")
                    staleIds.forEach { docId ->
                        runCatching { dao.deleteDocWithChunks(docId) }
                    }
                }
        } else {
            staleIds.forEach { docId ->
                runCatching { dao.deleteDocWithChunks(docId) }
                    .onFailure { Logger.w(TAG, "清理旧画像分片失败 ($docId): ${it.message}") }
            }
        }
        Logger.i(TAG, "已将助手 $safeAssistantId 的 ${staleIds.size} 个旧画像分片从知识库剥离")
    }

    private fun loadOrGetCachedLocked(safeAssistantId: String): Map<ProfileShardEngine.ProfileDomain, String> {
        cache[safeAssistantId]?.let { return it }
        val file = shardFileFor(safeAssistantId)
        if (file == null || !file.isFile) {
            return emptyMap()
        }
        val parsed = runCatching {
            val raw = file.readText()
            if (raw.isBlank()) {
                emptyMap()
            } else {
                val payload = AppJson.decodeFromString(StoredAssistantShards.serializer(), raw)
                buildMap {
                    payload.domains.forEach { (key, body) ->
                        val domain = ProfileShardEngine.ProfileDomain.fromKey(key) ?: return@forEach
                        if (body.isNotBlank()) put(domain, body)
                    }
                }
            }
        }.onFailure { error ->
            Logger.w(TAG, "读取独立画像分域文件失败 (${file.name}): ${error.message}")
        }.getOrNull().orEmpty()
        if (parsed.isNotEmpty()) {
            cache[safeAssistantId] = parsed
        }
        return parsed
    }

    private fun writeShardFileLocked(safeAssistantId: String, docs: Map<ProfileShardEngine.ProfileDomain, String>) {
        val file = shardFileFor(safeAssistantId) ?: return
        runCatching {
            val parent = file.parentFile
            if (parent != null && !parent.exists()) {
                parent.mkdirs()
            }
            val payload = StoredAssistantShards(
                updatedAt = clock(),
                domains = docs.entries.associate { (domain, text) -> domain.key to text },
            )
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(AppJson.encodeToString(StoredAssistantShards.serializer(), payload))
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        }.onFailure { error ->
            Logger.w(TAG, "写入独立画像分域文件失败 ($safeAssistantId): ${error.message}", error)
        }
    }

    private fun shardFileFor(safeAssistantId: String): File? {
        val dir = storageDir ?: return null
        val sanitizedName = safeAssistantId.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return File(dir, "$sanitizedName.json")
    }

    companion object {
        private const val TAG = "ProfileShardRepo"
        const val DEFAULT_ASSISTANT_ID: String = "default"
        const val KB_PREFIX: String = "profile_shards:"
        const val FILE_TYPE: String = "profile_shard"

        fun normalizeAssistantId(assistantId: String?): String = assistantId?.trim()?.takeIf { it.isNotBlank() } ?: DEFAULT_ASSISTANT_ID

        fun kbIdFor(assistantId: String?): String = "$KB_PREFIX${normalizeAssistantId(assistantId)}"

        fun docIdFor(assistantId: String?, domain: ProfileShardEngine.ProfileDomain): String =
            "profile_shard_${normalizeAssistantId(assistantId)}_${domain.key}"
    }
}
