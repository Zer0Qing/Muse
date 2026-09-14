package io.zer0.muse.data.stats

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import io.zer0.common.AppDispatchers
import io.zer0.common.Logger
import io.zer0.muse.data.session.MessageDao
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * v1.107 自动备份助手。
 *
 * F-26(功能完善度审查): 将三个核心数据库打包为 ZIP 备份到内部存储 `backups/` 子目录,
 * 覆盖会话/消息(muse.db)、记忆摘要/编译产物(memory.db)、元事实(facts.db),
 * 并记录每次备份结果到 [auto_backup_log][AutoBackupLogEntity] 表,用于备份历史查看与恢复。
 *
 * 备份策略(由 WorkManager 周期任务驱动):
 *  - 频率: 每日 1 次(只在实际有新消息时才真正复制);
 *  - 保留: 最近 [DEFAULT_KEEP_COUNT] 份,超出自动清理最旧的;
 *  - 路径: `getDatabasePath("muse.db").parentFile/backups/`,包名 `muse_backup_yyyyMMdd_HHmmss.zip`。
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

        /** 备份文件前缀。 */
        private const val BACKUP_FILE_PREFIX = "muse_backup_"

        /** 备份文件后缀。 */
        private const val BACKUP_FILE_SUFFIX = ".zip"

        /** 备份子目录名。 */
        private const val BACKUP_DIR_NAME = "backups"

        /** 备份包内各数据库条目名。 */
        const val ENTITY_MUSE_DB = "muse.db"
        const val ENTITY_MEMORY_DB = "memory.db"
        const val ENTITY_FACTS_DB = "facts.db"

        /** 默认保留的备份数。 */
        private const val DEFAULT_KEEP_COUNT = 7
    }

    /**
     * 执行一次备份。
     *
     * 步骤:
     *  1. 对三个数据库(muse.db/memory.db/facts.db)分别执行 `VACUUM INTO`
     *     生成一致性快照(含 WAL 中未 checkpoint 部分);
     *  2. 将快照打包为 `muse_backup_yyyyMMdd_HHmmss.zip` 存入 backups/;
     *  3. 记录备份结果(路径、大小、消息总数)到 auto_backup_log。
     *
     * 在 [AppDispatchers.io] 上执行。任一步骤异常时记录失败日志并返回 false,不向上抛出。
     *
     * @return true 表示备份成功
     */
    suspend fun backupNow(): Boolean = withContext(AppDispatchers.io) {
        Logger.i(TAG, "backupNow: 开始备份")
        val now = System.currentTimeMillis()

        val backupDir = ensureBackupDir()
        val dbNames = listOf(ENTITY_MUSE_DB, ENTITY_MEMORY_DB, ENTITY_FACTS_DB)
        val missingDbs = dbNames.filter { !context.getDatabasePath(it).exists() }
        if (backupDir == null || missingDbs.size == dbNames.size) {
            val reason = if (backupDir == null) "backup dir missing" else "all dbs missing"
            Logger.w(TAG, "backupNow: $reason")
            logResult(success = false, path = "", size = 0L, now = now, error = reason)
            return@withContext false
        }

        // 1. 各库生成一致性快照到临时目录
        val staging = File(context.cacheDir, "auto_backup_staging_$now")
        staging.mkdirs()
        val snapshotFiles = mutableListOf<Pair<String, File>>()
        for (name in dbNames) {
            val dbFile = context.getDatabasePath(name)
            if (!dbFile.exists()) continue
            val snap = File(staging, name)
            // 旧版只备份 muse.db;新逻辑对缺失库跳过,不视为失败
            if (vacuumInto(dbFile, snap)) {
                snapshotFiles.add(name to snap)
            } else {
                Logger.w(TAG, "backupNow: ${name} 快照失败,跳过该库计入部分成功")
            }
        }

        // 2. 打包
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(now))
        val target = File(backupDir, "$BACKUP_FILE_PREFIX$timestamp$BACKUP_FILE_SUFFIX")
        val zipped = zipSnapshots(snapshotFiles, target)

        // 3. 清理临时快照
        staging.deleteRecursively()

        if (zipped) {
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
            Logger.i(
                TAG,
                "backupNow: 备份成功 -> ${target.absolutePath} (${target.length()} bytes, dbs=${snapshotFiles.size})",
            )
        } else {
            logResult(
                success = false,
                path = target.absolutePath,
                size = 0L,
                now = now,
                error = "zip failed",
            )
        }
        zipped
    }

    /**
     * 把多个数据库快照打包为 zip。
     */
    private fun zipSnapshots(snapshotFiles: List<Pair<String, File>>, target: File): Boolean {
        if (snapshotFiles.isEmpty()) return false
        return runCatching {
            FileOutputStream(target).use { fos ->
                ZipOutputStream(fos).use { zos ->
                    for ((name, file) in snapshotFiles) {
                        zos.putNextEntry(ZipEntry(name))
                        file.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
            }
            true
        }.getOrElse {
            Logger.e(TAG, "zipSnapshots: 打包失败: ${it.message}", it)
            target.delete()
            false
        }
    }

    /**
     * 清理旧备份文件与日志,仅保留最近 [keepCount] 份。
     *
     * 文件按文件名时间戳降序保留(兼容旧版 .db 单文件备份);日志表同步调用 [AutoBackupLogDao.trim]。
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

        // L-14: 优先按文件名解析的时间戳排序(文件名固定为 muse_backup_yyyyMMdd_HHmmss.{zip,db}),
        // lastModified 仅作为兜底,防止系统时钟异常时删除顺序错误
        val files = backupDir.listFiles { f ->
            f.isFile && f.name.startsWith(BACKUP_FILE_PREFIX)
        }?.sortedByDescending { f ->
            // 尝试从文件名解析时间戳作为排序键
            val ts = f.name.substringAfterLast('_').substringBefore('.').toLongOrNull()
            ts ?: f.lastModified()
        } ?: emptyList()

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
        val dbFile = context.getDatabasePath("muse.db")
        val parent = dbFile.parentFile ?: return null
        val backupDir = File(parent, BACKUP_DIR_NAME)
        if (!backupDir.exists() && !backupDir.mkdirs()) {
            Logger.w(TAG, "ensureBackupDir: 创建备份目录失败 ${backupDir.absolutePath}")
            return null
        }
        return backupDir
    }

    /**
     * 审计修复 (0.4): 用 VACUUM INTO 生成一致性备份快照。
     *
     * VACUUM INTO 会生成一个包含当前库全部已提交数据(含 WAL 中未 checkpoint 部分)的
     * 全新数据库文件,且不阻塞写入连接;替代"PASSIVE checkpoint + 复制主库"的旧方案
     * (后者在活跃写事务时丢帧、且从不复制 -wal/-shm,备份是旧状态)。
     *
     * @return true 表示备份成功
     */
    private fun vacuumInto(dbFile: File, target: File): Boolean {
        var conn: SQLiteDatabase? = null
        try {
            conn = SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            )
            // VACUUM INTO 路径中的单引号需转义
            val escaped = target.absolutePath.replace("'", "''")
            conn.execSQL("VACUUM INTO '$escaped'")
            Logger.d(TAG, "vacuumInto: 一致性快照已生成 ${target.absolutePath} (${target.length()} bytes)")
            return true
        } catch (e: Exception) {
            Logger.e(TAG, "vacuumInto: 生成快照失败: ${e.message}", e)
            // VACUUM INTO 失败(旧设备/权限)时回退:先强制 checkpoint 再复制主库
            return runCatching {
                conn?.rawQuery("PRAGMA wal_checkpoint(FULL)", null)?.use { it.moveToFirst() }
                conn?.close()
                conn = null
                dbFile.copyTo(target, overwrite = true)
                true
            }.getOrElse {
                Logger.e(TAG, "vacuumInto: 回退复制也失败: ${it.message}", it)
                false
            }
        } finally {
            try {
                conn?.close()
            } catch (e: Exception) {
                Logger.w(TAG, "vacuumInto: 关闭连接失败: ${e.message}", e)
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
