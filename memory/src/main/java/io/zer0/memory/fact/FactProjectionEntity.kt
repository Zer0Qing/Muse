package io.zer0.memory.fact

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 会话事实投影 generation。
 *
 * deep-memory 提取结果先写 staged，再按 source revision CAS 发布。
 * stale/被取代的 generation 标 ORPHANED，由 reconcile 清理。
 */
@Entity(
    tableName = "fact_projections",
    indices = [
        Index(value = ["session_id"], name = "index_fact_projections_session_id"),
        Index(value = ["state"], name = "index_fact_projections_state"),
    ],
)
data class FactProjectionEntity(
    @PrimaryKey
    @ColumnInfo(name = "generation_id")
    val generationId: String,

    @ColumnInfo(name = "session_id")
    val sessionId: String,

    @ColumnInfo(name = "source_revision")
    val sourceRevision: Long,

    @ColumnInfo(name = "scope")
    val scope: String,

    @ColumnInfo(name = "space_id")
    val spaceId: String,

    @ColumnInfo(name = "state")
    val state: String,

    @ColumnInfo(name = "created_at")
    val createdAt: String,

    @ColumnInfo(name = "updated_at")
    val updatedAt: String,
)

sealed interface ProjectionPublishResult {
    data class Published(val generationId: String, val sourceRevision: Long) : ProjectionPublishResult
    data class Superseded(val currentRevision: Long) : ProjectionPublishResult
    data class Missing(val generationId: String) : ProjectionPublishResult
}
