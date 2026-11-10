package io.zer0.muse.miniphone.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 群红包。[state] 取 "open" / "claimed" / "expired"；[claimedByJson] 是领取者 id 数组的
 * JSON 文本，空为 `[]`。金额只是 RP 展示文案，不做任何真实账务，也不写进任何支付通道。
 */
@Entity(
    tableName = "mp_red_packet",
    indices = [Index("threadId"), Index("createdAt")],
)
data class MpRedPacketEntity(
    @PrimaryKey val id: String,
    val threadId: String,
    val senderId: String,
    val amount: String,
    @ColumnInfo(defaultValue = "''") val note: String = "",
    val state: String,
    @ColumnInfo(defaultValue = "'[]'") val claimedByJson: String = "[]",
    val createdAt: Long,
)
