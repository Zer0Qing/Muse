package io.zer0.muse.backup

import android.content.Context
import io.zer0.common.Logger
import io.zer0.muse.data.AtomicFileStore
import java.io.File
import kotlinx.serialization.json.Json

/**
 * 恢复过程的持久化 staging/recovery point。
 *
 * staging 保存准备提交的目标备份，recovery 保存导入前快照。两者都位于
 * filesDir 私有目录，并通过 AtomicFileStore 写入，进程在跨数据库提交中途
 * 被杀后仍可由账本定位。这里不把文件路径或正文写入日志。
 */
class RestoreStagingStore(context: Context) {

    private val directory = File(context.filesDir, DIRECTORY_NAME)
    private val json = Json { ignoreUnknownKeys = true }

    fun writeTarget(entry: RestoreJournal.Entry, backup: BackupService.Backup) {
        write(fileFor(entry.stagingFileName), backup)
    }

    fun writeRecoveryPoint(entry: RestoreJournal.Entry, backup: BackupService.Backup) {
        write(fileFor(entry.recoveryFileName), backup)
    }

    fun readTarget(entry: RestoreJournal.Entry): BackupService.Backup? = read(fileFor(entry.stagingFileName))

    fun readRecoveryPoint(entry: RestoreJournal.Entry): BackupService.Backup? = read(fileFor(entry.recoveryFileName))

    /** 完成或放弃恢复后清除私有恢复副本；删除失败只记日志，不暴露文件内容。 */
    fun cleanup(entry: RestoreJournal.Entry) {
        listOf(entry.stagingFileName, entry.recoveryFileName)
            .filter(String::isNotBlank)
            .forEach { name ->
                val file = fileFor(name)
                if (file.exists() && !file.delete()) {
                    Logger.w(TAG, "清理恢复副本失败: ${file.name}")
                }
            }
        if (directory.exists() && directory.listFiles().isNullOrEmpty()) {
            directory.delete()
        }
    }

    private fun write(file: File, backup: BackupService.Backup) {
        directory.mkdirs()
        val text = json.encodeToString(BackupService.Backup.serializer(), backup)
        AtomicFileStore.writeText(file, text)
    }

    private fun read(file: File): BackupService.Backup? {
        if (!file.exists()) return null
        return runCatching {
            json.decodeFromString(BackupService.Backup.serializer(), file.readText())
        }.onFailure {
            Logger.w(TAG, "恢复副本解析失败: ${file.name}", it)
        }.getOrNull()
    }

    private fun fileFor(name: String): File {
        require(name.isNotBlank() && File(name).name == name && name != "." && name != "..") {
            "invalid restore artifact name"
        }
        return File(directory, name)
    }

    private companion object {
        const val TAG = "RestoreStagingStore"
        const val DIRECTORY_NAME = "restore-staging"
    }
}
