package io.zer0.memory.dream

import androidx.room.Entity

@Entity(
    tableName = "memory_dream_revisions",
    primaryKeys = ["revisionId", "assistantId", "scope", "spaceId"],
)
data class MemoryDreamRevisionEntity(
    val revisionId: String,
    val assistantId: String,
    val scope: String,
    val spaceId: String,
    val trigger: String,
    val beforeFingerprint: String,
    val beforeJson: String,
    val nextJson: String,
    val publishedAt: String,
)

@Entity(tableName = "memory_dream_states", primaryKeys = ["assistantId", "scope", "spaceId"])
data class MemoryDreamStateEntity(
    val assistantId: String,
    val scope: String,
    val spaceId: String,
    val running: Boolean,
    val runId: String?,
    val lastRevisionId: String?,
    val activeRevisionId: String?,
    val lastError: String?,
)
