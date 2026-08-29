package io.zer0.muse.data.session

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** 角色消息计数(统计面板用)。 */
data class RoleCount(
    val role: String,
    val cnt: Int,
)

/** Phase 10.3: 搜索结果 JOIN 投影(消息 + 会话标题,避免 N+1 查询)。 */
data class MessageSearchJoin(
    val messageId: String,
    val sessionId: String,
    val content: String,
    val role: String,
    val createdAt: Long,
    val sessionTitle: String,
)

/**
 * 消息数据访问对象。
 */
@Dao
interface MessageDao {

    /** 观察指定会话的全部消息(按 seq 单调序列升序)。备份导出用,需全量加载。 */
    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY CASE WHEN commitSeq > 0 THEN commitSeq ELSE seq END ASC, createdAt ASC, id ASC")
    fun observeBySession(sessionId: String): Flow<List<MessageEntity>>

    /**
     * H-SESS3: 观察指定会话最近 limit 条消息(按 createdAt 升序),UI 流式渲染用。
     *
     * 避免长会话全量加载导致 OOM:子查询先按 DESC 取最近 limit 条,
     * 外层再按 ASC 排序,保证返回的是「最近 limit 条」且按时间正序展示。
     * 更早的历史由 [getOlderBySession] 分页加载。limit 由调用方传入
     * (如 [SessionRepository.OBSERVE_LIMIT])。
     */
    @Query("SELECT * FROM (SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY CASE WHEN commitSeq > 0 THEN commitSeq ELSE seq END DESC, createdAt DESC, id DESC LIMIT :limit) ORDER BY CASE WHEN commitSeq > 0 THEN commitSeq ELSE seq END ASC, createdAt ASC, id ASC")
    fun observeRecentBySession(sessionId: String, limit: Int): Flow<List<MessageEntity>>

    /**
     * v1.53-A1: 窗口加载 — 取指定会话最近 limit 条消息(按 createdAt 降序取,再在内存里反转)。
     *
     * 用于初始加载时分页:只取最近 PAGE_SIZE 条,避免一次性加载全部导致卡顿/OOM。
     * 返回顺序为降序(最新在前),调用方需自行 reversed()。
     */
    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY CASE WHEN commitSeq > 0 THEN commitSeq ELSE seq END DESC, createdAt DESC, id DESC LIMIT :limit")
    suspend fun getRecentBySession(sessionId: String, limit: Int): List<MessageEntity>

    /** 计划历史恢复用:只读取带工具展示信息的消息,避免为恢复计划加载整段长会话。 */
    @Query(
        "SELECT * FROM messages WHERE sessionId = :sessionId AND toolCallInfoJson IS NOT NULL " +
            "ORDER BY CASE WHEN commitSeq > 0 THEN commitSeq ELSE seq END ASC, createdAt ASC, id ASC",
    )
    suspend fun getToolCallMessagesBySession(sessionId: String): List<MessageEntity>

    /**
     * v1.53-A1: 窗口加载 — 取指定会话 createdAt < beforeCreatedAt 的前 limit 条消息(降序)。
     *
     * 用于上滑加载更多:以当前列表最早一条消息的 createdAt 为锚点,取更早的历史。
     * 返回顺序为降序(最新在前),调用方需自行 reversed()。
     */
    @Query("SELECT * FROM messages WHERE sessionId = :sessionId AND createdAt < :beforeCreatedAt ORDER BY CASE WHEN commitSeq > 0 THEN commitSeq ELSE seq END DESC, createdAt DESC, id DESC LIMIT :limit")
    suspend fun getOlderBySession(sessionId: String, beforeCreatedAt: Long, limit: Int): List<MessageEntity>

    /** v1.53-A1: 会话消息总数(分页判断 hasMoreHistory 用)。 */
    @Query("SELECT COUNT(*) FROM messages WHERE sessionId = :sessionId")
    suspend fun countBySession(sessionId: String): Int

    /** v92: 会话内最大 seq(新消息分配 seq 用)。 */
    @Query("SELECT COALESCE(MAX(seq), 0) FROM messages WHERE sessionId = :sessionId")
    suspend fun getMaxSeq(sessionId: String): Long

    /** v1.58: 查询会话中到指定时间戳为止(含)的全部消息(升序),用于对话分叉 Fork。 */
    @Query("SELECT * FROM messages WHERE sessionId = :sessionId AND createdAt <= :untilCreatedAt ORDER BY CASE WHEN commitSeq > 0 THEN commitSeq ELSE seq END ASC")
    suspend fun getUpToBySession(sessionId: String, untilCreatedAt: Long): List<MessageEntity>

