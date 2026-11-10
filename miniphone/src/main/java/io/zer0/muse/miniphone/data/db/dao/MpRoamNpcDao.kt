package io.zer0.muse.miniphone.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.zer0.muse.miniphone.data.db.entity.MpRoamNpcEntity
import kotlinx.coroutines.flow.Flow

/** 漫游路人读写：按位置看这一带遇到过谁。 */
@Dao
interface MpRoamNpcDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(npc: MpRoamNpcEntity)

    @Query("SELECT * FROM mp_roam_npc WHERE locationId = :locationId ORDER BY metAt DESC")
    fun observeByLocation(locationId: String): Flow<List<MpRoamNpcEntity>>

    @Query("SELECT * FROM mp_roam_npc WHERE id = :id LIMIT 1")
    suspend fun get(id: String): MpRoamNpcEntity?

    @Query("DELETE FROM mp_roam_npc WHERE locationId = :locationId")
    suspend fun deleteByLocation(locationId: String)
}
