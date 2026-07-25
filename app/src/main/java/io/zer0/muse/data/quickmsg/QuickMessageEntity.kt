package io.zer0.muse.data.quickmsg

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * QuickMessage(快捷消息)实体 — Phase 8.5。
 *
 * 独立编写实现: 预设消息模板,用户可一键插入输入框。
 * - 全局快捷消息(scope="global"): 所有 Assistant 共享
 * - Assistant 绑定快捷消息(scope="assistant"): 通过 Assistant.quickMessageIdsJson 关联
 *
 * Phase 8.5 修复: 所有 NOT NULL 字段加 @ColumnInfo(defaultValue=...) 与 MIGRATION_4_5 SQL 对齐。
 *
 * M-QM1: 加 @Index(scope, assistantId, enabled) 加速 observeForAssistant 查询
 * (WHERE scope='global' OR (scope='assistant' AND assistantId=:id)) AND enabled=1。
 */
@Serializable
@Entity(
    tableName = "quick_messages",
    indices = [Index(value = ["scope", "assistantId", "enabled"])],
)
data class QuickMessageEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(defaultValue = "") val content: String = "",
    @ColumnInfo(defaultValue = "global") val scope: String = "global",
    @ColumnInfo(defaultValue = "") val assistantId: String = "",
    @ColumnInfo(defaultValue = "0") val sortIndex: Int = 0,
    @ColumnInfo(defaultValue = "1") val enabled: Boolean = true,
    @ColumnInfo(defaultValue = "0") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = System.currentTimeMillis(),
)
