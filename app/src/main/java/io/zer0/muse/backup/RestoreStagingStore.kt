package io.zer0.muse.backup

import android.content.Context
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.muse.data.AtomicFileStore
import io.zer0.muse.data.SecureKeyStore
import io.zer0.muse.data.computenode.RemoteComputeNodeEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 恢复过程的持久化 staging/recovery point。
 *
 * staging 保存准备提交的目标备份，recovery 保存导入前快照。两者都位于
 * filesDir 私有目录，并通过 AtomicFileStore 写入，进程在跨数据库提交中途
 * 被杀后仍可由账本定位。这里不把文件路径或正文写入日志。
 */
class RestoreStagingStore(context: Context) {

    private val directory = File(context.filesDir, DIRECTORY_NAME)
    private val json = AppJson

    fun writeTarget(entry: RestoreJournal.Entry, backup: BackupService.Backup) {
        write(fileFor(entry.stagingFileName), backup)
    }

    fun writeRecoveryPoint(entry: RestoreJournal.Entry, backup: BackupService.Backup) {
        write(fileFor(entry.recoveryFileName), backup)
    }

    fun readTarget(entry: RestoreJournal.Entry): BackupService.Backup? = read(fileFor(entry.stagingFileName))

    fun readRecoveryPoint(entry: RestoreJournal.Entry): BackupService.Backup? = read(fileFor(entry.recoveryFileName))

    @Serializable
    internal data class RecoveryCredentials(
        val rawProviders: String? = null,
        val nodes: List<RemoteComputeNodeEntity> = emptyList(),
    )

    internal suspend fun writeRecoveryCredentials(entry: RestoreJournal.Entry, credentials: RecoveryCredentials) {
        val data = if (credentials == RecoveryCredentials()) {
            EMPTY_CREDENTIALS
        } else {
            SecureKeyStore.encrypt(json.encodeToString(RecoveryCredentials.serializer(), credentials))
        }
        withContext(Dispatchers.IO) {
            directory.mkdirs()
            AtomicFileStore.writeText(credentialsFile(entry), data)
        }
    }

    internal suspend fun readRecoveryCredentials(entry: RestoreJournal.Entry): RecoveryCredentials? = withContext(Dispatchers.IO) {
        val file = credentialsFile(entry)
        if (!file.isFile) return@withContext null
        val data = file.readText()
        if (data == EMPTY_CREDENTIALS) {
            RecoveryCredentials()
        } else {
            val plain = checkNotNull(SecureKeyStore.decryptOrNull(data)) { "Cannot decrypt local recovery credentials" }
            json.decodeFromString(RecoveryCredentials.serializer(), plain)
        }
    }

    private fun credentialsFile(entry: RestoreJournal.Entry): File = fileFor("${entry.recoveryFileName}.credentials")

    /**
     * 将大体量 NDJSON 恢复点流式写入磁盘，不把正文拼成一个 JSON 字符串。
     */
    suspend fun writeRecoveryNdJson(entry: RestoreJournal.Entry, write: suspend (BufferedWriter) -> Unit) = withContext(Dispatchers.IO) {
        val target = fileFor(entry.recoveryFileName)
        directory.mkdirs()
        val temp = File(directory, ".${target.name}.${System.nanoTime()}.tmp")
        try {
            FileOutputStream(temp).use { output ->
                val writer = BufferedWriter(OutputStreamWriter(output, StandardCharsets.UTF_8))
                try {
                    write(writer)
                    writer.flush()
                    output.fd.sync()
                } finally {
                    writer.close()
                }
            }
            moveIntoPlace(temp, target)
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    /** 在文件仍打开时消费 NDJSON 恢复点，避免把整个恢复点读入内存。 */
    suspend fun <T> readRecoveryNdJson(entry: RestoreJournal.Entry, read: suspend (Sequence<String>) -> T): T =
        withContext(Dispatchers.IO) {
            fileFor(entry.recoveryFileName)
                .bufferedReader(StandardCharsets.UTF_8)
                .use { reader -> read(reader.lineSequence()) }
        }

    /** 完成或放弃恢复后清除私有恢复副本；删除失败只记日志，不暴露文件内容。 */
    fun cleanup(entry: RestoreJournal.Entry) {
        listOf(entry.stagingFileName, entry.recoveryFileName, credentialsFile(entry).name)
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

    private fun moveIntoPlace(temp: File, target: File) {
        try {
            Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private companion object {
        const val TAG = "RestoreStagingStore"
        const val DIRECTORY_NAME = "restore-staging"
        const val EMPTY_CREDENTIALS = "empty"
    }
}
