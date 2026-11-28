package io.zer0.muse.data.media

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Persistent background generation task record for Media Studio & Chat media generation.
 *
 * 必须 @Serializable：备份 NDJSON 行直接序列化本实体，任务行要随备份迁移。
 */
@Serializable
@Entity(
    tableName = "media_gen_tasks",
    indices = [
        Index("kind"),
        Index("status"),
        Index("createdAt"),
    ],
)
data class MediaGenTaskEntity(
    @PrimaryKey val id: String,
    /** "IMAGE" or "VIDEO". */
    val kind: String,
    /** "QUEUED" / "RUNNING" / "SUCCEEDED" / "FAILED" / "CANCELLED". */
    val status: String,
    val prompt: String,
    val providerId: String,
    val modelId: String,
    val paramsJson: String = "{}",
    val referenceImagePath: String? = null,
    val elapsedSeconds: Int = 0,
    val errorMessage: String? = null,
    val source: String = SOURCE_STUDIO,
    val createdAt: Long,
    val updatedAt: Long,
) {
    companion object {
        const val KIND_IMAGE = "IMAGE"
        const val KIND_VIDEO = "VIDEO"

        const val STATUS_QUEUED = "QUEUED"
        const val STATUS_RUNNING = "RUNNING"
        const val STATUS_SUCCEEDED = "SUCCEEDED"
        const val STATUS_FAILED = "FAILED"
        const val STATUS_CANCELLED = "CANCELLED"

        const val SOURCE_STUDIO = "studio"
        const val SOURCE_CHAT = "chat"
        const val SOURCE_TOOL = "tool"
    }
}

/**
 * Persistent generated image/video asset backed by local app-private files.
 *
 * 同 [MediaGenTaskEntity]：资产行（含收藏标记与提示词）必须能随备份写出。
 */
@Serializable
@Entity(
    tableName = "media_gen_assets",
    indices = [
        Index("taskId"),
        Index("kind"),
        Index("createdAt"),
    ],
)
data class MediaGenAssetEntity(
    @PrimaryKey val id: String,
    val taskId: String,
    /** "IMAGE" or "VIDEO". */
    val kind: String,
    val localPath: String,
    val thumbnailPath: String? = null,
    val remoteUrl: String? = null,
    val mimeType: String,
    val width: Int = 0,
    val height: Int = 0,
    val durationMs: Long = 0L,
    val prompt: String,
    val modelId: String,
    val providerId: String,
    val paramsJson: String = "{}",
    val favorite: Boolean = false,
    val createdAt: Long,
)
