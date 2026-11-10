package io.zer0.muse.miniphone.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.zer0.muse.miniphone.data.db.entity.MpRpSaveEntity
import kotlinx.coroutines.flow.Flow

/** 跑团存档读写：按剧本看存档，回合与局面整体覆盖。 */
@Dao
interface MpRpSaveDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(save: MpRpSaveEntity)

    @Query("SELECT * FROM mp_rp_save WHERE id = :id LIMIT 1")
    suspend fun get(id: String): MpRpSaveEntity?

    @Query("SELECT * FROM mp_rp_save WHERE scriptId = :scriptId ORDER BY updatedAt DESC")
    fun observeByScript(scriptId: String): Flow<List<MpRpSaveEntity>>

    @Query("SELECT * FROM mp_rp_save ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<MpRpSaveEntity>>

    @Query("DELETE FROM mp_rp_save WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM mp_rp_save WHERE scriptId = :scriptId")
    suspend fun deleteByScript(scriptId: String)
}
