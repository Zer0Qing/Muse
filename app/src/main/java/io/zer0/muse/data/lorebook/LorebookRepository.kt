package io.zer0.muse.data.lorebook

import io.zer0.common.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/**
 * Lorebook 仓库 — Phase 8.5。
 *
 * 负责 Lorebook 的 CRUD + 关键词匹配查询。
 * 关键词匹配逻辑([matchAgainst])供 LorebookTransformer 调用。
 */
class LorebookRepository(
    private val dao: LorebookDao,
) {
    /** 观察全部 Lorebook(管理页用)。 */
    fun observeAll(): Flow<List<LorebookEntity>> = dao.observeAll()

    /** 观察启用的 Lorebook(注入器用)。 */
    fun observeEnabled(): Flow<List<LorebookEntity>> = dao.observeEnabled()

    /**
     * 按 id 取启用条目(Assistant 绑定的)。
     * L-DAO1: 分批查询(每批 DAO_BATCH_SIZE 个 id),避免超大 ids 列表导致 SQL 参数过多。
     */
    suspend fun getByIdsEnabled(ids: List<String>): List<LorebookEntity> {
        if (ids.isEmpty()) return emptyList()
        val uniqueIds = ids.distinct()
        val result = mutableListOf<LorebookEntity>()
        for (batch in uniqueIds.chunked(DAO_BATCH_SIZE)) {
            result.addAll(dao.getByIdsEnabled(batch))
        }
        // 分批查询后重新排序,保持与 DAO 一致的 priority DESC, name ASC
        return result.sortedWith(compareByDescending<LorebookEntity> { it.priority }.thenBy { it.name })
    }

    suspend fun getById(id: String): LorebookEntity? = dao.getById(id)

    suspend fun upsert(entity: LorebookEntity) = dao.upsert(entity)

    suspend fun delete(id: String) = dao.deleteById(id)

    /**
     * 在给定文本中匹配 Lorebook 条目。
     * 任一关键词命中即视为匹配;返回所有匹配的条目。
     *
     * 匹配方式: contains 子串匹配,大小写由 [LorebookEntity.caseSensitive] 控制
     * (L-LB7/L-LB8: 为向后兼容保留 contains;TODO:增加 wholeWord 模式以减少误触发)。
     * M-LB2: 显式按 priority 降序 + name 升序排序。
     * L-LB10: 防御性过滤已禁用条目(正常情况调用方已过滤)。
     *
     * @param entries 候选 Lorebook 条目
     * @param text 待匹配文本(通常是最近一条用户消息)
     * @return 命中的条目列表(按 priority 降序 + name 升序)
     */
    fun matchAgainst(entries: List<LorebookEntity>, text: String): List<LorebookEntity> {
        if (entries.isEmpty() || text.isBlank()) return emptyList()
        return entries
            .filter { it.enabled }  // L-LB10: 防御性过滤
            .filter { entry ->
                val keywords = parseKeywords(entry.keywordsJson)
                if (keywords.isEmpty()) return@filter false
                keywords.any { kw ->
                    if (kw.isBlank()) false
                    else if (entry.caseSensitive) text.contains(kw)
                    else text.contains(kw, ignoreCase = true)
                }
            }
            .sortedWith(compareByDescending<LorebookEntity> { it.priority }.thenBy { it.name })  // M-LB2
    }

    companion object {
        // L-DAO1: DAO 分批查询每批最大 id 数
        const val DAO_BATCH_SIZE = 500

        private val json = Json { ignoreUnknownKeys = true }
        private val stringListSerializer = ListSerializer(String.serializer())
        // L-LB5: keywordsJson 解析缓存,避免每次 matchAgainst 重复解析
        private val keywordsCache = ConcurrentHashMap<String, List<String>>()

        /**
         * 解析 keywordsJson 为 List<String>。
         * L-LB5: 以 keywordsJson 字符串为 key 缓存解析结果,避免重复解析。
         * L-DAO2: 解析失败时 Logger.w 记录(而非静默返回 emptyList)。
         */
        fun parseKeywords(keywordsJson: String): List<String> =
            keywordsCache.computeIfAbsent(keywordsJson) {
                runCatching { json.decodeFromString(stringListSerializer, it) }
                    .getOrElse { e ->
                        Logger.w("LorebookRepository", "keywordsJson 解析失败: ${e.message}, 返回 emptyList (raw=$keywordsJson)")
                        emptyList()
                    }
            }

        /** 编码 List<String> 为 keywordsJson。 */
        fun encodeKeywords(keywords: List<String>): String =
            runCatching { json.encodeToString(stringListSerializer, keywords) }
                .getOrDefault("[]")
    }
}
