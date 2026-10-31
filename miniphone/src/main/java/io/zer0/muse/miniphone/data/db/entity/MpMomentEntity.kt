package io.zer0.muse.miniphone.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import io.zer0.muse.miniphone.data.db.MpMomentSource

/**
 * 一条动态。
 *
 * [imagesJson] 是图片 uri 数组的 JSON 文本(空图为 `[]`),解码放到仓库层做,数据库层不解析。
 *
 * [visibleToJson] 是可见范围:逗号分隔的可见对象 id,空串 = 所有人可见。
 * 本阶段只写不读 —— 时间线查询不做过滤,「仅自己可见」的动态在自己这边的列表里照常显示,
 * 不要把它当成已经做了隔离。
 */
@Entity(
    tableName = "mp_moment",
    indices = [Index("createdAt"), Index("authorId")],
)
data class MpMomentEntity(
    @PrimaryKey val id: String,
    val authorId: String,
    val authorName: String,
    val content: String,
    val imagesJson: String = "[]",
    @ColumnInfo(defaultValue = "") val visibleToJson: String = "",
    val createdAt: Long,
    val source: MpMomentSource,
)
