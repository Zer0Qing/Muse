package io.zer0.memory.fact

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * 元事实实体。
 *
 * v3 schema: 移除 search_text 字段(不再用 FTS5,无需 n-gram 索引列)。
 * v4 schema: 新增 importance 字段(0=普通,1=重要,2=关键),关键事实永不衰减。
 * v8 schema: 新增 scope 字段(记忆作用域,默认 "main" 表示主助手作用域,
 *   子助手/团队成员使用各自的 assistantId),用于隔离不同 Agent 的记忆。
 * v9 schema: 新增 space_id 字段(记忆空间,默认 "default" 表示默认空间),
 *   用于多 Space 隔离,与 scope 正交。
 * v12 schema: 新增 entity_key 字段(实体归一化键)。同一实体的不同写法
 *   (如"张三"/"张先生"/"张三老师")共享同一 entity_key,用于精确去重与
 *   跨写法合并,解决"同一用户名 3 条重复记忆"问题。历史数据为 null,
 *   由反思任务在整理时回填。
 * v17 schema: 新增 score / score_updated_at / suppressed_at 字段 —— 衰减从"到期删除"改为"分数出场"。
 *   低分记忆只是退出召回(行保留、可回拨),清理必须走显式删除入口。
 * v18 schema: 新增 supersedes_id 字段 —— 新记忆指向它取代的旧记忆。旧行保留可查,
 *   只在编译上下文里让位;证据不足时不写入本列,两条都留在上下文由模型判断。
 * v19 schema: 新增 merged_into_id 字段 —— 本条被合并进了哪条记忆。被指向的新记忆承载
 *   去重后整合的正文,指向方(原始行)保留可查,但不参与自动召回、不进编译投影。
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
        Index(value = ["space_id"], name = "index_facts_space_id"),
        // v12 修复: entity_key 索引必须与 MIGRATION_11_12 的 CREATE INDEX 一致。
        // 此前注解遗漏该索引,而迁移里建了它 → 真机 Room 打开时 schema 校验失败
        // (Migration didn't properly handle: facts),记忆中心一点开就崩溃。
        Index(value = ["entity_key"], name = "idx_facts_entity_key"),
        // v16（记忆审计）: 复合索引 — 绝大多数查询按 (scope, space_id) 过滤。
        Index(value = ["scope", "space_id"], name = "index_facts_scope_space_id"),
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
    /** v14: 被检索命中的累计次数（判断这条记忆是否在真正干活）。 */
    @ColumnInfo(name = "hit_count", defaultValue = "0")
    val hitCount: Int = 0,

    /**
     * v8: 记忆作用域,用于隔离不同 Agent 的记忆。
     *  - "main":主助手作用域(默认),用户与主助手的对话事实
     *  - assistantId:子助手/团队成员作用域,用户与该子助手的对话事实
     * 查询时按 scope 过滤,避免子助手误用主助手的事实,也避免团队成员之间记忆混淆。
     */
    @ColumnInfo(name = "scope", defaultValue = "main")
    val scope: String = "main",

    /**
     * v9: 记忆空间 id,用于多 Space 隔离。
     *  - "default":默认空间(兼容旧数据)
     *  - 自定义 id:用户创建的工作/生活/学习等空间
     * 与 [scope] 正交:scope 按 Agent 隔离,spaceId 按场景隔离。
     * 一个 fact 既属于某 Agent scope,也属于某 Space。
     */
    @ColumnInfo(name = "space_id", defaultValue = "default")
    val spaceId: String = "default",

    /**
     * B4-05: 手动置顶时间 ISO 8601,null 表示未置顶。
     * 列表按置顶优先排序,注入提示词时置顶记忆优先。
     */
    @ColumnInfo(name = "pinned_at", defaultValue = "NULL")
    val pinnedAt: String? = null,

    /**
     * v12: 实体归一化键。同一实体不同写法的规范名。
     *  - 由提取阶段 LLM 给出(如 entity_key="张三",aliases=["张先生","张三老师"])
     *  - 空/未提取时由 FactStore 从事实文本推断(首个人名/专名或归一化文本)
     *  - 用于写入时精确查重与反思任务的跨写法合并
     */
    @ColumnInfo(name = "entity_key")
    val entityKey: String? = null,

    /**
     * v17: 持久化的衰减分数 —— "这条记忆还在不在场"的判定依据。
     *
     * 此前分数每次现算(按年龄推),没有落库,衰减的唯一后果就是删行;
     * 现在分数随每日衰减结算写回本列,阈值判定因此有稳定依据:
     *  - 新记忆从 [io.zer0.memory.ticker.MemoryConfig.baseImportance](默认 10.0)起算
     *  - 每次结算按 exp(-λ·天数) 递减,λ = decayPerDay × forgetSpeed
     *  - 被命中/合并时加 hitBonus,上限 baseImportance + hitBonus
     */
    @ColumnInfo(name = "score", defaultValue = "10.0")
    val score: Float = 10f,

    /**
     * v17: 上一次衰减结算时间 ISO 8601 —— 持久分数的衰减时钟。
     *
     * 增量结算靠本列才不自相矛盾:每轮只按"上次结算到现在的天数"递减,
     * 而不是每次都从 created_at 重算。null 表示从未结算,回退到 last_hit_at / created_at。
     */
    @ColumnInfo(name = "score_updated_at", defaultValue = "NULL")
    val scoreUpdatedAt: String? = null,

    /**
     * v17: 出场时间 ISO 8601 —— null 表示仍在召回中。
     *
     * 分数低于 compileThreshold 时由衰减结算写入本列:该记忆不再参与检索与上下文注入,
     * 但整行保留,后续被引用/显式检索时清除本列即可回到召回范围。
     */
    @ColumnInfo(name = "suppressed_at", defaultValue = "NULL")
    val suppressedAt: String? = null,

    /**
     * v18: 被本条记忆取代的旧记忆 id —— null 表示本条不取代任何记忆。
     *
     * 写入时若判定本条与某条旧记忆在同一实体上断言相反,把旧记忆 id 记在这里:
     * 旧行不删(记忆页仍可见、可回退),但编译产物(FACTS 段)不再把它写进上下文。
     * 判定证据不足时留空 —— 两条都进上下文,由模型自己看出冲突。
     */
    @ColumnInfo(name = "supersedes_id", defaultValue = "NULL")
    val supersedesId: Long? = null,

    /**
     * v19: 本条记忆被合并进了哪条记忆 —— null 表示未被合并。
     *
     * 与 [supersedesId] 是两种不同的关系,不要混用:
     *  - [supersedesId]: 新记忆取代了旧记忆的**内容**(两条断言矛盾,新的是对的)
     *  - [mergedIntoId]: 本条被**合并进了**另一条(两条不矛盾,只是重复,聚合后只留一份)
     * 同一行不会同时带两个指向: 一条记忆要么被取代(内容作废),要么被合并(内容已并入他条)。
     *
     * 语义方向: 指向合并后生成的那条新事实的 id。新事实本身不带本列值,
     * 聚合关系由被指向方反向查(见 FactStore.getMergedChildren)。
     * 行不删、记忆页可见,只是不参与自动召回、不进编译投影;删掉新事实即自动撤销合并。
     */
    @ColumnInfo(name = "merged_into_id", defaultValue = "NULL")
    val mergedIntoId: Long? = null,
)
