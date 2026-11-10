package io.zer0.muse.miniphone.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 漫游时在某个位置遇到的 AI 路人。[persona] 是一段性格设定，用来接着生成对话；
 * [characterId] 非空表示这一位其实是可深聊的角色，空串表示只属于这个位置。
 */
@Entity(
    tableName = "mp_roam_npc",
    indices = [Index("locationId")],
)
data class MpRoamNpcEntity(
    @PrimaryKey val id: String,
    val locationId: String,
    val name: String,
    @ColumnInfo(defaultValue = "''") val persona: String = "",
    @ColumnInfo(defaultValue = "''") val characterId: String = "",
    val metAt: Long,
)
