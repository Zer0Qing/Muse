package io.zer0.muse.miniphone.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import io.zer0.muse.miniphone.data.db.MpAlbumSource

/**
 * 相册里的一张图。
 *
 * [hidden] 为隐藏(不上墙), [favorite] 为收藏。
 */
@Entity(
    tableName = "mp_album",
    indices = [Index("createdAt"), Index("source")],
)
data class MpAlbumEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val uri: String,
    val source: MpAlbumSource,
    val hidden: Boolean = false,
    val favorite: Boolean = false,
    val createdAt: Long,
)
