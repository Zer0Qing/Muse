package io.zer0.muse.miniphone.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 论坛帖子下的一条评论。
 */
@Entity(
    tableName = "mp_forum_comment",
    indices = [Index("postId"), Index("createdAt")],
)
data class MpForumCommentEntity(
    @PrimaryKey val id: String,
    val postId: String,
    val authorId: String,
    val authorName: String,
    val content: String,
    val createdAt: Long,
)
