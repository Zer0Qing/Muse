package io.zer0.muse.worldbook

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * P1-2: Worldbook(动态世界书)实体。
 *
 * 与现有 [io.zer0.muse.data.lorebook.LorebookEntity] 的区别:
 *  - Lorebook 走 Transformer 管道,仅扫描最后一条 USER 消息,注入位置有限(before/after_system/before_last)
 *  - Worldbook 走 Hook 系统(SystemPromptComposeHook + PromptFinalizeHook),支持:
 *    · alwaysActive 常驻激活(无需关键词命中)
 *    · scanDepth 扫描最近 N 层 USER 消息(非仅最后一条)
 *    · isRegex 正则关键词(配合 caseSensitive)
 *    · assistantId 绑定特定助手(null = 全局)
 *    · AT_DEPTH 深度注入(插入到历史第 N 层,而非仅系统提示前后)
 *
 * 两者并存,互不干扰:LorebookTransformer 在管道内执行,WorldBookHook 在管道后执行。
 *
 * 所有 NOT NULL 字段均带 @ColumnInfo(defaultValue=...),与 MIGRATION_57_58 SQL 对齐。
 */
@Serializable
@Entity(tableName = "worldbook_entries")
data class WorldBookEntryEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(defaultValue = "[]") val keywordsJson: String = "[]",
    @ColumnInfo(defaultValue = "") val content: String = "",
    @ColumnInfo(defaultValue = "50") val priority: Int = 50,
    @ColumnInfo(defaultValue = "1") val enabled: Boolean = true,
    @ColumnInfo(defaultValue = "0") val caseSensitive: Boolean = false,
    /** 关键词是否按正则表达式匹配(isRegex=true 时 keywordsJson 每项视为正则源串)。 */
    @ColumnInfo(defaultValue = "0") val isRegex: Boolean = false,
    /** 常驻激活:无需关键词命中即注入到系统提示。 */
    @ColumnInfo(defaultValue = "0") val alwaysActive: Boolean = false,
    /** 扫描最近 N 层 USER 消息(1 = 仅最后一条,与 Lorebook 行为一致)。 */
    @ColumnInfo(defaultValue = "3") val scanDepth: Int = 3,
    /**
     * 注入目标(对应 [WorldBookInjectTarget]):
     *  - system: 注入到 SYSTEM 消息(默认)
     *  - user:   注入为 USER 消息前缀
     *  - assistant: 注入为 ASSISTANT 消息前缀
     */
    @ColumnInfo(defaultValue = "system") val injectTarget: String = "system",
    /**
     * 注入位置(对应 [WorldBookInjectPosition]):
     *  - prepend:  插到目标角色消息之前
     *  - append:   追加到目标角色消息之后
     *  - at_depth:  插入到历史第 [insertionDepth] 层 USER 消息处
     */
    @ColumnInfo(defaultValue = "append") val injectPosition: String = "append",
    /** injectPosition=at_depth 时,插入到倒数第 N 层 USER 消息处(1 = 最后一层)。 */
    @ColumnInfo(defaultValue = "0") val insertionDepth: Int = 0,
    /** 绑定特定助手(null = 全局,所有助手生效)。 */
    @ColumnInfo(defaultValue = "NULL") val assistantId: String? = null,
    @ColumnInfo(defaultValue = "0") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * 注入目标角色。
 */
enum class WorldBookInjectTarget(val storage: String) {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant");

    companion object {
        fun fromStorage(raw: String?): WorldBookInjectTarget =
            entries.firstOrNull { it.storage == raw } ?: SYSTEM
    }
}

/**
 * 注入位置策略。
 */
enum class WorldBookInjectPosition(val storage: String) {
    PREPEND("prepend"),
    APPEND("append"),
    AT_DEPTH("at_depth");

    companion object {
        fun fromStorage(raw: String?): WorldBookInjectPosition =
            entries.firstOrNull { it.storage == raw } ?: APPEND
    }
}
