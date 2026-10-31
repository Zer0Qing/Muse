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
    @Query("SELECT * FROM sessions ORDER BY pinned DESC, sortOrder ASC, updatedAt DESC")
    fun observeAll(): Flow<List<SessionEntity>>

    /** v0.45: 观察未归档会话(置顶优先,再按 updatedAt 降序)。主列表用。 */
    @Query("SELECT * FROM sessions WHERE archived = 0 AND deletedAt IS NULL ORDER BY pinned DESC, sortOrder ASC, updatedAt DESC")
    fun observeActive(): Flow<List<SessionEntity>>

    /**
     * v1.28: 观察未归档的任务会话(排除 Agent Tab 会话)。任务列表用。
     */
    @Query(
        "SELECT * FROM sessions WHERE archived = 0 AND isAgentSession = 0 " +
            "AND deletedAt IS NULL ORDER BY pinned DESC, sortOrder ASC, updatedAt DESC",
    )
    fun observeTaskSessions(): Flow<List<SessionEntity>>

    /** v1.28: 观察 Agent Tab 会话(Agent 日常聊天)。 */
    @Query("SELECT * FROM sessions WHERE isAgentSession = 1 AND deletedAt IS NULL ORDER BY updatedAt DESC")
    fun observeAgentSessions(): Flow<List<SessionEntity>>

    /** v1.28: 获取最近的 Agent 会话(一次性,用于自动恢复 Agent 对话)。 */
    @Query("SELECT * FROM sessions WHERE isAgentSession = 1 AND deletedAt IS NULL ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getLatestAgentSession(): SessionEntity?

    /** v1.0.54: 按助手查最近 Agent 会话(切换助手时恢复该助手的对话房间)。 */
    @Query(
        "SELECT * FROM sessions WHERE assistantId = :assistantId AND deletedAt IS NULL " +
            "AND isAgentSession = 1 ORDER BY updatedAt DESC LIMIT :limit",
    )
    suspend fun getRecentAgentByAssistant(assistantId: String, limit: Int): List<SessionEntity>

    /** v0.45: 观察已归档会话。v1.67: 排序与主列表一致(pinned DESC, updatedAt DESC)。 */
    @Query("SELECT * FROM sessions WHERE archived = 1 AND deletedAt IS NULL ORDER BY pinned DESC, sortOrder ASC, updatedAt DESC")
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

    /** v2.0: 即将被永久删除的软删会话 id — 记忆清理需要先知道哪些会话没了。 */
    @Query("SELECT id FROM sessions WHERE deletedAt IS NOT NULL AND deletedAt < :cutoff")
    suspend fun listIdsDeletedBefore(cutoff: Long): List<String>

    /** 清空全部会话前先取一遍 id,供记忆清理使用。 */
    @Query("SELECT id FROM sessions")
    suspend fun listAllIds(): List<String>

    /** 切换会话置顶状态(Phase 8.2 L13 配套)。 */
    @Query("UPDATE sessions SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean)

    /** 切换会话绑定的 Assistant(Phase 8.2 H6)。 */
    @Query("UPDATE sessions SET assistantId = :assistantId WHERE id = :id")
    suspend fun setAssistantId(id: String, assistantId: String)

    /** v1.0.72: 设置会话"不参考记忆"标志。 */
    @Query("UPDATE sessions SET ignoreMemory = :ignore WHERE id = :id")
    suspend fun setIgnoreMemory(id: String, ignore: Boolean)

    /**
     * 保存未发送的输入草稿。
     *
     * 每个会话各自保存，切换会话时不丢草稿。空串表示清空（已发送或用户删空）。
     * 不更新 updatedAt —— 草稿只是输入缓冲，不该把会话顶到列表最前面。
     */
    @Query("UPDATE sessions SET input_draft = :draft WHERE id = :id")
    suspend fun updateInputDraft(id: String, draft: String)

    /** v2.x 导入预热:设置/清除会话首轮全量历史标记。 */
    @Query("UPDATE sessions SET warmupPending = :pending WHERE id = :id")
    suspend fun setWarmupPending(id: String, pending: Boolean)

    /** v2.5.3 (P3-2): 写入会话级配置快照 JSON(null = 清除快照)。 */
    @Query("UPDATE sessions SET configSnapshotJson = :snapshotJson WHERE id = :id")
    suspend fun setConfigSnapshot(id: String, snapshotJson: String?)

    /** Phase 9.1 (M13): 切换会话所属文件夹(null = 移出文件夹到未分组)。 */
    @Query("UPDATE sessions SET folderId = :folderId WHERE id = :id")
    suspend fun setFolderId(id: String, folderId: String?)

    /** 删除文件夹时批量清空其下会话的 folderId(移到未分组)。Android 16 禁止 execSQL DML。 */
    @Query("UPDATE sessions SET folderId = NULL WHERE folderId = :folderId")
    suspend fun clearFolderId(folderId: String)

    /** 按 id 取单个会话(一次性)。 */
    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun getById(id: String): SessionEntity?

    /**
     * v2.6.7 fix (AUDIT F16): 某助手名下全部会话 id。
     *
     * 只投影 id 列；供跨会话检索一次性过滤，避免逐条查会话。
     */
    @Query("SELECT id FROM sessions WHERE assistantId = :assistantId AND deletedAt IS NULL")
    suspend fun listIdsByAssistant(assistantId: String): List<String>

    /** 按 id 批量取会话,用于记忆中心关联摘要标题。 */
    @Query("SELECT * FROM sessions WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<SessionEntity>

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
    @Query(
        """
        UPDATE sessions SET
            lastMessagePreview = :preview,
            updatedAt = :now,
            title = CASE
                WHEN title = :defaultTitle AND :isUser = 1 THEN :autoTitle
                ELSE title
            END
        WHERE id = :id
    """,
    )
    suspend fun updatePreviewAndTitle(id: String, preview: String, now: Long, defaultTitle: String, isUser: Int, autoTitle: String)

    /**
     * 仅更新会话预览 + updatedAt,不改标题。
     * 退出对话时由 AI 摘要统一命名,首条消息不再自动覆盖标题。
     */
    @Query(
        """
        UPDATE sessions SET
            lastMessagePreview = :preview,
            updatedAt = :now
        WHERE id = :id
    """,
    )
    suspend fun updatePreviewOnly(id: String, preview: String, now: Long)

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

    /**
     * v1.0.52: 查询某助手最近 N 条会话(供 Recent Chats Reference 注入 system prompt)。
     *
     * 过滤规则与 [observeTaskSessions] 对齐:
     *  - 排除已归档(archived=1)
     *  - 排除已软删除(deletedAt IS NOT NULL)
     *  - 排除 Agent Tab 会话(isAgentSession=1,Agent 日常聊天不参与参考)
     * 按 updatedAt DESC 排序,LIMIT :limit。
     */
    @Query(
        "SELECT * FROM sessions WHERE assistantId = :assistantId " +
            "AND archived = 0 AND deletedAt IS NULL AND isAgentSession = 0 " +
            "ORDER BY updatedAt DESC LIMIT :limit",
    )
    suspend fun getRecentByAssistant(assistantId: String, limit: Int): List<SessionEntity>

    @Query("UPDATE sessions SET lastReadMessageId = :messageId, lastReadCount = :readCount WHERE id = :sessionId")
    suspend fun updateLastReadMessage(sessionId: String, messageId: String, readCount: Int)

    @Query("UPDATE sessions SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun updateSortOrder(id: String, sortOrder: Int)

    /** B8-01: 更新会话级主动消息排期(null=清除排期)。 */
    @Query("UPDATE sessions SET proactiveNextTriggerAt = :nextTriggerAt WHERE id = :id")
    suspend fun updateProactiveNextTriggerAt(id: String, nextTriggerAt: Long?)

    /**
     * v2.6.13: 按 folderId 哨兵值取会话（卡片收藏专用会话复用用）。
     *
     * 不用标题匹配：标题是本地化文案，切语言后会变成另一个值，导致每次切语言都新建一个会话。
     *
     * 放在接口末尾：本模块 ktlint 基线按行号匹配，中间插入会让后面所有条目漂移。
     */
    @Query("SELECT * FROM sessions WHERE folderId = :folderId AND deletedAt IS NULL ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getByFolderId(folderId: String): SessionEntity?

    /**
     * 主动消息落点:该助手最近有用户发言、且写入合法的会话(按最后活动时间倒序取第一条)。
     *
     * 排除条件写进 SQL,不靠取回来再在内存里过滤:这里只取一条,内存过滤等于
     * 「先挑错再补救」,被排除的那条落空后整轮就退化成新建会话,白丢一次判断。
     *  - deletedAt 非空:已软删除
     *  - archived = 1:已归档
     *  - 没有非删除的 USER 消息:用户没在这里说过话,不算「聊完的地方」
     *
     * 外层走 idx_sessions_assistantId_updatedAt(assistantId + updatedAt),
     * EXISTS 子查询走 idx_messages_sessionId_createdAt_role;命中第一条即停,
     * 不改动主对话列表使用的任何查询或索引。
     *
     * 放在接口末尾:本模块 ktlint 基线按行号匹配,中间插入会让后面所有条目漂移。
     */
    @Query(
        "SELECT * FROM sessions s WHERE s.assistantId = :assistantId " +
            "AND s.deletedAt IS NULL AND s.archived = 0 " +
            "AND EXISTS (SELECT 1 FROM messages m WHERE m.sessionId = s.id " +
            "AND m.role = 'USER' AND m.deletedAt IS NULL) " +
            "ORDER BY s.updatedAt DESC LIMIT 1",
    )
    suspend fun getRecentActiveByAssistant(assistantId: String): SessionEntity?
}
