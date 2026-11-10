package io.zer0.muse.miniphone.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.zer0.muse.miniphone.data.db.entity.MpCallSessionEntity
import kotlinx.coroutines.flow.Flow

/** 通话记录读写：按会话看通话时间线，按时间看全局历史；不写回消息表。 */
@Dao
interface MpCallSessionDao {
    @Query("SELECT * FROM mp_call_session WHERE threadId = :threadId ORDER BY startedAt ASC")
    fun observeByThread(threadId: String): Flow<List<MpCallSessionEntity>>

    @Query("SELECT * FROM mp_call_session ORDER BY startedAt DESC LIMIT :limit")
    fun observeHistory(limit: Int): Flow<List<MpCallSessionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: MpCallSessionEntity)

    @Query("DELETE FROM mp_call_session WHERE threadId = :threadId")
    suspend fun deleteByThread(threadId: String)
}
