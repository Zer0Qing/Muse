package io.zer0.muse.miniphone.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一段铃声素材。[kind] 取 "voice" / "video"，决定它在哪一类通话里被选到；
 * [characterId] 为空串表示对所有角色通用。
 */
@Entity(
    tableName = "mp_ringtone",
    indices = [Index("characterId")],
)
data class MpRingtoneEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(defaultValue = "''") val characterId: String = "",
    val name: String,
    val uri: String,
    val kind: String,
    val createdAt: Long,
)
