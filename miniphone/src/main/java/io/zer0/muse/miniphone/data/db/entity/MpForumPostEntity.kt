package io.zer0.muse.miniphone.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 论坛（公开信息流）里的一条发帖。[likeIdsJson] 是点赞者 id 数组的 JSON 文本，空为 `[]`。
 */
@Entity(
    tableName = "mp_forum_post",
    indices = [Index("createdAt"), Index("authorId")],
)
data class MpForumPostEntity(
    @PrimaryKey val id: String,
    val authorId: String,
    val authorName: String,
    val content: String,
    @ColumnInfo(defaultValue = "'[]'") val likeIdsJson: String = "[]",
    val createdAt: Long,
)
