package io.zer0.muse.backup

import android.content.Context
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.muse.data.AtomicFileStore
import java.io.File
import java.util.UUID
import kotlinx.serialization.Serializable

/**
 * 备份恢复阶段账本。
 *
 * Muse 的会话库、记忆库、事实库和 DataStore 无法共享一个事务；本账本
 * 记录跨存储恢复当前走到哪一步，使下次启动能够识别未完成恢复，而不是
 * 把半恢复状态误认为正常完成。它不保存备份正文或任何敏感值。
 */
class RestoreJournal(context: Context) {

    private val file = File(context.filesDir, FILE_NAME)

    /** 当前恢复状态；文件不存在表示没有进行中的恢复。 */
    fun read(): Entry? = if (!file.exists()) {
        null
    } else {
        runCatching { AppJson.decodeFromString(Entry.serializer(), file.readText()) }
            .onFailure { error ->
                Logger.w(TAG, "恢复账本解析失败: ${error.message}", error)
                AtomicFileStore.quarantine(file, "restore_journal_parse")
            }
            .getOrNull()
    }

    /** 是否存在尚未完成的恢复；供启动自检和诊断页使用。 */
    fun hasIncompleteRestore(): Boolean = read()?.phase?.let { phase ->
        phase != Phase.COMPLETED
    } == true

    /** 返回尚未完成的恢复记录，供启动层展示具体阶段。 */
    fun incompleteEntry(): Entry? = read()?.takeUnless { it.phase == Phase.COMPLETED }

    /** 创建一次新的恢复记录。 */
    @Synchronized
    fun begin(restoreId: String, sourceHash: String, backupVersion: Int): Entry = update(
        Entry(
            restoreId = restoreId,
            sourceHash = sourceHash,
            backupVersion = backupVersion,
            phase = Phase.PREPARING,
            artifactId = UUID.randomUUID().toString(),
        ),
    )

    /** 更新恢复阶段和已完成的存储。 */
    @Synchronized
    fun advance(
        entry: Entry,
        phase: Phase,
        completedStores: Set<Store> = entry.completedStores,
        failureReason: String? = null,
    ): Entry = update(
        entry.copy(
            phase = phase,
            completedStores = completedStores,
            failureReason = failureReason,
            updatedAt = System.currentTimeMillis(),
        ),
    )

    /** 标记恢复成功并删除账本，避免旧状态影响下一次启动。 */
    @Synchronized
    fun complete(entry: Entry) {
        val completed = entry.copy(
            phase = Phase.COMPLETED,
            updatedAt = System.currentTimeMillis(),
        )
        update(completed)
        if (!file.delete()) {
            Logger.w(TAG, "恢复完成但账本删除失败: ${file.absolutePath}")
        }
    }

    /** 标记恢复失败并保留账本供诊断；调用方负责决定是否回滚。 */
    @Synchronized
    fun fail(entry: Entry, error: Throwable): Entry = advance(
        entry = entry,
        phase = Phase.FAILED,
        failureReason = error.message?.take(MAX_REASON_LENGTH) ?: error::class.java.simpleName,
    )

    private fun update(entry: Entry): Entry {
        file.parentFile?.mkdirs()
        AtomicFileStore.writeText(file, AppJson.encodeToString(Entry.serializer(), entry))
        return entry
    }

    @Serializable
    data class Entry(
        val restoreId: String,
        /** 只保存源数据摘要，不保存备份正文。 */
        val sourceHash: String,
        val backupVersion: Int,
        /** 恢复副本的私有文件名前缀，不含用户数据。旧账本缺失时由 restoreId 兼容。 */
        val artifactId: String = restoreId,
        val phase: Phase,
        val completedStores: Set<Store> = emptySet(),
        val failureReason: String? = null,
        val createdAt: Long = System.currentTimeMillis(),
        val updatedAt: Long = System.currentTimeMillis(),
    ) {
        /** staging/recovery 文件名只由内部随机标识构成，不能穿越目录。 */
        val stagingFileName: String get() = "target-$artifactId.json"
        val recoveryFileName: String get() = "recovery-$artifactId.json"
    }

    @Serializable
    enum class Phase {
        PREPARING,
        STAGING,
        VALIDATING,
        COMMITTING,
        REBUILDING,
        COMPLETED,
        ROLLING_BACK,
        FAILED,
    }

    @Serializable
    enum class Store {
        MUSE_DB,
        MEMORY_DB,
        FACT_DB,
        SETTINGS,
        FTS,
    }

    private companion object {
        const val TAG = "RestoreJournal"
        const val FILE_NAME = "restore-journal.json"
        const val MAX_REASON_LENGTH = 512
    }
}
