package io.zer0.muse.data.conversation

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import io.zer0.common.AppJson
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.Serializable

internal object ConversationSelectionCodec {
    private val serializer = MapSerializer(String.serializer(), String.serializer())

    fun encode(selection: Map<String, String>): String =
        AppJson.encodeToString(serializer, selection)

    fun decode(json: String): Map<String, String> =
        runCatching { AppJson.decodeFromString(serializer, json) }.getOrElse {
            throw IllegalArgumentException("invalid persisted conversation selection", it)
        }
}

@Entity(
    tableName = "conversation_selections",
    indices = [Index(value = ["sessionId", "revision"], unique = true)],
)
@Serializable
data class ConversationSelectionEntity(
    @PrimaryKey val selectionSetId: String,
    val sessionId: String,
    val revision: Long,
    val userSelectionJson: String,
    val assistantSelectionJson: String,
)

@Dao
interface ConversationSelectionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(selection: ConversationSelectionEntity)

    @Query("SELECT * FROM conversation_selections WHERE selectionSetId = :selectionSetId LIMIT 1")
    suspend fun find(selectionSetId: String): ConversationSelectionEntity?

    @Query(
        "SELECT * FROM conversation_selections " +
            "WHERE sessionId = :sessionId ORDER BY revision DESC LIMIT 1",
    )
    suspend fun findBySession(sessionId: String): ConversationSelectionEntity?

    @Query("SELECT * FROM conversation_selections")
    suspend fun getAll(): List<ConversationSelectionEntity>

    @Query("DELETE FROM conversation_selections")
    suspend fun deleteAll()
}
