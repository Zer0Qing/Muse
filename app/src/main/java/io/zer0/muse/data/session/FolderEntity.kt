package io.zer0.muse.data.session

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Phase 9.1 (M13): 会话文件夹实体。
 *
 * 用于将侧栏会话按文件夹分组。
 * - [id] 主键,字符串 UUID
 * - [name] 文件夹名称(用户可编辑)
 * - [sortIndex] 排序索引(小的在上)
 * - [createdAt] / [updatedAt] 时间戳
 * - [expanded] 侧栏展开状态(本地持久化,跨会话保留)
 *
 * 会话通过 [SessionEntity.folderId] 关联文件夹(null = 未分组,显示在"未分组"区)。
 */
@Serializable
@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(defaultValue = "0") val sortIndex: Int = 0,
    @ColumnInfo(defaultValue = "0") val createdAt: Long = 0,
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0,
    @ColumnInfo(defaultValue = "1") val expanded: Boolean = true,
    /** v1.107 冗余: 文件夹内会话数(避免列表页 COUNT,Repository 双写维护)。 */
    @ColumnInfo(defaultValue = "0") val sessionCount: Int = 0,
)
