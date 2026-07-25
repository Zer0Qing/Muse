package io.zer0.memory.summary

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface DailyStateDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DailyStateEntity)

    @Query("SELECT * FROM daily_state WHERE `key` = 'default'")
    suspend fun get(): DailyStateEntity?

    @Query("DELETE FROM daily_state")
    suspend fun deleteAll()
}