    /** 插入消息(冲突时替换,支持流式更新 assistant 消息)。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: MessageEntity)

    /** 每日总结用:取指定时间区间内的用户消息(升序,限量)。 */
    @Query("SELECT * FROM messages WHERE role = 'USER' AND createdAt >= :fromCreatedAt AND createdAt < :toCreatedAt ORDER BY createdAt ASC, rowid ASC LIMIT :limit")
    suspend fun getUserMessagesBetween(fromCreatedAt: Long, toCreatedAt: Long, limit: Int): List<MessageEntity>

    /** 兼容其他调用方的下界查询。 */
    @Query("SELECT * FROM messages WHERE role = 'USER' AND createdAt >= :fromCreatedAt ORDER BY createdAt ASC, rowid ASC LIMIT :limit")
    suspend fun getUserMessagesSince(fromCreatedAt: Long, limit: Int): List<MessageEntity>

    /** 批量插入。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(messages: List<MessageEntity>)

    /** 删除指定会话的全部消息(会话删除时级联触发,也可手动调)。 */
    @Query("DELETE FROM messages WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: String)

    /** v1.48: 按 id 删除单条消息(长按菜单删除误发消息用)。同时清理 FTS 索引。 */
    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun deleteById(messageId: String)

    /** v1.0.80 (T-4): 查父消息的所有子消息(删除 user 消息时级联删除其 assistant 回复)。 */
    @Query("SELECT * FROM messages WHERE parentGroupId = :parentId OR parentMessageId = :parentId")
    suspend fun getChildrenByParentId(parentId: String): List<MessageEntity>

    /** 按 id 删除单条消息(编辑/重生成时截断用)。 */
    @Query("DELETE FROM messages WHERE sessionId = :sessionId AND createdAt >= :fromCreatedAt")
    suspend fun deleteFromCreatedAt(sessionId: String, fromCreatedAt: Long)

    /** 取指定会话的最后一条消息。 */
    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY createdAt DESC LIMIT 1")
    suspend fun getLastBySession(sessionId: String): MessageEntity?

    /** 按 id 取单条消息(编辑助手消息用)。 */
    @Query("SELECT * FROM messages WHERE sessionId = :sessionId AND id = :messageId LIMIT 1")
    suspend fun getById(sessionId: String, messageId: String): MessageEntity?

    /** v1.107: 只按 messageId 取单条消息(删除时查 sessionId 用于冗余计数维护)。 */
    @Query("SELECT * FROM messages WHERE id = :messageId LIMIT 1")
    suspend fun getByMessageId(messageId: String): MessageEntity?

    /** v1.0.74: AI 生成图聚合 — 取 assistant 且带图的最近消息(相册用)。 */
    @Query(
        "SELECT * FROM messages WHERE role = 'ASSISTANT' AND deletedAt IS NULL " +
            "AND (imageUrlsJson != '[]' OR imageBase64Json != '[]') " +
            "ORDER BY createdAt DESC LIMIT :limit",
    )
    suspend fun getAllAssistantWithImages(limit: Int): List<MessageEntity>

    /** P3-16: 取全局最近一条 assistant 消息(桌面对话小部件用,跨会话)。 */
    @Query("SELECT * FROM messages WHERE role = 'ASSISTANT' ORDER BY createdAt DESC LIMIT 1")
    suspend fun getLastAssistantMessage(): MessageEntity?

    /**
     * v1.0.4: 取全局最近 N 条 assistant 消息(跨会话,情绪追踪 / 报告页用)。
     *
     * 用于 [io.zer0.muse.data.emotion.MoodParser] 从消息内容中解析 `<mood>` 标签,
     * 聚合情绪趋势展示在报告页。
     */
    @Query("SELECT * FROM messages WHERE role = 'ASSISTANT' ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getRecentAssistantMessages(limit: Int): List<MessageEntity>

    /**
     * Phase 10.3 之前:LIKE 搜索(保留作为 fallback,单字查询 / FTS4 不可用时用)。
     *
     * M-SESS2: 用 ESCAPE '\' + 预转义 pattern,避免 query 内的 %/_ 被当作通配符。
     * 调用方([SessionRepository.buildLikePattern])负责把 \ % _ 转义后包成 %...% 传入。
     */
    @Query("SELECT * FROM messages WHERE content LIKE :pattern ESCAPE '\\' ORDER BY createdAt DESC LIMIT 50")
    suspend fun searchLike(pattern: String): List<MessageEntity>

    /**
     * v2.x: LIKE 兜底搜索(FTS4 不可用 / ngram 转换为空时使用)。
     *
     * 返回 [MessageSearchJoin](含 content 原文),由 Repository 层用 buildSnippet
     * 构建 [SearchResult.contentSnippet](基于原文,片段语义正确)。
     *
     * TODO: LIKE 路径性能低于 FTS,仅作兜底;FTS5 + 原文 snippet 落地后可移除。
     *
     * @param pattern 已转义的 LIKE 模式串(如 %keyword%),配合 ESCAPE '\'
     * @param limit 最大返回条数(默认 50)
     */
    @Query("""
        SELECT
            m.id as messageId,
            m.sessionId as sessionId,
            m.content as content,
            m.role as role,
            m.createdAt as createdAt,
            s.title as sessionTitle
        FROM messages m
        JOIN sessions s ON m.sessionId = s.id
        WHERE m.content LIKE :pattern ESCAPE '\'
        ORDER BY m.createdAt DESC
        LIMIT :limit
    """)
    suspend fun searchMessageContentLike(pattern: String, limit: Int = 50): List<MessageSearchJoin>

    /** 消息总数流(统计面板用)。 */
    @Query("SELECT COUNT(*) FROM messages")
    fun observeCount(): Flow<Int>

    // ── v1.0.30: 变体管理 ──

    /** 获取同一变体组的所有消息（按 variantIndex 排序）。 */
    @Query("SELECT * FROM messages WHERE variantGroupId = :groupId ORDER BY variantIndex ASC")
    suspend fun getVariants(groupId: String): List<MessageEntity>

    /** 更新变体组内所有消息的 variantCount。 */
    @Query("UPDATE messages SET variantCount = :count WHERE variantGroupId = :groupId")
    suspend fun updateVariantCount(groupId: String, count: Int)

    /** 清空全部消息(备份恢复时用,Android 16 禁止 execSQL DML)。 */
    @Query("DELETE FROM messages")
    suspend fun deleteAll()

    /** 取全部消息(rebuild 索引用,只取 id + content)。 */
    @Query("SELECT id, content FROM messages")
    suspend fun getAllForFtsRebuild(): List<FtsRebuildRow>

    /**
     * 取最近 [limit] 条消息(id + content,按 createdAt 降序)。FTS 一致性内容抽查用。
     *
     * C-23: ensureFtsIndexConsistent 的行数相等但内容可能漂移时,抽样最近消息做
     * 内容级 MATCH 校验。最近消息是编辑/重生成最常触达的区域,抽样命中率最高;
     * 有界 limit 避免全量加载(full rebuild 才需要 getAllForFtsRebuild)。
     */
    @Query("SELECT id, content FROM messages ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getFtsSample(limit: Int): List<FtsRebuildRow>

    /** Phase 10.3: messages 表行数(ensureFtsIndexConsistent 比较用)。 */
    @Query("SELECT COUNT(*) FROM messages")
    suspend fun countMessages(): Int

    /** 各角色消息数(统计面板用)。 */
    @Query("SELECT role, COUNT(*) as cnt FROM messages GROUP BY role")
    suspend fun countByRole(): List<RoleCount>

    /**
     * v0.46: 统计热力图用 — 查询所有 user 消息的 createdAt 时间戳。
     * 用于在 ViewModel 层 groupBy 日期,统计每日消息数。
     * 只查 user 消息(用户主动行为),不查 assistant/system/tool。
     *
     * role 字段存的是 MessageRole.USER.name(大写 enum name,如 "USER"),
     * 与 [getLastAssistantMessage] 的 `role = 'ASSISTANT'` 写法保持一致。
     */
    @Query("SELECT createdAt FROM messages WHERE role = 'USER'")
    suspend fun getAllUserMessageTimestamps(): List<Long>

    /**
     * v1.104 U8: 统计页时间范围筛选用 — 查询全部 ASSISTANT 消息的 createdAt 时间戳。
     *
     * 与 [getAllUserMessageTimestamps] 对称,供 ViewModel 在内存按 timeRange 过滤后
     * 计算 totalAiMessages(避免依赖 countByRole 的全量聚合)。
     */
    @Query("SELECT createdAt FROM messages WHERE role = 'ASSISTANT'")
    suspend fun getAllAssistantMessageTimestamps(): List<Long>

    /**
     * v0.46: 统计页用 — 查询全部消息(不限角色)的 createdAt 时间戳。
     * 用于统计总消息数 / 本周 / 本月 / 平均每日 / 每周趋势等衍生指标,
     * 在 ViewModel 层 groupBy 日期后计算。
     */
    @Query("SELECT createdAt FROM messages")
    suspend fun getAllMessageTimestamps(): List<Long>

    /**
     * Phase 8.3: 设置消息收藏状态。
     */
    @Query("UPDATE messages SET favorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: String, favorite: Boolean)

    /**
     * Phase 8.3: 观察指定会话内的收藏消息(按 createdAt 升序)。
     */
    @Query("SELECT * FROM messages WHERE sessionId = :sessionId AND favorite = 1 ORDER BY createdAt ASC")
    fun observeFavoritesBySession(sessionId: String): Flow<List<MessageEntity>>

    /**
     * Phase 8.3: 观察跨会话的全部收藏消息(收藏面板用,按 createdAt 降序)。
     */
    @Query("SELECT * FROM messages WHERE favorite = 1 ORDER BY createdAt DESC LIMIT 200")
    fun observeAllFavorites(): Flow<List<MessageEntity>>

    /**
     * v1.104 U7: 设置收藏分组标签(null = 移到未分组)。
     */
    @Query("UPDATE messages SET favoriteTag = :tag WHERE id = :id")
    suspend fun setMessageFavoriteTag(id: String, tag: String?)

    /** 功能1: 设置消息表情回应(null = 取消回应)。 */
    @Query("UPDATE messages SET reaction = :reaction WHERE id = :id")
    suspend fun setReaction(id: String, reaction: String?)

    /**
     * v1.104 U7: 观察所有已命名的收藏分组标签(去 NULL / 去重 / 升序)。
     *
     * 供 FavoritesScreen 顶部 FilterChip 渲染"全部 / tag1 / tag2 / …"。
     * NULL(未分组)不在此结果里 — 由 UI 单独以"未分组"chip 兜底。
     */
    @Query("SELECT DISTINCT favoriteTag FROM messages WHERE favorite = 1 AND favoriteTag IS NOT NULL ORDER BY favoriteTag ASC")
    fun observeAllFavoriteTags(): Flow<List<String>>

    // ── v0.47: 统计页扩展(零迁移,纯查询) ──

    /** v1.0.30: ASSISTANT 消息累计内容长度（≈ token 估算基准）。 */
    @Query("SELECT COALESCE(SUM(LENGTH(content)), 0) FROM messages WHERE role = 'ASSISTANT'")
    suspend fun sumAssistantContentLength(): Long

    /**
     * v0.47: 按 modelId 分组统计 ASSISTANT 消息数(环形图用)。
     *
     * 只统计 ASSISTANT 角色且 modelId 非空的消息(user/system 消息无 modelId)。
     * 用于在 ViewModel 层反查 SettingsRepository 得到模型显示名。
     */
    @Query("SELECT modelId, COUNT(*) as cnt FROM messages WHERE role = 'ASSISTANT' AND modelId IS NOT NULL GROUP BY modelId")
    suspend fun countByModel(): List<ModelCount>

    /**
     * v0.47: 按 assistantId 分组统计消息数(横向条形图用)。
     *
     * JOIN sessions 取 assistantId,按助手聚合全部消息数。
     * 用于在 ViewModel 层反查 AssistantRepository 得到助手显示名。
     */
    @Query("SELECT s.assistantId as assistantId, COUNT(*) as cnt FROM messages m JOIN sessions s ON m.sessionId = s.id GROUP BY s.assistantId")
    suspend fun countByAssistant(): List<AssistantCount>

    /**
     * v0.47: 按小时聚合消息数(24 根柱状图用)。
     *
     * createdAt 为毫秒时间戳,/1000 转秒后用 strftime 取本地时区小时(00-23)。
     * 缺失的小时由 ViewModel 补 0,凑成 24 元素列表。
     */
    @Query("SELECT strftime('%H', createdAt/1000, 'unixepoch', 'localtime') as hour, COUNT(*) as cnt FROM messages GROUP BY hour ORDER BY hour")
    suspend fun countByHour(): List<HourCount>

    /**
     * v0.47: 取消息数最多的前 N 个会话(Top 10 列表用)。
     *
     * 按 sessionId 聚合消息数,降序取前 [n] 条。
     * 用于在 ViewModel 层反查 SessionDao 得到会话标题。
     */
    @Query("SELECT sessionId, COUNT(*) as cnt FROM messages GROUP BY sessionId ORDER BY cnt DESC LIMIT :n")
    suspend fun topSessionsByMessageCount(n: Int): List<SessionMessageCount>
}

/** Phase 10.3: FTS rebuild 用的轻量投影。 */
data class FtsRebuildRow(
    val id: String,
    val content: String,
)

// ── v0.47: 统计页扩展投影(零迁移,纯查询用) ──

/** v0.47: 按 modelId 分组的 ASSISTANT 消息计数(环形图用)。 */
data class ModelCount(
    val modelId: String,
    val cnt: Int,
)

/** v0.47: 按 assistantId 分组的消息计数(横向条形图用)。 */
data class AssistantCount(
    val assistantId: String,
    val cnt: Int,
)

/** v0.47: 按小时分组的消息计数(24 根柱状图用,hour 为 "00"-"23")。 */
data class HourCount(
    val hour: String,
    val cnt: Int,
)

/** v0.47: 单个会话的消息数投影(Top 10 列表用)。 */
data class SessionMessageCount(
    val sessionId: String,
    val cnt: Int,
)
