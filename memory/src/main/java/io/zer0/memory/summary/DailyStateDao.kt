package io.zer0.memory.summary

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface DailyStateDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DailyStateEntity)

    /**
     * v2.6.2（记忆审计）: 按 target key 读取进度。
     *
     * 旧实现固定读 `key='default'` 单行 —— 主助手 / 子助手 / 不同 Space 的每日编译
     * 共用同一行，切换 Space 后当天会跳过已完成的步骤、或复用另一 target 的 completed steps，
     * 导致新 Space 的 daily/week/longterm/facts 永远不编译（记忆随机缺失）。
     */
    @Query("SELECT * FROM daily_state WHERE `key` = :key")
    suspend fun get(key: String): DailyStateEntity?

    /** v2.6.2: 读取全部 target 的进度（备份导出用）。 */
    @Query("SELECT * FROM daily_state")
    suspend fun getAll(): List<DailyStateEntity>

    @Query("DELETE FROM daily_state")
    suspend fun deleteAll()
}
