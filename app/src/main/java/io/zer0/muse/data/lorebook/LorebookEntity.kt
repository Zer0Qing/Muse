package io.zer0.muse.data.lorebook

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Lorebook(世界书)实体 — Phase 8.5。
 *
 * 独立编写实现: 关键词触发的上下文注入。
 * 当用户消息包含 [keywords] 中任一关键词时,把 [content] 作为 SYSTEM 上下文
 * 注入到对话前缀,让 AI "想起" 这个设定。常用于角色扮演/世界观设定。
 *
 * Phase 8.5 修复: 所有 NOT NULL 字段加 @ColumnInfo(defaultValue=...) 与 MIGRATION_4_5 SQL 对齐。
 */
@Serializable
@Entity(tableName = "lorebooks")
data class LorebookEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(defaultValue = "[]") val keywordsJson: String = "[]",
    @ColumnInfo(defaultValue = "") val content: String = "",
    @ColumnInfo(defaultValue = "0") val priority: Int = 0,
    @ColumnInfo(defaultValue = "1") val enabled: Boolean = true,
    @ColumnInfo(defaultValue = "0") val caseSensitive: Boolean = false,
    /**
     * v1.0.47: 全词匹配模式 — 关键词前后必须是非单词字符(或字符串边界)才命中,减少子串误触发。
     * 例如关键词"cat"在 wholeWord=true 时不会命中"category"。
     * 单词边界定义:\b(字母数字下划线 vs 非字母数字下划线/字符串边界)。
     * 向后兼容:默认 false,保持原 contains 行为。
     */
    @ColumnInfo(defaultValue = "0") val wholeWord: Boolean = false,
    @ColumnInfo(defaultValue = "after_system") val insertionPosition: String = "after_system",
    @ColumnInfo(defaultValue = "0") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = System.currentTimeMillis(),
)
