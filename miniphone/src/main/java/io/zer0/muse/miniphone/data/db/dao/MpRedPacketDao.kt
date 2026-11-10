package io.zer0.muse.miniphone.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.zer0.muse.miniphone.data.db.entity.MpRedPacketEntity
import kotlinx.coroutines.flow.Flow

/** 群红包读写：按会话看红包，领取名单与状态整体覆盖。 */
@Dao
interface MpRedPacketDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(packet: MpRedPacketEntity)

    @Query("SELECT * FROM mp_red_packet WHERE id = :id LIMIT 1")
    suspend fun get(id: String): MpRedPacketEntity?

    @Query("SELECT * FROM mp_red_packet WHERE threadId = :threadId ORDER BY createdAt DESC")
    fun observeByThread(threadId: String): Flow<List<MpRedPacketEntity>>

    @Query("UPDATE mp_red_packet SET state = :state, claimedByJson = :claimedByJson WHERE id = :id")
    suspend fun setState(id: String, state: String, claimedByJson: String)

    @Query("DELETE FROM mp_red_packet WHERE threadId = :threadId")
    suspend fun deleteByThread(threadId: String)
}
