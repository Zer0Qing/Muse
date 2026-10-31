package io.zer0.muse.miniphone.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 一天的日记。[date] 为 `yyyy-MM-dd` 文本主键,一天一条。
 */
@Entity(tableName = "mp_diary")
data class MpDiaryEntity(
    @PrimaryKey val date: String,
    val content: String,
    val updatedAt: Long,
)
