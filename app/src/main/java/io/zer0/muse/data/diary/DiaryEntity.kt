package io.zer0.muse.data.diary

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.serialization.Serializable

/**
 * v1.0.74: AI 日记本 — 每天由 LLM 基于当天动态/记忆生成一篇日记。
 * date 为本地日期 "yyyy-MM-dd"。
 */
@Serializable
@Entity(tableName = "ai_diaries")
data class DiaryEntity(
    @PrimaryKey val date: String,
    /** 日记正文(LLM 生成)。 */
    val content: String,
    @ColumnInfo(defaultValue = "0") val createdAt: Long = 0,
)

/** v1.0.74: 日记 DAO。 */
@Dao
interface DiaryDao {

    @Query("SELECT * FROM ai_diaries WHERE date = :date LIMIT 1")
    suspend fun getByDate(date: String): DiaryEntity?

    /** 某月所有日记(月视图标记用)。 */
    @Query("SELECT * FROM ai_diaries WHERE date >= :monthStart AND date < :nextMonth")
    suspend fun getByMonth(monthStart: String, nextMonth: String): List<DiaryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(diary: DiaryEntity)

    @Query("DELETE FROM ai_diaries WHERE date = :date")
    suspend fun delete(date: String)
}
