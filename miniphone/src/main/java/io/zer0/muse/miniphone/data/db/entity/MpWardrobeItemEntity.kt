package io.zer0.muse.miniphone.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 衣橱里的一件衣服。[kind] 取 "top" / "bottom" / "dress" / "shoes" / "accessory"；
 * [equipped] 为该角色是否正穿着这一件，同一 kind 只应有一件为真。
 */
@Entity(
    tableName = "mp_wardrobe_item",
    indices = [Index("characterId")],
)
data class MpWardrobeItemEntity(
    @PrimaryKey val id: String,
    val characterId: String,
    val name: String,
    val kind: String,
    val uri: String,
    @ColumnInfo(defaultValue = "0") val equipped: Int = 0,
    val updatedAt: Long,
)
