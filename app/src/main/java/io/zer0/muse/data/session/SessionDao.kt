package io.zer0.muse.data.session

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * 会话数据访问对象。
 */
@Dao
interface SessionDao {

    /** 观察全部会话(置顶优先,再按 updatedAt 降序)。含已归档和已软删除会话,仅供备份导出使用。主列表用 [observeActive]。 */
    @Query("SELECT * FROM sessions ORDER BY pinned DESC, updatedAt DESC")
    fun observeAll(): Flow<List<SessionEntity>>

    /** v0.45: 观察未归档会话(置顶优先,再按 updatedAt 降序)。主列表用。 */
    @Query("SELECT * FROM sessions WHERE archived = 0 AND deletedAt IS NULL ORDER BY pinned DESC, updatedAt DESC")
    fun observeActive(): Flow<List<SessionEntity>>

    /** v1.28: 观察未归档的任务会话(排除 Agent Tab 会话)。任务列表用。 */
    @Query("SELECT * FROM sessions WHERE archived = 0 AND isAgentSession = 0 AND deletedAt IS NULL ORDER BY pinned DESC, updatedAt DESC")
    fun observeTaskSessions(): Flow<List<SessionEntity>>

    /** v1.28: 观察 Agent Tab 会话(Agent 日常聊天)。 */
    @Query("SELECT * FROM sessions WHERE isAgentSession = 1 AND deletedAt IS NULL ORDER BY updatedAt DESC")
    fun observeAgentSessions(): Flow<List<SessionEntity>>

    /** v1.28: 获取最近的 Agent 会话(一次性,用于自动恢复 Agent 对话)。 */
    @Query("SELECT * FROM sessions WHERE isAgentSession = 1 AND deletedAt IS NULL ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getLatestAgentSession(): SessionEntity?

    /** v0.45: 观察已归档会话。v1.67: 排序与主列表一致(pinned DESC, updatedAt DESC)。 */
    @Query("SELECT * FROM sessions WHERE archived = 1 AND deletedAt IS NULL ORDER BY pinned DESC, updatedAt DESC")
    fun observeArchived(): Flow<List<SessionEntity>>

    /** v0.45: 切换会话归档状态。 */
    @Query("UPDATE sessions SET archived = :archived WHERE id = :id")
    suspend fun setArchived(id: String, archived: Boolean)

    // ── v2.0: 软删除 ──

    /** v2.0: 软删除会话(设置 deletedAt 时间戳)。 */
    @Query("UPDATE sessions SET deletedAt = :now WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)

    /** v2.0: 恢复软删除的会话。 */
    @Query("UPDATE sessions SET deletedAt = NULL WHERE id = :id")
    suspend fun restore(id: String)

    /** v2.0: 观察 7 天内软删除的会话。 */
    @Query("SELECT * FROM sessions WHERE deletedAt IS NOT NULL AND deletedAt >= :cutoff ORDER BY deletedAt DESC")
    fun observeDeleted(cutoff: Long): Flow<List<SessionEntity>>

    /** v2.0: 永久删除软删除超过 7 天的会话。 */
    @Query("DELETE FROM sessions WHERE deletedAt IS NOT NULL AND deletedAt < :cutoff")
    suspend fun permanentlyDeleteOldSessions(cutoff: Long)

    /** 切换会话置顶状态(Phase 8.2 L13 配套)。 */
    @Query("UPDATE sessions SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean)

    /** 切换会话绑定的 Assistant(Phase 8.2 H6)。 */
    @Query("UPDATE sessions SET assistantId = :assistantId WHERE id = :id")
    suspend fun setAssistantId(id: String, assistantId: String)

    /** Phase 9.1 (M13): 切换会话所属文件夹(null = 移出文件夹到未分组)。 */
    @Query("UPDATE sessions SET folderId = :folderId WHERE id = :id")
    suspend fun setFolderId(id: String, folderId: String?)

    /** 删除文件夹时批量清空其下会话的 folderId(移到未分组)。Android 16 禁止 execSQL DML。 */
    @Query("UPDATE sessions SET folderId = NULL WHERE folderId = :folderId")
    suspend fun clearFolderId(folderId: String)

    /** 按 id 取单个会话(一次性)。 */
    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun getById(id: String): SessionEntity?

    /** 插入新会话(冲突时忽略)。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(session: SessionEntity)

    /** 更新会话(标题/updatedAt/lastMessagePreview)。 */
    @Update
    suspend fun update(session: SessionEntity)

    /**
     * H-SESS2: 原子重命名会话(避免读-改-写并发丢失更新)。
     * 同步维护 updatedAt。
     */
    @Query("UPDATE sessions SET title = :title, updatedAt = :now WHERE id = :id")
    suspend fun updateTitle(id: String, title: String, now: Long)

    /**
     * H-SESS2: 原子更新会话预览 + updatedAt(删除最后一条消息后回填新预览用)。
     */
    @Query("UPDATE sessions SET lastMessagePreview = :preview, updatedAt = :now WHERE id = :id")
    suspend fun updatePreview(id: String, preview: String, now: Long)

    /**
     * H-SESS2: 原子更新预览 + updatedAt,并在标题仍为默认值且当前消息来自用户时
     * 自动用消息内容前缀覆盖标题(首条 user 消息自动命名)。
     *
     * 用 CASE 在单条 UPDATE 内完成条件标题更新,彻底避免读-改-写竞态。
     * :isUser 传 1 表示当前消息角色为 USER,否则 0。
     */
    @Query("""
        UPDATE sessions SET
            lastMessagePreview = :preview,
            updatedAt = :now,
            title = CASE
                WHEN title = :defaultTitle AND :isUser = 1 THEN :autoTitle
                ELSE title
            END
        WHERE id = :id
    """)
    suspend fun updatePreviewAndTitle(
        id: String,
        preview: String,
        now: Long,
        defaultTitle: String,
        isUser: Int,
        autoTitle: String,
    )

    /** 按 id 删除会话(级联删消息)。 */
    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteById(id: String)

    /** 清空全部会话(备份恢复时用,Android 16 禁止 execSQL DML)。 */
    @Query("DELETE FROM sessions")
    suspend fun deleteAll()

    /** 会话总数(用于统计)。 */
    @Query("SELECT COUNT(*) FROM sessions")
    suspend fun count(): Int

    /** 会话总数流(统计面板用)。 */
    @Query("SELECT COUNT(*) FROM sessions")
    fun observeCount(): Flow<Int>

    // ── v1.107 冗余字段维护方法 ──

    /** v1.107: 原子增减会话消息计数(delta 可正可负)。 */
    @Query("UPDATE sessions SET messageCount = messageCount + :delta WHERE id = :id")
    suspend fun incrementMessageCount(id: String, delta: Int)

    /** v1.107: 直接设置会话消息计数(回填/校准用)。 */
    @Query("UPDATE sessions SET messageCount = :count WHERE id = :id")
    suspend fun setMessageCount(id: String, count: Int)

    /** v5: 递增分叉子会话计数。 */
    @Query("UPDATE sessions SET childCount = childCount + 1 WHERE id = :id")
    suspend fun incrementChildCount(id: String)

    /** v5: 查询指定会话的分叉子会话(按 updatedAt 降序)。 */
    @Query("SELECT * FROM sessions WHERE parentSessionId = :parentId AND deletedAt IS NULL ORDER BY updatedAt DESC")
    suspend fun getChildSessions(parentId: String): List<SessionEntity>

    /** v5: 查询指定会话的父会话标题。 */
    @Query("SELECT title FROM sessions WHERE id = :parentId")
    suspend fun getParentSessionTitle(parentId: String): String?
}
