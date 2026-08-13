package io.zer0.muse.data.patrol

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.serialization.Serializable

/**
 * v1.0.74: 主动巡检日志 — 记录每次巡检做了什么/没做什么。
 * AI 下次巡检时能看到,避免重复做同一件事(OpenHanako patrol-log 思路)。
 */
@Serializable
@Entity(tableName = "patrol_logs")
data class PatrolLogEntity(
    @PrimaryKey val id: String,
    /** 巡检时间戳。 */
    val timestamp: Long,
    /** 巡检结果: wrote_diary / organized_memories / sent_message / idle(无事) / error。 */
    val action: String,
    /** 做了什么(摘要,AI 可读)。 */
    val summary: String,
)

/** v1.0.74: 巡检日志 DAO。 */
@Dao
interface PatrolLogDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: PatrolLogEntity)

    @Query("SELECT * FROM patrol_logs ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<PatrolLogEntity>

    @Query("DELETE FROM patrol_logs WHERE timestamp < :before")
    suspend fun deleteBefore(before: Long)

    /** 某动作今天是否已做过(防重复)。 */
    @Query("SELECT COUNT(*) FROM patrol_logs WHERE action = :action AND timestamp >= :dayStart")
    suspend fun countActionToday(action: String, dayStart: Long): Int
}
