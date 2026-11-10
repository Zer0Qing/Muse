package io.zer0.muse.miniphone.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 关系网的一条有向边：[fromId] 怎么看 [toId]。
 *
 * 关系是单向的 —— 「A 认为 B 是挚友」不代表「B 也这么看 A」，所以两边各存一条。
 */
@Entity(
    tableName = "mp_relation_edge",
    indices = [Index("fromId"), Index("toId")],
)
data class MpRelationEdgeEntity(
    @PrimaryKey val id: String,
    val fromId: String,
    val toId: String,
    val label: String,
    @ColumnInfo(defaultValue = "''") val note: String = "",
    val updatedAt: Long,
)
