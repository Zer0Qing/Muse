package io.zer0.memory.summary

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * 编译产物段落 (openhanako memory 目录下的 .md 文件 → Room)。
 *
 * 4 个固定 section: facts / today / week / longterm。
 * assemble 时拼接为 memory.md 注入 system prompt。
 *
 * Phase 7: @Serializable 用于备份导出/导入。
 */
@Serializable
@Entity(tableName = "compiled_sections")
data class CompiledSectionEntity(
    @PrimaryKey
    @ColumnInfo(name = "section_key")
    val sectionKey: String,  // facts / today / week / longterm

    @ColumnInfo(name = "content")
    val content: String = "",

    @ColumnInfo(name = "fingerprint")
    val fingerprint: String? = null,

    @ColumnInfo(name = "updated_at")
    val updatedAt: String,
)
