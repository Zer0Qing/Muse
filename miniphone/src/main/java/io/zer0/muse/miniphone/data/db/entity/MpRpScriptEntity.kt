package io.zer0.muse.miniphone.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 跑团剧本库里的一份剧本。[body] 是开场与规则正文。
 */
@Entity(
    tableName = "mp_rp_script",
    indices = [Index("updatedAt")],
)
data class MpRpScriptEntity(
    @PrimaryKey val id: String,
    val title: String,
    val body: String,
    val createdAt: Long,
    val updatedAt: Long,
)
