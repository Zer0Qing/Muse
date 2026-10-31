package io.zer0.muse.data.stats

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * 一次模型请求的用量流水。
 *
 * 为什么需要这张表：此前统计页要遍历全部消息、解析 JSON 反序列化出 token 字段，
 * 会话多了打开就要等几秒；而且**非对话类请求**（压缩、起标题、翻译、记忆整理、OCR）
 * 根本没有消息载体，用量完全不可见。
 *
 * 这里每次请求落一行，统计改为直接聚合本表：查询快、口径统一、来源可拆分。
 *
 * 设计要点：
 *  - 只追加不修改，天然适合按时间范围聚合；
 *  - [source] 用字符串而非枚举序号，将来加新来源不需要迁移；
 *  - 对话类记录在迁移时用 messages 上已有的 usage 字段回填，历史数据不丢。
 */
@Entity(
    tableName = "usage_records",
    indices = [
        Index(value = ["created_at"]),
        Index(value = ["source", "created_at"]),
        Index(value = ["model_id", "created_at"]),
    ],
)
data class UsageRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 请求发生时间（毫秒）。 */
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    /** 请求来源，见 [UsageSource]。 */
    val source: String,
    /** 模型标识（如 deepseek-v4-flash），未知时为空串。 */
    @ColumnInfo(name = "model_id", defaultValue = "") val modelId: String = "",
    /** 供应商标识（用于按通道拆分）。 */
    @ColumnInfo(name = "provider_id", defaultValue = "") val providerId: String = "",
    @ColumnInfo(name = "prompt_tokens", defaultValue = "0") val promptTokens: Int = 0,
    @ColumnInfo(name = "completion_tokens", defaultValue = "0") val completionTokens: Int = 0,
    @ColumnInfo(name = "reasoning_tokens", defaultValue = "0") val reasoningTokens: Int = 0,
    @ColumnInfo(name = "cached_tokens", defaultValue = "0") val cachedTokens: Int = 0,
    /**
     * 缓存**写入** token（Anthropic cache_creation_input_tokens）。
     *
     * 与 [cachedTokens]（缓存读取）分列：写入单价高于普通输入（Anthropic 为 1.25 倍），
     * 合进一个数就再也拆不开。旧行为 0（该列不存在时不存在写入量可记）。
     */
    @ColumnInfo(name = "cache_write_tokens", defaultValue = "0") val cacheWriteTokens: Int = 0,
    /**
     * 首字延迟（毫秒）。0 表示未记录（非流式请求或旧数据）。
     * 这是用户感知“快慢”最直接的指标，比总耗时更有意义。
     */
    @ColumnInfo(name = "first_token_ms", defaultValue = "0") val firstTokenMs: Long = 0,
    /** 整个请求耗时（毫秒），0 表示未记录。 */
    @ColumnInfo(name = "duration_ms", defaultValue = "0") val durationMs: Long = 0,
)

/** 用量来源。字符串常量，新增来源无需数据库迁移。 */
object UsageSource {
    const val CHAT = "chat"
    const val COMPRESS = "compress"
    const val TITLE = "title"
    const val SUGGESTION = "suggestion"
    const val TRANSLATE = "translate"
    const val MEMORY = "memory"
    const val OCR = "ocr"
    const val OTHER = "other"

    val ALL = listOf(CHAT, COMPRESS, TITLE, SUGGESTION, TRANSLATE, MEMORY, OCR, OTHER)
}

/** 按来源聚合的一行。 */
data class UsageBySource(
    val source: String,
    val requests: Int,
    val promptTokens: Long,
    val completionTokens: Long,
)

/** 按模型聚合的一行。 */
data class UsageByModel(
    val modelId: String,
    val requests: Int,
    val totalTokens: Long,
)

/** 按天聚合的一行（day 为 yyyy-MM-dd）。 */
data class UsageByDay(
    val day: String,
    val requests: Int,
    val totalTokens: Long,
)

/** 用量总计。 */
data class UsageTotals(
    val requests: Int,
    @ColumnInfo(name = "prompt_tokens") val promptTokens: Long,
    @ColumnInfo(name = "completion_tokens") val completionTokens: Long,
    @ColumnInfo(name = "reasoning_tokens") val reasoningTokens: Long,
    @ColumnInfo(name = "cached_tokens") val cachedTokens: Long,
    @ColumnInfo(name = "cache_write_tokens") val cacheWriteTokens: Long,
    @ColumnInfo(name = "avg_first_token_ms") val avgFirstTokenMs: Long,
)

@Dao
interface UsageRecordDao {

    @Insert
    suspend fun insert(record: UsageRecordEntity)

    @Insert
    suspend fun insertAll(records: List<UsageRecordEntity>)

    /** 时间范围总计。sinceMs 为 0 表示不限起点。 */
    @Query(
        """
        SELECT COUNT(*) AS requests,
               COALESCE(SUM(prompt_tokens), 0) AS prompt_tokens,
               COALESCE(SUM(completion_tokens), 0) AS completion_tokens,
               COALESCE(SUM(reasoning_tokens), 0) AS reasoning_tokens,
               COALESCE(SUM(cached_tokens), 0) AS cached_tokens,
               COALESCE(SUM(cache_write_tokens), 0) AS cache_write_tokens,
               COALESCE(AVG(NULLIF(first_token_ms, 0)), 0) AS avg_first_token_ms
        FROM usage_records
        WHERE created_at >= :sinceMs
        """,
    )
    suspend fun totalsSince(sinceMs: Long): UsageTotals?

    /** 按来源拆分。 */
    @Query(
        """
        SELECT source AS source,
               COUNT(*) AS requests,
               COALESCE(SUM(prompt_tokens), 0) AS promptTokens,
               COALESCE(SUM(completion_tokens), 0) AS completionTokens
        FROM usage_records
        WHERE created_at >= :sinceMs
        GROUP BY source
        ORDER BY requests DESC
        """,
    )
    suspend fun bySourceSince(sinceMs: Long): List<UsageBySource>

    /** 模型排行（按总 token）。 */
    @Query(
        """
        SELECT model_id AS modelId,
               COUNT(*) AS requests,
               COALESCE(SUM(prompt_tokens + completion_tokens), 0) AS totalTokens
        FROM usage_records
        WHERE created_at >= :sinceMs AND model_id != ''
        GROUP BY model_id
        ORDER BY totalTokens DESC
        LIMIT :limit
        """,
    )
    suspend fun topModelsSince(sinceMs: Long, limit: Int = 8): List<UsageByModel>

    /**
     * 按天聚合（day = yyyy-MM-dd，本地时区）。
     *
     * 用 SQLite 的 date() 配合 'localtime'，保证与用户看到的日期一致；
     * 时区偏移在 SQL 里处理，避免 Kotlin 侧再算一遍。
     */
    @Query(
        """
        SELECT date(created_at / 1000, 'unixepoch', 'localtime') AS day,
               COUNT(*) AS requests,
               COALESCE(SUM(prompt_tokens + completion_tokens), 0) AS totalTokens
        FROM usage_records
        WHERE created_at >= :sinceMs
        GROUP BY day
        ORDER BY day ASC
        """,
    )
    suspend fun byDaySince(sinceMs: Long): List<UsageByDay>

    /** 清理早于指定时间的记录（长期运行避免表无限增长）。 */
    @Query("DELETE FROM usage_records WHERE created_at < :beforeMs")
    suspend fun deleteBefore(beforeMs: Long): Int

    @Query("SELECT COUNT(*) FROM usage_records")
    suspend fun count(): Int
}
