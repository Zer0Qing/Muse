package io.zer0.muse.data.promptinjection

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * PromptInjection(模式注入)实体 — Phase 8.5。
 *
 * 独立编写实现: 根据对话模式注入对应提示词。
 * 与 Lorebook 区别: Lorebook 是关键词触发,PromptInjection 是模式开关触发
 * (用户在会话中切换模式时,对应注入立即生效,无需关键词)。
 *
 * Phase 8.5 修复: 所有 NOT NULL 字段加 @ColumnInfo(defaultValue=...) 与 MIGRATION_4_5 SQL 对齐。
 *
 * M-PID1: 加 @Index(mode, enabled) 加速按模式查询启用条目(getEnabledByMode / observeAll)。
 * L-PID7: mode/insertionPosition 为自由 String,这里提供 insertionPosition 的合法常量;
 *         mode 由 PromptInjectionRepository.PRESET_MODES 约定(允许自定义,不强校验)。
 */
@Serializable
@Entity(
    tableName = "prompt_injections",
    indices = [Index(value = ["mode", "enabled"])],
)
data class PromptInjectionEntity(
    @PrimaryKey val id: String,
    val name: String,
    val mode: String,
    @ColumnInfo(defaultValue = "") val displayName: String = "",
    @ColumnInfo(defaultValue = "") val content: String = "",
    @ColumnInfo(defaultValue = "0") val priority: Int = 0,
    @ColumnInfo(defaultValue = "1") val enabled: Boolean = true,
    @ColumnInfo(defaultValue = "after_system") val insertionPosition: String = "after_system",
    @ColumnInfo(defaultValue = "0") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = System.currentTimeMillis(),
) {
    companion object {
        /** insertionPosition 合法值常量(L-PID7)。注:PromptInjectionTransformer 仅支持 before_system/after_system,不支持 before_last。 */
        const val INSERTION_BEFORE_SYSTEM = "before_system"
        const val INSERTION_AFTER_SYSTEM = "after_system"

        /** insertionPosition 合法值集合;Repository.upsert 会据此校正非法值。 */
        val VALID_INSERTION_POSITIONS = setOf(INSERTION_BEFORE_SYSTEM, INSERTION_AFTER_SYSTEM)
    }
}
