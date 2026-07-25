package io.zer0.muse.data.audit

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/**
 * P2-4 审计日志 DAO。
 *
 * - [insert]: 写入一条审计日志
 * - [query]: 按时间倒序分页查询
 * - [count]: 总条数(用于环形缓冲检查)
 * - [deleteOldest]: 删除最旧的 n 条(环形缓冲超限时调用)
 * - [deleteOlderThan]: 删除指定时间戳之前的日志(启动时按保留期清理)
 * - [deleteAll]: 清空全部日志(用户在审计日志页点击"清空"时调用)
 */
@Dao
interface AuditLogDao {

    @Insert
    suspend fun insert(log: AuditLogEntity): Long

    @Query("SELECT * FROM audit_log ORDER BY timestamp DESC LIMIT :limit OFFSET :offset")
    suspend fun query(limit: Int, offset: Int): List<AuditLogEntity>

    @Query("SELECT COUNT(*) FROM audit_log")
    suspend fun count(): Int

    @Query("DELETE FROM audit_log WHERE id IN (SELECT id FROM audit_log ORDER BY timestamp ASC LIMIT :n)")
    suspend fun deleteOldest(n: Int)

    @Query("DELETE FROM audit_log WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("DELETE FROM audit_log")
    suspend fun deleteAll()
}
