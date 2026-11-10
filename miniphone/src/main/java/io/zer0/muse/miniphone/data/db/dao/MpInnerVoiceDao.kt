package io.zer0.muse.miniphone.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.zer0.muse.miniphone.data.db.entity.MpInnerVoiceEntity
import kotlinx.coroutines.flow.Flow

/** 心声读写：按会话看伴随独白，按时间看历史；不写回主线消息表。 */
@Dao
interface MpInnerVoiceDao {
    @Query("SELECT * FROM mp_inner_voice WHERE threadId = :threadId ORDER BY createdAt ASC")
    fun observeByThread(threadId: String): Flow<List<MpInnerVoiceEntity>>

    @Query("SELECT * FROM mp_inner_voice WHERE messageId = :messageId LIMIT 1")
    suspend fun forMessage(messageId: String): MpInnerVoiceEntity?

    @Query("SELECT * FROM mp_inner_voice ORDER BY createdAt DESC LIMIT :limit")
    fun observeHistory(limit: Int): Flow<List<MpInnerVoiceEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: MpInnerVoiceEntity)

    @Query("DELETE FROM mp_inner_voice WHERE threadId = :threadId")
    suspend fun deleteByThread(threadId: String)
}
