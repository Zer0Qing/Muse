package io.zer0.muse.data.milestone

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MilestoneDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(milestone: MilestoneEntity): Long

    @Query("SELECT * FROM milestones ORDER BY created_at DESC")
    fun observeAll(): Flow<List<MilestoneEntity>>

    @Query("SELECT * FROM milestones ORDER BY created_at DESC")
    suspend fun getAll(): List<MilestoneEntity>

    @Query("SELECT * FROM milestones WHERE dismissed_at IS NULL ORDER BY created_at DESC")
    suspend fun getActive(): List<MilestoneEntity>

    @Query("SELECT * FROM milestones WHERE condition_type = :condition LIMIT 1")
    suspend fun findByCondition(condition: String): MilestoneEntity?

    @Query("UPDATE milestones SET dismissed_at = :now WHERE id = :id")
    suspend fun dismiss(id: Long, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM milestones WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM milestones")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM milestones")
    fun observeCount(): Flow<Int>

    @Query("DELETE FROM milestones")
    suspend fun deleteAll()
}
