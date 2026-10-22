package io.zer0.muse.data.todo

import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.muse.tools.TodoTool
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * 会话待办的持久化仓储。
 *
 * 在 `TodoTool` 与 Room 之间做三件事：序列化清单、维护 `hasOpenItems`、
 * 把「还有事没做完的会话」查出来给上层做跨会话提醒。
 */
class SessionTodoRepository(
    private val dao: SessionTodoDao,
) {

    /** 写入某会话的完整清单（替换语义，与 todo_write 一致）。 */
    suspend fun save(sessionId: String, list: TodoTool.TodoList) {
        if (sessionId.isBlank()) return
        val now = System.currentTimeMillis()
        val existing = dao.getBySession(sessionId)
        val entity = SessionTodoEntity(
            id = existing?.id ?: UUID.randomUUID().toString(),
            sessionId = sessionId,
            todosJson = AppJson.encodeToString(TodoTool.TodoList.serializer(), list),
            hasOpenItems = hasOpenItems(list),
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
        )
        dao.upsert(entity)
    }

    /** 读取某会话的清单；无记录或解析失败返回空清单。 */
    suspend fun load(sessionId: String): TodoTool.TodoList {
        val row = dao.getBySession(sessionId) ?: return TodoTool.TodoList()
        return decode(row.todosJson)
    }

    /** 订阅某会话的清单。 */
    fun observe(sessionId: String): Flow<TodoTool.TodoList> = dao.observeBySession(sessionId).map { row ->
        row?.let { decode(it.todosJson) } ?: TodoTool.TodoList()
    }

    /** 还有未完成项的会话（跨会话台账）。 */
    suspend fun sessionsWithOpenItems(): List<SessionTodoEntity> = dao.getSessionsWithOpenItems()

    /** 还有未完成项的会话数，用于开场提示。 */
    suspend fun countSessionsWithOpenItems(): Int = dao.countSessionsWithOpenItems()

    /** 会话删除时清掉它的待办。 */
    suspend fun delete(sessionId: String) {
        dao.deleteBySession(sessionId)
    }

    /**
     * 是否还有未完成项。
     *
     * 只认 pending / in_progress；未知状态按「未完成」处理 ——
     * 宁可多提醒一次，也不要把没做完的事静默标成做完了。
     */
    private fun hasOpenItems(list: TodoTool.TodoList): Boolean = list.todos.any { it.status != "completed" }

    private fun decode(json: String): TodoTool.TodoList {
        if (json.isBlank()) return TodoTool.TodoList()
        return runCatching {
            AppJson.decodeFromString(TodoTool.TodoList.serializer(), json)
        }.onFailure {
            // 解析失败不能当成「没有待办」静默吞掉：记日志，返回空清单但不删库，
            // 下次写入会覆盖。
            Logger.w("SessionTodoRepo", "待办 JSON 解析失败，保留原记录: ${it.message}")
        }.getOrDefault(TodoTool.TodoList())
    }
}
