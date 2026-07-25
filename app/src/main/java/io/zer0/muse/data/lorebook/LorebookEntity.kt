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
    @ColumnInfo(defaultValue = "after_system") val insertionPosition: String = "after_system",
    @ColumnInfo(defaultValue = "0") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = System.currentTimeMillis(),
)
