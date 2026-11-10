package io.zer0.muse.miniphone.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.zer0.muse.miniphone.data.db.entity.MpOfflineMessageEntity
import kotlinx.coroutines.flow.Flow

/** 待应答队列读写：按会话看还欠几次应答，应答到达后按 id 删行。 */
@Dao
interface MpOfflineMessageDao {
    @Query("SELECT * FROM mp_offline_message WHERE threadId = :threadId ORDER BY createdAt ASC")
    fun observeByThread(threadId: String): Flow<List<MpOfflineMessageEntity>>

    /** 一次性取某会话还欠着应答的行：进程重建后的恢复用，不等 Flow 首次发射。 */
    @Query("SELECT * FROM mp_offline_message WHERE threadId = :threadId ORDER BY createdAt ASC")
    suspend fun listByThread(threadId: String): List<MpOfflineMessageEntity>

    @Query("SELECT * FROM mp_offline_message ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<MpOfflineMessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: MpOfflineMessageEntity)

    @Query("DELETE FROM mp_offline_message WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM mp_offline_message WHERE threadId = :threadId")
    suspend fun deleteByThread(threadId: String)
}
