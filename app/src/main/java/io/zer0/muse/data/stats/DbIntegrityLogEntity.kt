package io.zer0.muse.data.stats

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * v1.107 数据库完整性校验日志。
 *
 * 每次启动时执行 PRAGMA integrity_check,记录结果到本表。
 *  - status: "ok" / "error"
 *  - details: 校验输出(ok 时为 "ok",error 时为完整错误信息)
 *  - dbSizeBytes: 启动时数据库文件大小(用于追踪增长趋势)
 *  - checkedAt: 校验时间戳
 *
 * 若发现 error,UI 层可提示用户从自动备份恢复。
 */
@Entity(tableName = "db_integrity_log")
data class DbIntegrityLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(defaultValue = "ok") val status: String = "ok",
    @ColumnInfo(defaultValue = "") val details: String = "",
    @ColumnInfo(defaultValue = "0") val dbSizeBytes: Long = 0,
    @ColumnInfo(defaultValue = "0") val checkedAt: Long = System.currentTimeMillis(),
)
