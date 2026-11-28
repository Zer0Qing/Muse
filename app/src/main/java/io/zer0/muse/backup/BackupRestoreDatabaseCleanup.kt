package io.zer0.muse.backup

import io.zer0.common.Logger
import io.zer0.muse.data.session.MessageImageStore
import io.zer0.muse.data.session.MuseDb
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * 清理恢复时会被完整替换的会话状态。
 *
 * 调用方必须在 MuseDb 事务中执行；checkpoint 显式清理不依赖外键级联配置。
 */
internal suspend fun clearConversationStateForRestore(db: MuseDb) {
    db.generationCheckpointDao().deleteAll()
    db.messageDao().deleteAll()
    db.sessionDao().deleteAll()
}

internal suspend fun <T> restoreAndPruneMessageImages(
    imageStore: MessageImageStore,
    restore: suspend () -> T,
    readReferencedPaths: suspend () -> Set<String>?,
): T {
    val snapshot = imageStore.snapshotStoredFilePaths()
    val result = restore()
    pruneMessageImageSnapshot(imageStore, snapshot, readReferencedPaths)
    return result
}

internal suspend fun pruneMessageImageSnapshot(
    imageStore: MessageImageStore,
    snapshot: Set<String>,
    readReferencedPaths: suspend () -> Set<String>?,
) {
    if (snapshot.isEmpty()) return
    try {
        withContext(NonCancellable) {
            val referenced = readReferencedPaths() ?: return@withContext
            imageStore.deleteSnapshotFilesNotReferenced(snapshot, referenced)
        }
    } catch (error: SecurityException) {
        Logger.w("BackupRestore", "恢复后图片清理未完成: ${error.message}", error)
    }
}
