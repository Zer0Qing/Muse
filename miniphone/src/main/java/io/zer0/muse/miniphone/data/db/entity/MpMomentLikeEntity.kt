package io.zer0.muse.miniphone.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import io.zer0.muse.miniphone.data.db.MpActorType

/**
 * 动态点赞。同一个人对同一条动态只能点一次,故用三列复合主键。
 */
@Entity(
    tableName = "mp_moment_like",
    primaryKeys = ["momentId", "likerType", "likerId"],
    indices = [Index("momentId")],
)
data class MpMomentLikeEntity(
    val momentId: String,
    val likerType: MpActorType,
    val likerId: String,
    val likerName: String,
    val createdAt: Long,
)
