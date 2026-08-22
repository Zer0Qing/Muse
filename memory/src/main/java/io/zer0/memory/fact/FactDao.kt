package io.zer0.memory.fact

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

/**
 * Fact 数据访问对象(预编译查询)。
 *
 * v6: 新增 facts_fts FTS4 全文索引。
 *  - 全文搜索优先走 FTS4 MATCH(ngram 预处理)
 *  - 单字/异常时回退 LIKE '%query%'
 *  - 标签搜索用 json_each 精确匹配(避免 LIKE 子串误匹配)
 */
@Dao
interface FactDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: FactEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<FactEntity>): List<Long>

    /**
     * v4: 按 importance 降序 + time 降序获取全部事实。
     * 关键(importance=2)和重要(importance=1)的事实排在前面,便于 UI 优先展示。
     *
     * v8: 新增可选 scope 过滤,null 表示全部作用域,非 null 仅返回指定作用域的事实。
     */
    @Query("SELECT * FROM facts WHERE (:scope IS NULL OR scope = :scope) ORDER BY (pinned_at IS NOT NULL) DESC, importance DESC, time DESC")
    suspend fun getAll(scope: String? = null): List<FactEntity>

    @Query("SELECT * FROM facts WHERE id = :id")
    suspend fun getById(id: Long): FactEntity?

    @Query("SELECT * FROM facts WHERE session_id = :sessionId ORDER BY (pinned_at IS NOT NULL) DESC, importance DESC, time DESC")
    suspend fun getBySession(sessionId: String): List<FactEntity>

    @Query("SELECT COUNT(*) FROM facts")
    suspend fun count(): Int

    @Query("DELETE FROM facts WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    /**
     * P2: 更新指定 fact 的内容(用于记忆页 UI 编辑 Fact 层)。
     *
     * v8: 新增可选 scope 参数:
     *  - scope 为 null 时,只更新 content,保留原有 scope(COALESCE 语义)
     *  - scope 非 null 时,同时更新 content 与 scope(用于 UI 切换事实作用域)
     */
    @Query("UPDATE facts SET fact = :content, scope = COALESCE(:scope, scope) WHERE id = :id")
    suspend fun updateContent(id: Long, content: String, scope: String? = null): Int

    /**
     * v4: 更新指定 fact 的重要程度(用于记忆页 UI 手动调整)。
     * importance: 0=普通,1=重要,2=关键
     */
    @Query("UPDATE facts SET importance = :importance WHERE id = :id")
    suspend fun updateImportance(id: Long, importance: Int): Int

    /** B4-05: 设置/取消手动置顶(pinnedAt 为 null 表示取消)。 */
    @Query("UPDATE facts SET pinned_at = :pinnedAt WHERE id = :id")
    suspend fun updatePinnedAt(id: Long, pinnedAt: String?): Int

    /** v12: 回填实体键(反思任务对历史 null 数据)。 */
    @Query("UPDATE facts SET entity_key = :entityKey WHERE id = :id")
    suspend fun updateEntityKey(id: Long, entityKey: String): Int

    /**
     * v10 P2-3: 更新指定 fact 的分类和标签(用于 AI 记忆管理的 updatedEntities/autoCategorize)。
     * category 或 tags 为 null 时保留原值(COALESCE 语义)。
     */
    @Query("UPDATE facts SET category = COALESCE(:category, category), tags = COALESCE(:tags, tags) WHERE id = :id")
    suspend fun updateCategoryAndTags(id: Long, category: String? = null, tags: String? = null): Int

    /** v5: 全字段更新(用于合并去重后替换内容)。 */
    @Query("UPDATE facts SET fact = :fact, tags = :tags, time = :time, session_id = :sessionId, created_at = :createdAt, importance = :importance, category = :category, confidence = :confidence, source = :source, expires_at = :expiresAt, last_confirmed_at = :lastConfirmedAt, last_hit_at = :lastHitAt, entity_key = :entityKey WHERE id = :id")
    suspend fun updateEntity(id: Long, fact: String, tags: String, time: String?, sessionId: String?, createdAt: String, importance: Int, category: String, confidence: Float, source: String, expiresAt: String?, lastConfirmedAt: String?, lastHitAt: String?, entityKey: String? = null)

    /**
     * v5: 查找与给定文本前40字前缀匹配的事实(用于去重)。
     *
     * v8: 新增可选 scope 过滤,去重时仅在相同作用域内查找相似事实,
     * 避免"main"作用域的事实与子助手作用域的事实被误合并。
     */
    @Query("SELECT * FROM facts WHERE fact LIKE :prefix || '%' AND (:scope IS NULL OR scope = :scope) ORDER BY importance DESC, created_at DESC LIMIT 5")
    suspend fun findSimilar(prefix: String, scope: String? = null): List<FactEntity>

    /**
     * v9: 按 scope + space_id 双重过滤查找相似事实(用于去重)。
     * 与 [findSimilar] 的区别:严格按 scope + space_id 过滤,不接受 null。
     */
    @Query(
        """
        SELECT * FROM facts
        WHERE fact LIKE :prefix || '%'
          AND scope = :scope
          AND space_id = :spaceId
        ORDER BY importance DESC, created_at DESC
        LIMIT 5
        """
    )
    suspend fun findSimilarBySpace(prefix: String, scope: String, spaceId: String): List<FactEntity>

    /**
     * v12: 按 entity_key + scope + space_id 精确查找(写入时实体级查重)。
     * 同一实体的不同写法共享同一 entity_key,命中即合并而非新增,
     * 直接解决"张三/张先生/张三老师"多条重复记忆问题。
     */
    @Query(
        """
        SELECT * FROM facts
        WHERE entity_key = :entityKey
          AND scope = :scope
          AND space_id = :spaceId
        ORDER BY (pinned_at IS NOT NULL) DESC, importance DESC, created_at DESC
        LIMIT 10
        """
    )
    suspend fun findByEntityKey(entityKey: String, scope: String, spaceId: String): List<FactEntity>

    @Query("DELETE FROM facts")
    suspend fun deleteAll(): Int

    /**
     * 删除创建时间早于 [cutoffIso] 的全部 fact。
     * 由 [FactStore.applyDecay] 在 daily pipeline 中调用,实现配置驱动的遗忘。
     *
     * 用 created_at 而非 time:time 是 fact 自身描述的事件时间(可空且可远早于入库),
     * created_at 是落库时间,作为衰减基准更稳定。
     *
     * v8: 新增可选 scope 过滤,null 表示全部作用域,非 null 仅删除指定作用域的事实。
     *
     * @return 实际删除的行数
     */
    @Query("DELETE FROM facts WHERE created_at < :cutoffIso AND (:scope IS NULL OR scope = :scope)")
    suspend fun deleteOlderThan(cutoffIso: String, scope: String? = null): Int

    /**
     * v4: 删除创建时间早于 [cutoffIso] 且 importance < [minImportance] 的 fact。
     * 关键事实(importance=2)通过 minImportance=3 永不删除,实现"永不衰减"。
     *
     * v8: 新增可选 scope 过滤,null 表示全部作用域。
     *
     * @return 实际删除的行数
     */
    @Query("DELETE FROM facts WHERE created_at < :cutoffIso AND importance < :minImportance AND (:scope IS NULL OR scope = :scope)")
    suspend fun deleteOlderThanExceptImportant(cutoffIso: String, minImportance: Int, scope: String? = null): Int

    /**
     * v7: 按命中时间衰减删除。
     *  - 从未命中(last_hit_at IS NULL)的事实按 created_at 判断
     *  - 已命中的事实按 last_hit_at 判断
     *  - importance < minImportance 的事实才会被删除
     *
     * 配合 [MemoryConfig.hitBonus] 实现"被引用的记忆更慢遗忘"。
     *
     * v8: 新增可选 scope 过滤,null 表示全部作用域。
     */
    @Query("""
        DELETE FROM facts
        WHERE importance < :minImportance
          AND (:scope IS NULL OR scope = :scope)
          AND (
            (last_hit_at IS NULL AND created_at < :neverHitCutoffIso)
            OR
            (last_hit_at IS NOT NULL AND last_hit_at < :hitCutoffIso)
          )
    """)
    suspend fun deleteOlderThanWithHit(neverHitCutoffIso: String, hitCutoffIso: String, minImportance: Int, scope: String? = null): Int

    /**
     * B-18: 删除已过期事实(expires_at 非空且早于当前时间)。
     * 时效性事实(如"明天上午开会")过期后不再驻留、不再注入。
     *
     * @return 实际删除的行数
     */
    @Query("DELETE FROM facts WHERE expires_at IS NOT NULL AND expires_at != '' AND expires_at < :nowISO AND (:scope IS NULL OR scope = :scope)")
    suspend fun deleteExpired(nowISO: String, scope: String? = null): Int

    /**
     * 全文搜索(LIKE,兼容所有 ROM)。
     * 在 fact 字段上做子串匹配,v4: 按 importance 降序 + time 降序。
     */
    @Query("SELECT * FROM facts WHERE fact LIKE '%' || :query || '%' AND (scope = :scope OR :scope IS NULL) ORDER BY (pinned_at IS NOT NULL) DESC, importance DESC, time DESC LIMIT :limit")
    suspend fun likeSearch(query: String, limit: Int, scope: String? = null): List<FactEntity>

    /**
     * v9: 按 scope + space_id 双重过滤的 LIKE 全文搜索(用于去重兜底)。
     */
    @Query(
        """
        SELECT * FROM facts
        WHERE fact LIKE '%' || :query || '%'
          AND scope = :scope
          AND space_id = :spaceId
        ORDER BY (pinned_at IS NOT NULL) DESC, importance DESC, time DESC
        LIMIT :limit
        """
    )
    suspend fun likeSearchBySpace(query: String, limit: Int, scope: String, spaceId: String): List<FactEntity>

    /**
     * v6: FTS4 全文搜索。
     * 使用已 ngram 化的 MATCH 表达式(由 [FactFtsManager.toMatchQuery] 生成)。
     */
    @Query("""
        SELECT f.* FROM facts_fts
        JOIN facts f ON facts_fts.fact_id = f.id
        WHERE content_ngram MATCH :matchQuery
        ORDER BY (f.pinned_at IS NOT NULL) DESC, f.importance DESC, f.time DESC
        LIMIT :limit
    """)
    suspend fun searchFts(matchQuery: String, limit: Int): List<FactEntity>

    /** 按 scope + space 搜索 FTS，避免先取全局 top-K 再过滤导致漏召回。 */
    @Query("""
        SELECT f.* FROM facts_fts
        JOIN facts f ON facts_fts.fact_id = f.id
        WHERE content_ngram MATCH :matchQuery
          AND f.scope = :scope
          AND f.space_id = :spaceId
        ORDER BY (f.pinned_at IS NOT NULL) DESC, f.importance DESC, f.time DESC
        LIMIT :limit
    """)
    suspend fun searchFtsBySpace(matchQuery: String, limit: Int, scope: String, spaceId: String): List<FactEntity>

    /**
     * 标签 + 日期范围搜索。SQL 由 [FactStore.searchByTags] 动态拼接
     * (因为 tag 数量和日期范围组合是动态的,Room 静态 @Query 难以表达)。
     */
    @RawQuery
    suspend fun tagSearch(query: SupportSQLiteQuery): List<FactTagSearchRow>

    // ── v8: 按作用域(scope)查询/衰减 ──

    /**
     * v8: 按 scope 观察事实列表(Flow 形式),用于 UI 实时刷新。
     * 排序与 [getAll] 一致:importance DESC + time DESC。
     */
    @Query("SELECT * FROM facts WHERE scope = :scope ORDER BY (pinned_at IS NOT NULL) DESC, importance DESC, time DESC")
    fun observeByScope(scope: String): Flow<List<FactEntity>>

    /**
     * v8: 按 scope 同步查询事实列表。
     * 用于 system prompt 注入、子助手记忆检索等场景。
     */
    @Query("SELECT * FROM facts WHERE scope = :scope ORDER BY (pinned_at IS NOT NULL) DESC, importance DESC, time DESC")
    suspend fun getByScope(scope: String): List<FactEntity>

    /**
     * v8: 按作用域衰减删除 — 仅删除指定 scope 下早于 [cutoffIso] 且 importance < [minImportance] 的事实。
     * 用于 daily pipeline 中各助手作用域独立衰减,避免一个助手的低重要性事实
     * 影响其他助手的衰减节奏。
     *
     * @return 实际删除的行数
     */
    @Query("DELETE FROM facts WHERE scope = :scope AND created_at < :cutoffIso AND importance < :minImportance")
    suspend fun deleteByScopeExceptImportant(scope: String, cutoffIso: String, minImportance: Int): Int

    // ── v6: FTS4 索引同步 ──

    /** 插入/更新 facts_fts 索引(fact_id 不索引,content_ngram 全文索引)。 */
    @Query("INSERT OR REPLACE INTO facts_fts(fact_id, content_ngram) VALUES(:factId, :contentNgram)")
    suspend fun insertFts(factId: Long, contentNgram: String)

    /** 删除指定 fact 的 FTS 索引。 */
    @Query("DELETE FROM facts_fts WHERE fact_id = :factId")
    suspend fun deleteFts(factId: Long)

    /** 清空 FTS 索引(rebuild 用)。 */
    @Query("DELETE FROM facts_fts")
    suspend fun clearFts()

    /** 取全部事实(rebuild 索引用,只取 id + fact)。 */
    @Query("SELECT id, fact FROM facts")
    suspend fun getAllForFtsRebuild(): List<FtsRebuildRow>

    /** facts 表行数(ensureFtsIndexConsistent 比较用)。 */
    @Query("SELECT COUNT(*) FROM facts")
    suspend fun countFacts(): Int

    /** facts_fts 表行数(ensureFtsIndexConsistent 比较用)。 */
    @Query("SELECT COUNT(*) FROM facts_fts")
    suspend fun countFts(): Int

    // ── v9: 按 Space(space_id)查询/观察/衰减 ───────────────────────────

    /**
     * v9: 按 space_id 观察事实列表(Flow 形式),用于 UI 实时刷新。
     * 排序与 [getAll] 一致:importance DESC + time DESC。
     */
    @Query("SELECT * FROM facts WHERE space_id = :spaceId ORDER BY (pinned_at IS NOT NULL) DESC, importance DESC, time DESC")
    fun observeBySpace(spaceId: String): Flow<List<FactEntity>>

    /**
     * v9: 按 space_id 同步查询事实列表。
     * 用于 system prompt 注入、记忆页 UI 展示等场景。
     */
    @Query("SELECT * FROM facts WHERE space_id = :spaceId ORDER BY (pinned_at IS NOT NULL) DESC, importance DESC, time DESC")
    suspend fun getBySpace(spaceId: String): List<FactEntity>

    /**
     * v9: 按 scope + space_id 双重过滤查询事实列表。
     * scope 按 Agent 隔离,space_id 按场景隔离,两者正交。
     */
    @Query("SELECT * FROM facts WHERE scope = :scope AND space_id = :spaceId ORDER BY (pinned_at IS NOT NULL) DESC, importance DESC, time DESC")
    suspend fun getByScopeAndSpace(scope: String, spaceId: String): List<FactEntity>

    /**
     * v9: 按 scope + space_id 双重过滤观察事实列表(Flow 形式)。
     */
    @Query("SELECT * FROM facts WHERE scope = :scope AND space_id = :spaceId ORDER BY (pinned_at IS NOT NULL) DESC, importance DESC, time DESC")
    fun observeByScopeAndSpace(scope: String, spaceId: String): Flow<List<FactEntity>>

    /**
     * v9: 按 space_id 衰减删除 — 仅删除指定 Space 下早于 [cutoffIso] 且 importance < [minImportance] 的事实。
     *
     * @return 实际删除的行数
     */
    @Query("DELETE FROM facts WHERE space_id = :spaceId AND created_at < :cutoffIso AND importance < :minImportance")
    suspend fun deleteBySpaceExceptImportant(spaceId: String, cutoffIso: String, minImportance: Int): Int

    /**
     * v9: 按 space_id 统计事实数量。
     */
    @Query("SELECT COUNT(*) FROM facts WHERE space_id = :spaceId")
    suspend fun countBySpace(spaceId: String): Int
}

/** 标签搜索结果(带 matchCount)。v4: 含 importance 字段。 */
data class FactTagSearchRow(
    val id: Long,
    val fact: String,
    val tags: String,
    val time: String?,
    @androidx.room.ColumnInfo(name = "session_id")
    val sessionId: String?,
    @androidx.room.ColumnInfo(name = "created_at")
    val createdAt: String,
    val importance: Int = 0,
    val category: String = "general",
    val confidence: Float = 1.0f,
    val source: String = "inferred",
    val expiresAt: String? = null,
    val lastConfirmedAt: String? = null,
    val lastHitAt: String? = null,
    val entityKey: String? = null,
    @androidx.room.ColumnInfo(name = "scope")
    val scope: String = "main",
    @androidx.room.ColumnInfo(name = "space_id")
    val spaceId: String = "default",
    val matchCount: Int,
)

/** FTS rebuild 用轻量行。 */
data class FtsRebuildRow(
    val id: Long,
    val fact: String,
)
