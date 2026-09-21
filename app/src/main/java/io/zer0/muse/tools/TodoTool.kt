package io.zer0.muse.tools

import io.zer0.common.AppJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

/**
 * todo_write 工具(既有实现 todo.ts 实现)。
 *
 * 替换式协议,三态状态机。
 * 每次调用替换会话的完整待办列表。
 *
 * v1.0.92: 新增 [todosFlow]/[observeTodos] — 供 UI 展示待办进度
 * (此前清单仅存内存,用户看不到 AI 的任务分解进度)。
 */
object TodoTool {

    @Serializable
    data class TodoItem(
        val content: String,
        val activeForm: String = "",
        val status: String = "pending", // pending / in_progress / completed
    )

    @Serializable
    data class TodoList(val todos: List<TodoItem> = emptyList())

    // 会话级存储:sessionId -> TodoList
    private val sessionTodos = ConcurrentHashMap<String, TodoList>()

    // v1.0.92: 待办快照流 — 每次写入后发布全量映射,UI 据此实时展示进度。
    private val _todosFlow = MutableStateFlow<Map<String, TodoList>>(emptyMap())

    /** v1.0.92: 全量待办快照流(只读)。 */
    val todosFlow: StateFlow<Map<String, TodoList>> = _todosFlow.asStateFlow()

    /** v1.0.92: 订阅指定会话的待办列表(无内容时为空列表)。 */
    fun observeTodos(sessionId: String): Flow<TodoList> =
        _todosFlow.map { it[sessionId] ?: TodoList() }

    private fun publish() {
        _todosFlow.value = sessionTodos.toMap()
    }

    fun toolDef() = ToolRegistry.ToolDef(
        name = "todo_write",
        description = "Manage the session todo list for multi-step work. " +
            "Decompose complex tasks into sub-tasks; not needed for simple single-step tasks. " +
            "Each call replaces the full list (replacement style).",
        parameters = mapOf(
            "session_id" to "Required. The session identifier to scope the todo list.",
            "todos" to "Required. JSON array of todo items: [{\"content\":\"...\",\"activeForm\":\"...\",\"status\":\"pending|in_progress|completed\"}]",
        ),
        required = setOf("session_id", "todos"),
        category = "built-in",
        riskLevel = ToolRiskLevel.NORMAL,
    )

    fun execute(args: Map<String, String>): String {
        val sessionId = args["session_id"]?.trim()
            ?: return "Error: session_id parameter is required."
        val todosJson = args["todos"]?.trim()
            ?: return "Error: todos parameter is required."

        val todos = try {
            AppJson.decodeFromString(TodoList.serializer(), TodoList.serializer().let {
                // 先解析为数组再包装
                val arr = AppJson.decodeFromString(
                    kotlinx.serialization.builtins.ListSerializer(TodoItem.serializer()),
                    todosJson,
                )
                AppJson.encodeToString(
                    TodoList.serializer(),
                    TodoList(arr),
                )
            })
        } catch (e: Exception) {
            // 尝试作为包装对象解析
            try {
                AppJson.decodeFromString(TodoList.serializer(), todosJson)
            } catch (e2: Exception) {
                return "Error: failed to parse todos JSON: ${e2.message}"
            }
        }

        sessionTodos[sessionId] = todos
        publish()

        // 构建摘要
        val counts = mutableMapOf("pending" to 0, "in_progress" to 0, "completed" to 0)
        for (td in todos.todos) {
            counts[td.status] = (counts[td.status] ?: 0) + 1
        }
        val total = todos.todos.size
        // v1.131: counts 由 mutableMapOf("pending" to 0, "in_progress" to 0, "completed" to 0)
        // 初始化,key 一定存在。用 getValue 替代 []!! — 失败时抛 NoSuchElementException 而非 NPE,
        // 契约更明确,且 IDE 不再标黄。
        val inProgressCount = counts.getValue("in_progress")
        val warning = if (inProgressCount > 1) {
            "Warning: multiple in_progress items ($inProgressCount). Convention: only one at a time."
        } else null

        return buildString {
            append("Todo list updated: $total total, ")
            append("${counts["completed"]} completed, ")
            append("$inProgressCount in progress, ")
            append("${counts["pending"]} pending.")
            if (warning != null) append("\n$warning")
        }
    }

    fun getTodos(sessionId: String): TodoList? = sessionTodos[sessionId]

    fun clearSession(sessionId: String) {
        sessionTodos.remove(sessionId)
        publish()
    }
}
