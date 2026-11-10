package io.zer0.muse.miniphone.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.zer0.muse.miniphone.data.db.entity.MpHolidayEventEntity
import kotlinx.coroutines.flow.Flow

/** 节日读写：按日期键查当天有没有节日。 */
@Dao
interface MpHolidayEventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: MpHolidayEventEntity)

    @Query("SELECT * FROM mp_holiday_event WHERE dateKey = :dateKey ORDER BY year DESC")
    fun observeByDate(dateKey: String): Flow<List<MpHolidayEventEntity>>

    @Query("SELECT * FROM mp_holiday_event ORDER BY dateKey ASC")
    fun observeAll(): Flow<List<MpHolidayEventEntity>>

    @Query("DELETE FROM mp_holiday_event WHERE id = :id")
    suspend fun delete(id: String)
}
