package io.zer0.muse.miniphone.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一次查岗案卷：谁发起、查谁、看多深、走到哪一步。
 *
 * [direction] 取 "outgoing"（本机用户查角色）/ "incoming"（角色查本机用户）；
 * [intensity] 取 "light" / "standard" / "deep"，决定取证据的范围；
 * [status] 取 "running" / "done" / "failed"，失败也留案卷，不让用户只看到一个空列表。
 */
@Entity(
    tableName = "mp_checkup_case",
    indices = [Index("characterId"), Index("createdAt")],
)
data class MpCheckupCaseEntity(
    @PrimaryKey val id: String,
    val characterId: String,
    @ColumnInfo(defaultValue = "''") val initiatorId: String = "",
    val direction: String,
    val intensity: String,
    val status: String,
    @ColumnInfo(defaultValue = "''") val summary: String = "",
    val createdAt: Long,
    @ColumnInfo(defaultValue = "0") val finishedAt: Long = 0L,
)
