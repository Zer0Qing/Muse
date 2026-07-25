package io.zer0.memory.fact

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * 元事实实体 (openhanako fact-store.ts SQLite schema 移植)。
 *
 * v3 schema: 移除 search_text 字段(不再用 FTS5,无需 n-gram 索引列)。
 * v4 schema: 新增 importance 字段(0=普通,1=重要,2=关键),关键事实永不衰减。
 * v8 schema: 新增 scope 字段(记忆作用域,默认 "main" 表示主助手作用域,
 *   子助手/团队成员使用各自的 assistantId),用于隔离不同 Agent 的记忆。
 *
 * Phase 7: @Serializable 用于备份导出/导入。id 自增主键在导入时清表后重新分配。
 */
@Serializable
@Entity(
    tableName = "facts",
    indices = [
        Index(value = ["time"], name = "idx_facts_time"),
        Index(value = ["session_id"], name = "idx_facts_session"),
        Index(value = ["importance"], name = "idx_facts_importance"),
        Index(value = ["category"], name = "idx_facts_category"),
        Index(value = ["scope"], name = "index_facts_scope"),
    ],
)
data class FactEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** 事实原文(已 PII 脱敏)。 */
    @ColumnInfo(name = "fact")
    val fact: String,

    /** 标签 JSON 数组字符串,如 `["记忆系统","近况"]`。 */
    @ColumnInfo(name = "tags", defaultValue = "[]")
    val tags: String = "[]",

    /** 事实时间(YYYY-MM-DDTHH:MM),可空。 */
    @ColumnInfo(name = "time")
    val time: String? = null,

    /** 来源 session id。 */
    @ColumnInfo(name = "session_id")
    val sessionId: String? = null,

    /** 创建时间 ISO 8601。 */
    @ColumnInfo(name = "created_at")
    val createdAt: String,

    /**
     * v4: 重要程度(0=普通,1=重要,2=关键)。
     * - 0(普通): 日常偏好,记错无妨(如"咖啡喝美式")
     * - 1(重要): 中等风险,记错会误事(如"周三要交报告")
     * - 2(关键): 高风险,记错会出事(如"青霉素过敏"),永不衰减
     * 由 LLM 提取时初步判定,用户可在记忆页手动调整。
     */
    @ColumnInfo(name = "importance", defaultValue = "0")
    val importance: Int = 0,

    /**
     * v5: 结构化分类(如 preference / identity / event / relationship / goal / medical 等)。
     * 用于按类型检索和 UI 分组展示。
     */
    @ColumnInfo(name = "category", defaultValue = "general")
    val category: String = "general",

    /**
     * v5: LLM 对事实可靠性的置信度,范围 0.0 ~ 1.0。
     * 用户明确陈述取 1.0,推测性信息取 0.5 以下,便于后续人工复核。
     */
    @ColumnInfo(name = "confidence", defaultValue = "1.0")
    val confidence: Float = 1.0f,

    /**
     * v5: 事实来源。
     * - user_explicit: 用户明确陈述
     * - inferred: LLM 从上下文推断
     * - imported: 外部导入
     */
    @ColumnInfo(name = "source", defaultValue = "inferred")
    val source: String = "inferred",

    /**
     * v5: 事实过期时间 ISO 8601,可空。
     * 用于时间敏感事实(如临时安排)的自动衰减,优先级高于普通按创建时间衰减。
     */
    @ColumnInfo(name = "expires_at")
    val expiresAt: String? = null,

    /**
     * v5: 最近一次被用户或系统确认的时间 ISO 8601,可空。
     * 每次事实被对话引用并确认时更新,可重置其衰减时钟。
     */
    @ColumnInfo(name = "last_confirmed_at")
    val lastConfirmedAt: String? = null,

    /**
     * v7: 最近一次命中时间 ISO 8601,可空。
     * 事实被引用/确认/合并时更新,用于重置衰减时钟并享受 hitBonus 加成。
     */
    @ColumnInfo(name = "last_hit_at", defaultValue = "NULL")
    val lastHitAt: String? = null,

    /**
     * v8: 记忆作用域,用于隔离不同 Agent 的记忆。
     *  - "main":主助手作用域(默认),用户与主助手的对话事实
     *  - assistantId:子助手/团队成员作用域,用户与该子助手的对话事实
     * 查询时按 scope 过滤,避免子助手误用主助手的事实,也避免团队成员之间记忆混淆。
     */
    @ColumnInfo(name = "scope", defaultValue = "main")
    val scope: String = "main",
)
