package io.zer0.muse.miniphone.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 跑团的一次结局。一次存档至多一个结局，但同一剧本可以有多个结局。
 */
@Entity(
    tableName = "mp_rp_ending",
    indices = [Index("scriptId"), Index("saveId")],
)
data class MpRpEndingEntity(
    @PrimaryKey val id: String,
    val scriptId: String,
    val saveId: String,
    val title: String,
    val text: String,
    val createdAt: Long,
)
