package io.zer0.muse.miniphone.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一个节日或纪念日。[dateKey] 为 `MM-dd`（每年重复）或 `yyyy-MM-dd`（只发生一次）；
 * [year] 为 0 表示每年重复，非 0 表示那一年的具体日子。
 */
@Entity(
    tableName = "mp_holiday_event",
    indices = [Index("dateKey")],
)
data class MpHolidayEventEntity(
    @PrimaryKey val id: String,
    val dateKey: String,
    val name: String,
    @ColumnInfo(defaultValue = "''") val note: String = "",
    @ColumnInfo(defaultValue = "0") val year: Int = 0,
)
