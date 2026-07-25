package io.zer0.muse.data.stats

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import io.zer0.common.AppDispatchers
import io.zer0.common.Logger
import io.zer0.muse.data.session.MessageDao
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v1.107 自动备份助手。
 *
 * 将主数据库 `muse.db` 复制到内部存储 `backups/` 子目录,并记录每次备份结果到
 * [auto_backup_log][AutoBackupLogEntity] 表,用于备份历史查看与恢复。
 *
 * 备份策略(由 WorkManager 周期任务驱动):
 *  - 频率: 每日 1 次(只在实际有新消息时才真正复制);
 *  - 保留: 最近 [DEFAULT_KEEP_COUNT] 份,超出自动清理最旧的;
 *  - 路径: `getDatabasePath("muse.db").parentFile/backups/`。
 *
 * @param autoBackupLogDao 备份日志 DAO
 * @param context 用于获取数据库路径
 * @param messageDao 用于备份时记录消息总数
 */
class AutoBackupHelper(
    private val autoBackupLogDao: AutoBackupLogDao,
    private val context: Context,
    private val messageDao: MessageDao,
) {

    companion object {
        private const val TAG = "AutoBackupHelper"

        /** 主数据库文件名。 */
        private const val DB_NAME = "muse.db"

        /** 备份子目录名。 */
        private const val BACKUP_DIR_NAME = "backups"

        /** 默认保留的备份数。 */
        private const val DEFAULT_KEEP_COUNT = 7

        /** 备份文件名前缀。 */
        private const val BACKUP_FILE_PREFIX = "muse_backup_"

        /** 备份文件名后缀。 */
        private const val BACKUP_FILE_SUFFIX = ".db"
    }

    /**
     * 执行一次备份。
     *
     * 步骤:
     *  1. 打开一个临时连接执行 `PRAGMA wal_checkpoint(PASSIVE)`,尽量把 WAL 日志合并进主库文件;
     *  2. 将 `muse.db` 复制为 `muse_backup_yyyyMMdd_HHmmss.db`;
     *  3. 记录备份结果(路径、大小、消息总数)到 auto_backup_log。
     *
     * 在 [AppDispatchers.io] 上执行。任一步骤异常时记录失败日志并返回 false,不向上抛出。
     *
     * @return true 表示备份成功
     */
    suspend fun backupNow(): Boolean = withContext(AppDispatchers.io) {
        Logger.i(TAG, "backupNow: 开始备份")
        val now = System.currentTimeMillis()

        val dbFile = context.getDatabasePath(DB_NAME)
        val backupDir = ensureBackupDir()
        if (backupDir == null || !dbFile.exists()) {
            Logger.w(TAG, "backupNow: 备份目录或数据库文件不存在,dbFile=${dbFile.absolutePath}")
            logResult(success = false, path = "", size = 0L, now = now, error = "db or backup dir missing")
            return@withContext false
        }

        // 先做 WAL checkpoint,确保 WAL 日志尽量合并到主库文件再复制
        runWalCheckpoint(dbFile)

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(now))
        val target = File(backupDir, "$BACKUP_FILE_PREFIX$timestamp$BACKUP_FILE_SUFFIX")

        val success = try {
            dbFile.copyTo(target, overwrite = true)
            true
        } catch (e: Exception) {
            Logger.e(TAG, "backupNow: 复制数据库文件失败: ${target.absolutePath}", e)
            false
        }

        if (success) {
            val messageCount = try {
                messageDao.countMessages().toLong()
            } catch (e: Exception) {
                Logger.w(TAG, "backupNow: 读取消息总数失败: ${e.message}", e)
                0L
            }
            logResult(
                success = true,
                path = target.absolutePath,
                size = target.length(),
                now = now,
                error = "",
                messageCount = messageCount,
            )
            Logger.i(TAG, "backupNow: 备份成功 -> ${target.absolutePath} (${target.length()} bytes)")
        } else {
            logResult(
                success = false,
                path = target.absolutePath,
                size = 0L,
                now = now,
                error = "copy failed",
            )
        }
        success
    }

    /**
     * 清理旧备份文件与日志,仅保留最近 [keepCount] 份。
     *
     * 文件按 lastModified 降序保留;日志表同步调用 [AutoBackupLogDao.trim]。
     * 在 [AppDispatchers.io] 上执行。
     *
     * @param keepCount 保留份数,默认 [DEFAULT_KEEP_COUNT]
     */
    suspend fun trimOldBackups(keepCount: Int = DEFAULT_KEEP_COUNT) = withContext(AppDispatchers.io) {
        val backupDir = ensureBackupDir()
        if (backupDir == null) {
            Logger.w(TAG, "trimOldBackups: 备份目录不可用")
            return@withContext
        }

        val files = backupDir.listFiles { f ->
            f.isFile && f.name.startsWith(BACKUP_FILE_PREFIX) && f.name.endsWith(BACKUP_FILE_SUFFIX)
        }?.sortedByDescending { it.lastModified() } ?: emptyList()

        val toDelete = if (files.size > keepCount) files.drop(keepCount) else emptyList()
        var deleted = 0
        for (f in toDelete) {
            try {
                if (f.delete()) {
                    deleted++
                } else {
                    Logger.w(TAG, "trimOldBackups: 删除失败 ${f.absolutePath}")
                }
            } catch (e: Exception) {
                Logger.w(TAG, "trimOldBackups: 删除异常 ${f.absolutePath}", e)
            }
        }

        try {
            autoBackupLogDao.trim(keepCount)
        } catch (e: Exception) {
            Logger.w(TAG, "trimOldBackups: 清理日志表失败: ${e.message}", e)
        }

        Logger.i(TAG, "trimOldBackups: 保留 $keepCount 份,删除 $deleted 个旧备份文件")
    }

    /**
     * 获取(必要时创建)备份目录;无法获取或创建时返回 null。
     */
    private fun ensureBackupDir(): File? {
        val dbFile = context.getDatabasePath(DB_NAME)
        val parent = dbFile.parentFile ?: return null
        val backupDir = File(parent, BACKUP_DIR_NAME)
        if (!backupDir.exists() && !backupDir.mkdirs()) {
            Logger.w(TAG, "ensureBackupDir: 创建备份目录失败 ${backupDir.absolutePath}")
            return null
        }
        return backupDir
    }

    /**
     * 以独立连接打开数据库并执行 `PRAGMA wal_checkpoint(PASSIVE)`。
     *
     * PASSIVE 模式不会阻塞写连接,只会尽量把已完成的 WAL 帧合并到主库文件。
     * 失败时仅记录警告,不中断备份流程。连接在 [finally] 中关闭。
     *
     * @param dbFile 主数据库文件
     */
    private fun runWalCheckpoint(dbFile: File) {
        var conn: SQLiteDatabase? = null
        try {
            conn = SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            )
            conn.rawQuery("PRAGMA wal_checkpoint(PASSIVE)", null).use { cursor ->
                if (cursor.moveToFirst()) {
                    val busy = if (cursor.columnCount > 0) cursor.getInt(0) else -1
                    val log = if (cursor.columnCount > 1) cursor.getInt(1) else -1
                    val checkpointed = if (cursor.columnCount > 2) cursor.getInt(2) else -1
                    Logger.d(TAG, "runWalCheckpoint: busy=$busy log=$log checkpointed=$checkpointed")
                }
            }
        } catch (e: Exception) {
            Logger.w(TAG, "runWalCheckpoint: 执行 wal_checkpoint 失败: ${e.message}", e)
        } finally {
            try {
                conn?.close()
            } catch (e: Exception) {
                Logger.w(TAG, "runWalCheckpoint: 关闭连接失败: ${e.message}", e)
            }
        }
    }

    /**
     * 写入一条备份结果日志。写日志本身失败时仅记录错误,不影响主流程。
     *
     * @param success 是否成功
     * @param path 备份文件绝对路径
     * @param size 备份文件大小(字节)
     * @param now 时间戳
     * @param error 失败时的错误信息(成功时传空串)
     * @param messageCount 备份时消息总数
     */
    private suspend fun logResult(
        success: Boolean,
        path: String,
        size: Long,
        now: Long,
        error: String,
        messageCount: Long = 0L,
    ) {
        try {
            autoBackupLogDao.insert(
                AutoBackupLogEntity(
                    backupPath = path,
                    fileSizeBytes = size,
                    status = if (success) "success" else "failed",
                    errorMessage = error,
                    messageCount = messageCount,
                    createdAt = now,
                )
            )
        } catch (e: Exception) {
            Logger.e(TAG, "logResult: 写入备份日志失败", e)
        }
    }
}
