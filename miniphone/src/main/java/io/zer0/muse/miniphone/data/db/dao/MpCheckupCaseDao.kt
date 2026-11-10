package io.zer0.muse.miniphone.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.zer0.muse.miniphone.data.db.entity.MpCheckupCaseEntity
import kotlinx.coroutines.flow.Flow

/** 查岗案卷读写：按角色看历史、按时间看全部；结案时改状态与结论。 */
@Dao
interface MpCheckupCaseDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(case: MpCheckupCaseEntity)

    @Query("SELECT * FROM mp_checkup_case WHERE id = :id LIMIT 1")
    suspend fun get(id: String): MpCheckupCaseEntity?

    @Query("SELECT * FROM mp_checkup_case WHERE characterId = :characterId ORDER BY createdAt DESC")
    fun observeByCharacter(characterId: String): Flow<List<MpCheckupCaseEntity>>

    @Query("SELECT * FROM mp_checkup_case ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<MpCheckupCaseEntity>>

    @Query("UPDATE mp_checkup_case SET status = :status, summary = :summary, finishedAt = :finishedAt WHERE id = :id")
    suspend fun finish(id: String, status: String, summary: String, finishedAt: Long)

    @Query("DELETE FROM mp_checkup_case WHERE id = :id")
    suspend fun delete(id: String)
}
