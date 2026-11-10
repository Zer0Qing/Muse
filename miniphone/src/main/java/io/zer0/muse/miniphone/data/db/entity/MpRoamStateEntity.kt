package io.zer0.muse.miniphone.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 某个角色当前所在的虚拟位置。角色 id 作主键：一人同时只在一个地方。
 */
@Entity(
    tableName = "mp_roam_state",
)
data class MpRoamStateEntity(
    @PrimaryKey val characterId: String,
    val locationId: String,
    val locationName: String,
    @ColumnInfo(defaultValue = "''") val note: String = "",
    val updatedAt: Long,
)
