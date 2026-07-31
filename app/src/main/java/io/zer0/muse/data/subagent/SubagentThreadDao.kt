package io.zer0.muse.data.subagent

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * v1.0.53: 子 agent 线程 DAO。
 */
@Dao
interface SubagentThreadDao {

    @Query("SELECT * FROM subagent_threads WHERE threadId = :threadId")
    suspend fun getById(threadId: String): SubagentThreadEntity?

    @Query("SELECT * FROM subagent_threads WHERE parentSessionId = :sessionId AND status = 'open' ORDER BY updatedAt DESC")
    suspend fun listOpenBySession(sessionId: String): List<SubagentThreadEntity>

    @Query("SELECT * FROM subagent_threads WHERE status = 'open' ORDER BY updatedAt DESC")
    suspend fun listAllOpen(): List<SubagentThreadEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SubagentThreadEntity)

    @Query("UPDATE subagent_threads SET status = 'closed', updatedAt = :now WHERE threadId = :threadId")
    suspend fun close(threadId: String, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM subagent_threads WHERE threadId = :threadId")
    suspend fun delete(threadId: String)
}
