package io.zer0.muse.proactive

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import io.zer0.muse.data.session.SessionEntity
import kotlinx.serialization.Serializable

/**
 * Per-session mute for proactive messages.
 *
 * This is intentionally separate from SessionEntity: muting one conversation
 * must never rewrite the global proactive configuration or another session.
 */
@Entity(
    tableName = "session_proactive_preferences",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["sessionId"], unique = true)],
)
@Serializable
data class SessionProactivePreferenceEntity(
    @PrimaryKey val sessionId: String,
    @ColumnInfo(defaultValue = "0") val muted: Boolean = false,
)

@Dao
interface SessionProactivePreferenceDao {
    @Upsert
    suspend fun upsert(preference: SessionProactivePreferenceEntity)

    @Query("SELECT muted FROM session_proactive_preferences WHERE sessionId = :sessionId LIMIT 1")
    suspend fun muted(sessionId: String): Boolean?

    @Query("SELECT sessionId FROM session_proactive_preferences WHERE muted = 1")
    suspend fun mutedSessionIds(): List<String>

    @Query("DELETE FROM session_proactive_preferences WHERE sessionId = :sessionId")
    suspend fun delete(sessionId: String)

    @Query("SELECT * FROM session_proactive_preferences")
    suspend fun getAll(): List<SessionProactivePreferenceEntity>

    @Query("DELETE FROM session_proactive_preferences")
    suspend fun deleteAll()
}

class ProactiveSessionPreferenceRepository(
    private val dao: SessionProactivePreferenceDao,
) {
    suspend fun isMuted(sessionId: String): Boolean = dao.muted(sessionId) == true

    suspend fun setMuted(sessionId: String, muted: Boolean) {
        if (muted) {
            dao.upsert(SessionProactivePreferenceEntity(sessionId = sessionId, muted = true))
        } else {
            dao.delete(sessionId)
        }
    }

    suspend fun mutedSessionIds(): Set<String> = dao.mutedSessionIds().toSet()
}
