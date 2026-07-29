package io.zer0.muse.data.session

import android.content.Context
import androidx.room.withTransaction
import io.zer0.common.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid
import io.zer0.muse.R

/**
 * Phase 9.1 (M13): 文件夹仓库。
 *
 * 提供文件夹 CRUD + 展开状态切换 + 会话移动到文件夹的领域 API。
 * 侧栏 Drawer 通过 observeAll() 获取文件夹列表,配合 SessionRepository.observeSessions()
 * 按 folderId 分组渲染。
 *
 * M-SESS8: 跨表操作(sessions + folders)用 [database.withTransaction] 包裹;
 * [database] 由 Koin 注入(见 AppKoinModule)。
 */
class FolderRepository(
    private val folderDao: FolderDao,
    private val sessionDao: SessionDao,
    private val database: MuseDb,
    private val context: Context,
) {

    private val TAG = "FolderRepo"

    /** 观察全部文件夹(按 sortIndex 升序)。 */
    fun observeAll(): Flow<List<FolderEntity>> = folderDao.observeAll()

    /** 按 id 取文件夹(一次性)。 */
    suspend fun getById(id: String): FolderEntity? = folderDao.getById(id)

    /** 新建文件夹,返回 id。 */
    suspend fun createFolder(name: String, sortIndex: Int = 0): String {
        val id = Uuid.random().toString()
        val now = System.currentTimeMillis()
        folderDao.insert(
            FolderEntity(
                id = id,
                name = name.ifBlank { context.getString(R.string.folder_repo_default_name) },
                sortIndex = sortIndex,
                createdAt = now,
                updatedAt = now,
                expanded = true,
            )
        )
        return id
    }

    /** 重命名文件夹。 */
    suspend fun renameFolder(id: String, name: String) {
        // M-SESS8: 原子 UPDATE,避免读-改-写并发丢失更新;同步维护 updatedAt
        folderDao.updateName(id, name, System.currentTimeMillis())
    }

    /** 切换文件夹展开状态(侧栏折叠/展开)。 */
    suspend fun setExpanded(id: String, expanded: Boolean) {
        folderDao.setExpanded(id, expanded)
    }

    /** 删除文件夹(关联会话的 folderId 清空,移到"未分组")。 */
    suspend fun deleteFolder(id: String) {
        // M-SESS8: 先清空该文件夹下所有会话的 folderId,再删除文件夹,避免悬空引用;
        // 两步跨表(sessions + folders),用事务包裹保证原子性(崩溃不残留悬空 folderId)
        withContext(Dispatchers.IO) {
            database.withTransaction {
                sessionDao.clearFolderId(id)
                folderDao.deleteById(id)
            }
        }
    }

    /** 移动会话到文件夹(folderId=null = 移到未分组)。 */
    suspend fun moveSessionToFolder(sessionId: String, folderId: String?) {
        withContext(Dispatchers.IO) {
            database.withTransaction {
                // v1.107 冗余: 先查旧 folderId,用于调整 folders.sessionCount
                val session = sessionDao.getById(sessionId)
                val oldFolderId = session?.folderId
                sessionDao.setFolderId(sessionId, folderId)
                // v1.107 冗余: 旧文件夹 sessionCount - 1
                if (oldFolderId != null) {
                    runCatching { folderDao.incrementSessionCount(oldFolderId, -1) }
                        .onFailure { Logger.w(TAG, "decrement old folder sessionCount failed: ${it.message}") }
                }
                // v1.107 冗余: 新文件夹 sessionCount + 1
                if (folderId != null) {
                    runCatching { folderDao.incrementSessionCount(folderId, 1) }
                        .onFailure { Logger.w(TAG, "increment new folder sessionCount failed: ${it.message}") }
                }
            }
        }
    }
}
