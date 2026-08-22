package io.zer0.memory.summary

import androidx.room.ColumnInfo
import androidx.room.Entity
import kotlinx.serialization.Serializable

/**
 * 按 scope + space 隔离的编译产物。
 * 旧 compiled_sections 保留作为升级兼容回退，不在迁移中删除。
 */
@Serializable
@Entity(
    tableName = "compiled_sections_scoped",
    primaryKeys = ["section_key", "scope", "space_id"],
)
data class ScopedCompiledSectionEntity(
    @ColumnInfo(name = "section_key")
    val sectionKey: String,
    @ColumnInfo(name = "scope")
    val scope: String = "main",
    @ColumnInfo(name = "space_id")
    val spaceId: String = "default",
    @ColumnInfo(name = "content")
    val content: String = "",
    @ColumnInfo(name = "fingerprint")
    val fingerprint: String? = null,
    @ColumnInfo(name = "updated_at")
    val updatedAt: String,
)
