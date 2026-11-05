package io.zer0.memory.fact

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * v20(M3): 用户画像快照 —— 从事实账本定期投影出的「我了解到的你」。
 *
 * 一行一个段落(section),同 (scope, space_id, section) 唯一。与手填 `UserProfile`
 * (SettingsRepository 里的 DataStore 字段)是两回事:
 *  - 手填字段是用户声明的事实,永远优先;
 *  - 本表是系统从对话里**提炼**的画像,自动刷新,但可被用户覆盖/暂停/恢复。
 *
 * 设计约束:
 *  - 只存**结论行**(每条 ≤80 字)与来源事实 id 列表,不复制整段对话 —— 画像泄露面小于原文;
 *  - [autoContent] 由刷新器按规则生成;[manualContent] 为用户覆写(非空时注入优先用覆写);
 *  - [evidenceFingerprint] 为「来源事实 id + 内容摘要」的稳定哈希,指纹不变 = 无需重算;
 *  - 注入侧有硬预算(总字数上限),并在末尾声明「与当前对话冲突时以当前对话为准」。
 */
@Serializable
@Entity(
    tableName = "profile_snapshots",
    indices = [
        Index(value = ["scope", "space_id", "section"], unique = true, name = "index_profile_snapshots_scope_space_section"),
    ],
)
data class ProfileSnapshotEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** 记忆作用域(与 facts.scope 对齐,"main" = 主助手)。 */
    @ColumnInfo(name = "scope")
    val scope: String = "main",

    /** 记忆空间(与 facts.space_id 对齐)。 */
    @ColumnInfo(name = "space_id")
    val spaceId: String = "default",

    /** 段落名,见 [io.zer0.memory.fact.ProfileSection] 常量。 */
    @ColumnInfo(name = "section")
    val section: String,

    /** 自动生成的画像正文(markdown 列表,可空串 = 该段暂无内容)。 */
    @ColumnInfo(name = "auto_content", defaultValue = "")
    val autoContent: String = "",

    /** 用户覆写正文;非空时优先于 [autoContent] 注入。 */
    @ColumnInfo(name = "manual_content", defaultValue = "NULL")
    val manualContent: String? = null,

    /** 本段引用的来源事实 id(JSON 数组,记忆页「看依据」与指纹计算共用)。 */
    @ColumnInfo(name = "source_fact_ids", defaultValue = "[]")
    val sourceFactIds: String = "[]",

    /** 来源指纹(sorted id + 内容哈希);刷新时不变则跳过重算,只续 [lastRefreshAt]。 */
    @ColumnInfo(name = "evidence_fingerprint", defaultValue = "")
    val evidenceFingerprint: String = "",

    /** 内容最近一次实际变化时间 ISO 8601。 */
    @ColumnInfo(name = "updated_at")
    val updatedAt: String,

    /** 最近一次刷新检查时间(无论是否变化),TTL 判定用。 */
    @ColumnInfo(name = "last_refresh_at", defaultValue = "NULL")
    val lastRefreshAt: String? = null,
)

/**
 * 画像六段名。与段落语义一起构成「稳定在前、易变在后、不确定单独放」的注入顺序,
 * 不确定项绝不下沉到稳定段(临时状态写成永久事实是最伤信任的错法)。
 */
object ProfileSection {
    /** 用户希望被怎样称呼/定位(身份与设定)。 */
    const val IDENTITY_SETTINGS = "identity_settings"

    /** 关系设定(助手与用户的关系、边界)。 */
    const val RELATIONSHIP_SETTINGS = "relationship_settings"

    /** 长期稳定事实(职业、城市、过敏、长期目标)。 */
    const val STABLE_FACTS = "stable_facts"

    /** 交互偏好(回复长度、语气、语言、忌讳话题)。 */
    const val INTERACTION_PREFERENCES = "interaction_preferences"

    /** 近期互动(最近发生的事,时效性强)。 */
    const val RECENT_INTERACTIONS = "recent_interactions"

    /** 不确定笔记(低置信推测,仅提示参考,不作为事实)。 */
    const val UNCERTAIN_NOTES = "uncertain_notes"

    /** 注入顺序(也是刷新器的段落顺序)。 */
    val ORDER = listOf(
        IDENTITY_SETTINGS,
        RELATIONSHIP_SETTINGS,
        STABLE_FACTS,
        INTERACTION_PREFERENCES,
        RECENT_INTERACTIONS,
        UNCERTAIN_NOTES,
    )
}
