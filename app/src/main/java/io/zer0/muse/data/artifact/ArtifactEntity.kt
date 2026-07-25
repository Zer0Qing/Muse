package io.zer0.muse.data.artifact

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import io.zer0.muse.data.session.SessionEntity
import kotlinx.serialization.Serializable

/**
 * 会话产物(Artifact)实体。
 *
 * 从 AI 回复中抽取的独立内容块(代码/文档/HTML/SVG/图片等),
 * 以卡片形式嵌入会话,避免长文滚动。
 */
@Entity(
    tableName = "artifacts",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("sessionId"), Index("messageId")],
)
@Serializable
data class ArtifactEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val messageId: String,
    val title: String,
    val type: String,
    @ColumnInfo(defaultValue = "") val content: String = "",
    val language: String? = null,
    @ColumnInfo(defaultValue = "0") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = System.currentTimeMillis(),
)
