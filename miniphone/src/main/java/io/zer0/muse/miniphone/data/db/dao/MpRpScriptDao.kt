package io.zer0.muse.miniphone.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.zer0.muse.miniphone.data.db.entity.MpRpScriptEntity
import kotlinx.coroutines.flow.Flow

/** 跑团剧本库读写。 */
@Dao
interface MpRpScriptDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(script: MpRpScriptEntity)

    @Query("SELECT * FROM mp_rp_script WHERE id = :id LIMIT 1")
    suspend fun get(id: String): MpRpScriptEntity?

    @Query("SELECT * FROM mp_rp_script ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<MpRpScriptEntity>>

    @Query("DELETE FROM mp_rp_script WHERE id = :id")
    suspend fun delete(id: String)
}
