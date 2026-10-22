package io.zer0.muse.data.todo

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * 会话待办（持久化）。
 *
 * ## 为什么要有这张表
 *
 * 助手自审第 2 条：「跨会话的待办没有闭环机制。比如播放器那事儿、外包那事儿，
 * 晾几天后我要么忘了，要么只在被追问时想起。缺一个『未完成话题台账』，能主动提醒。」
 *
 * 此前待办只存在 `TodoTool.sessionTodos`（进程内 ConcurrentHashMap）：
 * 切会话清空、进程重启丢失、别的会话看不到。于是「我记下了」是句空话。
 *
 * 表按 sessionId 存一行（整份清单 JSON），而不是每个 todo 一行：
 * - `todo_write` 的语义就是「整份替换」，按行存反而要在每次写入时做 diff；
 * - 清单条目数很小（通常 < 20），JSON 一列足够；
 * - 不引入新的一对多关系，迁移和回滚都简单。
 */
@Serializable
@Entity(
    tableName = "session_todos",
    indices = [Index(value = ["sessionId"], unique = true)],
)
data class SessionTodoEntity(
    @PrimaryKey val id: String,
    /** 所属会话。唯一索引 —— 每个会话只有一份当前清单。 */
    @ColumnInfo(defaultValue = "") val sessionId: String,
    /** 整份清单的 JSON（与 TodoTool.TodoList 的序列化格式一致）。 */
    @ColumnInfo(defaultValue = "") val todosJson: String,
    /**
     * 是否还有未完成项（pending + in_progress > 0）。
     *
     * 冗余字段，让「有哪些会话还有事没做完」不必逐行解析 JSON。
     * 写入时由仓储维护，不做数据库生成列 —— SQLite 生成列要求表达式确定，
     * 而条目数需要解析 JSON，做不到。
     */
    @ColumnInfo(defaultValue = "0") val hasOpenItems: Boolean = false,
    @ColumnInfo(defaultValue = "0") val createdAt: Long = 0L,
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0L,
)

@Dao
interface SessionTodoDao {
    @Upsert
    suspend fun upsert(todo: SessionTodoEntity)

    @Query("SELECT * FROM session_todos WHERE sessionId = :sessionId LIMIT 1")
    suspend fun getBySession(sessionId: String): SessionTodoEntity?

    /** 全量（备份导出用）。 */
    @Query("SELECT * FROM session_todos")
    suspend fun getAll(): List<SessionTodoEntity>

    @Query("SELECT * FROM session_todos WHERE sessionId = :sessionId LIMIT 1")
    fun observeBySession(sessionId: String): Flow<SessionTodoEntity?>

    /** 还有未完成项的会话（跨会话台账的查询入口）。 */
    @Query("SELECT * FROM session_todos WHERE hasOpenItems = 1 ORDER BY updatedAt DESC")
    suspend fun getSessionsWithOpenItems(): List<SessionTodoEntity>

    @Query("SELECT * FROM session_todos WHERE hasOpenItems = 1 ORDER BY updatedAt DESC")
    fun observeSessionsWithOpenItems(): Flow<List<SessionTodoEntity>>

    @Query("DELETE FROM session_todos WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: String)

    /** 恢复备份前清空（与其它表同口径）。 */
    @Query("DELETE FROM session_todos")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM session_todos WHERE hasOpenItems = 1")
    suspend fun countSessionsWithOpenItems(): Int
}
