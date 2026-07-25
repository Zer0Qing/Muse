package io.zer0.muse.data.session

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Phase 9.1 (M13): 文件夹数据访问对象。
 */
@Dao
interface FolderDao {

    /** 观察全部文件夹(按 sortIndex 升序)。 */
    @Query("SELECT * FROM folders ORDER BY sortIndex ASC, createdAt ASC")
    fun observeAll(): Flow<List<FolderEntity>>

    /** 按 id 取单个文件夹(一次性)。 */
    @Query("SELECT * FROM folders WHERE id = :id")
    suspend fun getById(id: String): FolderEntity?

    /** 插入新文件夹(冲突时忽略)。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(folder: FolderEntity)

    /** 更新文件夹(名称/排序/展开状态)。 */
    @Update
    suspend fun update(folder: FolderEntity)

    /**
     * M-SESS8: 原子重命名文件夹(避免读-改-写并发丢失更新)。
     * 同步维护 updatedAt。
     */
    @Query("UPDATE folders SET name = :name, updatedAt = :now WHERE id = :id")
    suspend fun updateName(id: String, name: String, now: Long)

    /** 按 id 删除文件夹(会话的 folderId 由 SessionDao.setFolderId 清空)。 */
    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun deleteById(id: String)

    /** 文件夹总数。 */
    @Query("SELECT COUNT(*) FROM folders")
    suspend fun count(): Int

    /** 切换文件夹展开状态(侧栏折叠/展开)。 */
    @Query("UPDATE folders SET expanded = :expanded WHERE id = :id")
    suspend fun setExpanded(id: String, expanded: Boolean)

    // ── v1.107 冗余字段维护方法 ──

    /** v1.107: 原子增减文件夹内会话计数。 */
    @Query("UPDATE folders SET sessionCount = sessionCount + :delta WHERE id = :id")
    suspend fun incrementSessionCount(id: String, delta: Int)

    /** v1.107: 直接设置文件夹会话计数(回填/校准用)。 */
    @Query("UPDATE folders SET sessionCount = :count WHERE id = :id")
    suspend fun setSessionCount(id: String, count: Int)

    @Query("SELECT * FROM folders")
    suspend fun getAll(): List<FolderEntity>

    @Query("DELETE FROM folders")
    suspend fun deleteAll()
}
