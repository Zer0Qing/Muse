package io.zer0.muse.data.session

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/** 当前会话分支头和新提交序的持久化游标。 */
@Entity(
    tableName = "session_branch_heads",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class SessionBranchHeadEntity(
    @PrimaryKey val sessionId: String,
    @ColumnInfo(defaultValue = "NULL") val headMessageId: String? = null,
    @ColumnInfo(defaultValue = "1") val nextCommitSeq: Long = 1,
    @ColumnInfo(defaultValue = "0") val projectionVersion: Long = 0,
    val updatedAt: Long,
)

@Dao
interface SessionBranchHeadDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(head: SessionBranchHeadEntity)

    @Query("SELECT * FROM session_branch_heads WHERE sessionId = :sessionId LIMIT 1")
    suspend fun get(sessionId: String): SessionBranchHeadEntity?

    @Query("UPDATE session_branch_heads SET headMessageId = :headMessageId, nextCommitSeq = :nextCommitSeq, projectionVersion = projectionVersion + 1, updatedAt = :updatedAt WHERE sessionId = :sessionId")
    suspend fun updateHead(sessionId: String, headMessageId: String?, nextCommitSeq: Long, updatedAt: Long): Int

    @Query("DELETE FROM session_branch_heads WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: String)
}
