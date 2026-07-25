package io.zer0.muse.data.stats

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * v1.107 自动备份日志。
 *
 * 记录每次自动备份的结果,用于备份历史查看和恢复。
 *  - backupPath: 备份文件绝对路径
 *  - fileSizeBytes: 备份文件大小
 *  - status: "success" / "failed"
 *  - errorMessage: 失败时的错误信息
 *  - messageCount: 备份时消息总数(用于追踪数据增长)
 *  - createdAt: 备份时间戳
 *
 * 备份策略(WorkManager 周期任务):
 *  - 频率: 每日 1 次(只在实际有新消息时才真正复制)
 *  - 保留: 最近 7 份,超出自动清理最旧的
 *  - 路径: app 内部存储 backups/ 目录
 */
@Entity(tableName = "auto_backup_log")
data class AutoBackupLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(defaultValue = "") val backupPath: String = "",
    @ColumnInfo(defaultValue = "0") val fileSizeBytes: Long = 0,
    @ColumnInfo(defaultValue = "success") val status: String = "success",
    @ColumnInfo(defaultValue = "") val errorMessage: String = "",
    @ColumnInfo(defaultValue = "0") val messageCount: Long = 0,
    @ColumnInfo(defaultValue = "0") val createdAt: Long = System.currentTimeMillis(),
)
