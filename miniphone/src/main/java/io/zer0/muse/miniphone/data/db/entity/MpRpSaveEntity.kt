package io.zer0.muse.miniphone.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 跑团的一次存档。[stateJson] 是局面快照（在场人物、已发生的转折、骰果），
 * [turn] 走到第几回合；结局另存一张表，存档本身不假设一定走到结局。
 */
@Entity(
    tableName = "mp_rp_save",
    indices = [Index("scriptId"), Index("updatedAt")],
)
data class MpRpSaveEntity(
    @PrimaryKey val id: String,
    val scriptId: String,
    val title: String,
    val stateJson: String,
    @ColumnInfo(defaultValue = "0") val turn: Int = 0,
    val updatedAt: Long,
)
