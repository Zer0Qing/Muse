package io.zer0.muse.miniphone.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.zer0.muse.miniphone.data.db.entity.MpRpEndingEntity
import kotlinx.coroutines.flow.Flow

/** 跑团结局读写：按剧本看结局册，按存档看这一次走到了哪。 */
@Dao
interface MpRpEndingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(ending: MpRpEndingEntity)

    @Query("SELECT * FROM mp_rp_ending WHERE scriptId = :scriptId ORDER BY createdAt DESC")
    fun observeByScript(scriptId: String): Flow<List<MpRpEndingEntity>>

    @Query("SELECT * FROM mp_rp_ending WHERE saveId = :saveId LIMIT 1")
    suspend fun forSave(saveId: String): MpRpEndingEntity?

    @Query("DELETE FROM mp_rp_ending WHERE saveId = :saveId")
    suspend fun deleteBySave(saveId: String)
}
