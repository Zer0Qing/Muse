package io.zer0.muse.miniphone.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import io.zer0.muse.miniphone.data.db.MpActorType

/**
 * 动态评论。
 *
 * [parentId] 为空表示直接评论动态;不为空表示回复同一动态下的另一条评论。
 */
@Entity(
    tableName = "mp_moment_comment",
    indices = [Index("momentId"), Index("parentId")],
)
data class MpMomentCommentEntity(
    @PrimaryKey val id: String,
    val momentId: String,
    val parentId: String? = null,
    val senderType: MpActorType,
    val senderId: String,
    val senderName: String,
    val content: String,
    val createdAt: Long,
)
