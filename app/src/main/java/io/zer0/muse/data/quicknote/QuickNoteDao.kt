package io.zer0.muse.data.quicknote

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * v1.0.17: 快速记录 DAO。
 *
 * 通过 deleted 标记区分正常记录与回收站:
 *  - [observeActive]: 正常列表(deleted=0)
 *  - [observeTrash]: 回收站(deleted=1)
 *
 * 标签检索:tags 列经 [QuickNoteConverters] 序列化为逗号分隔字符串,
 * `tags LIKE '%tag%'` 可命中含该标签的记录(粒度为子串匹配,可接受)。
 *
 * v1.0.18: 增加 folder 相关查询([observeByFolder] / [observeFolders])。
 */
@Dao
interface QuickNoteDao {
    /** 观察正常记录列表(置顶在前,其次按 updatedAt 降序)。 */
    @Query("SELECT * FROM quick_notes WHERE deleted = 0 ORDER BY pinned DESC, updated_at DESC LIMIT :limit")
    fun observeActive(limit: Int = 100): Flow<List<QuickNoteEntity>>

    /**
     * v1.0.18: 按文件夹观察记录(置顶在前,其次按 updatedAt 降序)。
     * 用于文件夹筛选,空串 folder 返回未分类记录。
     */
    @Query("SELECT * FROM quick_notes WHERE deleted = 0 AND folder = :folder ORDER BY pinned DESC, updated_at DESC")
    fun observeByFolder(folder: String): Flow<List<QuickNoteEntity>>

    /**
     * v1.0.18: 观察所有非空文件夹(去重,用于侧边栏/筛选条展示)。
     */
    @Query("SELECT DISTINCT folder FROM quick_notes WHERE deleted = 0 AND folder != ''")
    fun observeFolders(): Flow<List<String>>

    /** 按关键字/标签搜索(同时排除已删除记录)。keyword/tag 为 null 时不参与过滤。 */
    @Query(
        """
        SELECT * FROM quick_notes
        WHERE deleted = 0
          AND (:keyword IS NULL OR title LIKE '%' || :keyword || '%' OR content LIKE '%' || :keyword || '%')
          AND (:tag IS NULL OR tags LIKE '%' || :tag || '%')
        ORDER BY pinned DESC, updated_at DESC
        LIMIT :limit
        """,
    )
    suspend fun search(keyword: String?, tag: String?, limit: Int = 50): List<QuickNoteEntity>

    /** 观察回收站列表(按删除时间降序)。 */
    @Query("SELECT * FROM quick_notes WHERE deleted = 1 ORDER BY deleted_at DESC")
    fun observeTrash(): Flow<List<QuickNoteEntity>>

    /** 根据 id 获取单条记录(含已删除)。 */
    @Query("SELECT * FROM quick_notes WHERE id = :id")
    suspend fun getById(id: String): QuickNoteEntity?

    /** 插入或替换(以 id 为主键)。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: QuickNoteEntity)

    /** 移入回收站(soft delete)。 */
    @Query("UPDATE quick_notes SET deleted = 1, deleted_at = :now, updated_at = :now WHERE id = :id")
    suspend fun moveToTrash(id: String, now: Long = System.currentTimeMillis())

    /** 从回收站恢复。 */
    @Query("UPDATE quick_notes SET deleted = 0, deleted_at = 0, updated_at = :now WHERE id = :id")
    suspend fun restore(id: String, now: Long = System.currentTimeMillis())

    /** 永久删除单条记录。 */
    @Query("DELETE FROM quick_notes WHERE id = :id")
    suspend fun deletePermanent(id: String)

    /** 清理回收站中早于 [before] 的记录(定时清理用)。 */
    @Query("DELETE FROM quick_notes WHERE deleted = 1 AND deleted_at < :before")
    suspend fun cleanOldTrash(before: Long)

    /** 切换置顶状态。 */
    @Query("UPDATE quick_notes SET pinned = :pinned, updated_at = :now WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean, now: Long = System.currentTimeMillis())

    /**
     * v1.0.18: 设置文件夹。
     * folder 传空串表示移出文件夹(未分类)。
     */
    @Query("UPDATE quick_notes SET folder = :folder, updated_at = :now WHERE id = :id")
    suspend fun setFolder(id: String, folder: String, now: Long = System.currentTimeMillis())

    /**
     * v1.0.18: 设置内容类型(plain / markdown)。
     */
    @Query("UPDATE quick_notes SET content_type = :contentType, updated_at = :now WHERE id = :id")
    suspend fun setContentType(id: String, contentType: String, now: Long = System.currentTimeMillis())

    /**
     * v1.0.18: 设置提醒时间(0 表示取消提醒)。
     */
    @Query("UPDATE quick_notes SET reminder_at = :reminderAt, updated_at = :now WHERE id = :id")
    suspend fun setReminderAt(id: String, reminderAt: Long, now: Long = System.currentTimeMillis())

    /**
     * v1.0.18: 设置加密状态与密文。
     * encrypted=false 时 encryptedContent 应传空串。
     */
    @Query(
        "UPDATE quick_notes SET encrypted = :encrypted, encrypted_content = :encryptedContent, " +
            "content = CASE WHEN :encrypted THEN '' ELSE :encryptedContent END, " +
            "updated_at = :now WHERE id = :id",
    )
    suspend fun setEncrypted(id: String, encrypted: Boolean, encryptedContent: String, now: Long = System.currentTimeMillis())
}
