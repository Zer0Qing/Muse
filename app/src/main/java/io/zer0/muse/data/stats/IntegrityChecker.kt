package io.zer0.muse.data.stats

import androidx.sqlite.db.SupportSQLiteDatabase
import io.zer0.common.AppDispatchers
import io.zer0.common.Logger
import kotlinx.coroutines.withContext
import java.io.File

/**
 * v1.107 数据库完整性校验器。
 *
 * 启动时(或手动触发)执行 SQLite 的 `PRAGMA integrity_check`,将结果与数据库文件大小
 * 记录到 [db_integrity_log][DbIntegrityLogEntity] 表,用于追踪数据库健康度与体积增长趋势。
 * 若校验失败,UI 层可提示用户从自动备份恢复。
 *
 * @param integrityLogDao 完整性日志 DAO
 * @param db Room 暴露的 [SupportSQLiteDatabase],由 `MuseDb.openHelper.writableDatabase` 获取,
 *           用于执行 PRAGMA 查询与读取数据库文件路径
 */
class IntegrityChecker(
    private val integrityLogDao: DbIntegrityLogDao,
    private val db: SupportSQLiteDatabase,
) {

    companion object {
        private const val TAG = "IntegrityChecker"

        /** 历史日志保留条数。 */
        private const val KEEP_LOG_COUNT = 30
    }

    /**
     * 执行一次 `PRAGMA integrity_check` 并落库记录。
     *
     * 步骤:
     *  1. 读取数据库主文件大小(用于追踪增长趋势);
     *  2. 通过 [SupportSQLiteDatabase.query] 执行 `PRAGMA integrity_check`,读取全部结果行;
     *  3. 将状态("ok"/"error")、详情、文件大小、时间戳写入 db_integrity_log;
     *  4. 顺带清理旧日志,仅保留最近 [KEEP_LOG_COUNT] 条。
     *
     * 在 [AppDispatchers.io] 上执行。任一步骤异常都被捕获并记录,不向上抛出。
     *
     * @return true 表示校验通过(结果为 "ok");false 表示存在异常或查询失败
     */
    suspend fun checkAndLog(): Boolean = withContext(AppDispatchers.io) {
        val dbSize = readDbSizeBytes()

        val details = try {
            db.query("PRAGMA integrity_check").use { cursor ->
                val sb = StringBuilder()
                while (cursor.moveToNext()) {
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append(cursor.getString(0) ?: "")
                }
                if (sb.isEmpty()) "empty" else sb.toString()
            }
        } catch (e: Exception) {
            Logger.e(TAG, "checkAndLog: 执行 integrity_check 失败", e)
            "query_failed: ${e.message}"
        }

        val isOk = details.equals("ok", ignoreCase = true)
        val status = if (isOk) "ok" else "error"

        try {
            integrityLogDao.insert(
                DbIntegrityLogEntity(
                    status = status,
                    details = details,
                    dbSizeBytes = dbSize,
                    checkedAt = System.currentTimeMillis(),
                )
            )
        } catch (e: Exception) {
            Logger.e(TAG, "checkAndLog: 写入完整性日志失败", e)
        }

        try {
            integrityLogDao.trim(KEEP_LOG_COUNT)
        } catch (e: Exception) {
            Logger.w(TAG, "checkAndLog: 清理旧完整性日志失败: ${e.message}", e)
        }

        if (isOk) {
            Logger.i(TAG, "checkAndLog: integrity_check ok, dbSize=$dbSize")
        } else {
            Logger.w(TAG, "checkAndLog: integrity_check 异常: $details")
        }
        isOk
    }

    /**
     * 获取最近一次完整性校验记录。
     *
     * 在 [AppDispatchers.io] 上执行。
     *
     * @return 最近一条记录;表为空时返回 null
     */
    suspend fun getLatestStatus(): DbIntegrityLogEntity? = withContext(AppDispatchers.io) {
        integrityLogDao.getLatest()
    }

    /**
     * 读取数据库主文件大小,失败或路径为空时返回 0。
     */
    private fun readDbSizeBytes(): Long {
        val path: String? = try {
            db.path
        } catch (e: Exception) {
            Logger.w(TAG, "readDbSizeBytes: 获取数据库路径失败: ${e.message}", e)
            null
        }
        if (path.isNullOrEmpty()) return 0L
        return try {
            File(path).length()
        } catch (e: Exception) {
            Logger.w(TAG, "readDbSizeBytes: 读取文件大小失败: ${e.message}", e)
            0L
        }
    }
}
