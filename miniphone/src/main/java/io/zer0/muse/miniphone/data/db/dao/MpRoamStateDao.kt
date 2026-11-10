package io.zer0.muse.miniphone.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.zer0.muse.miniphone.data.db.entity.MpRoamStateEntity
import kotlinx.coroutines.flow.Flow

/** 漫游位置读写：一人一处，整体覆盖。 */
@Dao
interface MpRoamStateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(state: MpRoamStateEntity)

    @Query("SELECT * FROM mp_roam_state WHERE characterId = :characterId LIMIT 1")
    suspend fun get(characterId: String): MpRoamStateEntity?

    @Query("SELECT * FROM mp_roam_state ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<MpRoamStateEntity>>

    @Query("DELETE FROM mp_roam_state WHERE characterId = :characterId")
    suspend fun delete(characterId: String)
}
