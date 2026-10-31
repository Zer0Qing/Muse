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
 * todo_write 工具。
 *
 * 替换式协议,三态状态机。
 * 每次调用替换会话的完整待办列表。
 *
 * v1.0.92: 新增 [todosFlow]/[observeTodos] — 供 UI 展示待办进度。
 * v2.6.7 fix (AUDIT F5/F6/F7): 会话 id 改由宿主注入；落库失败不再静默；
 * 新增 clearAll / syncFromDatabase 供恢复与切会话使用。
 */
@Suppress("TooManyFunctions") // 待办工具族本身需要执行/持久化/预热/清理多个入口，合并反而碎
object TodoTool {

    @Serializable
    data class TodoItem(
        val content: String,
        val activeForm: String = "",
        // pending / in_progress / completed
        val status: String = "pending",
    )

    @Serializable
    data class TodoList(val todos: List<TodoItem> = emptyList())

    // 会话级存储:sessionId -> TodoList(v2.6.7: 内存快照 + 落库双层,写时一起更新)
    private val sessionTodos = ConcurrentHashMap<String, TodoList>()

    // v1.0.92: 待办快照流 — 每次写入后发布全量映射,UI 据此实时展示进度。
    private val _todosFlow = MutableStateFlow<Map<String, TodoList>>(emptyMap())

    /** v1.0.92: 全量待办快照流(只读)。 */
    val todosFlow: StateFlow<Map<String, TodoList>> = _todosFlow.asStateFlow()

    /** v1.0.92: 订阅指定会话的待办列表(无内容时为空列表)。 */
    fun observeTodos(sessionId: String): Flow<TodoList> = _todosFlow.map { it[sessionId] ?: TodoList() }

    private fun publish() {
        _todosFlow.value = sessionTodos.toMap()
    }

    fun toolDef() = ToolRegistry.ToolDef(
        name = "todo_write",
        description = "管理当前会话的待办清单,用于多步骤工作。" +
            "把复杂任务拆成子任务;单步就能完成的简单任务不需要。" +
            "每次调用会整体替换清单(替换式,不是增量)。" +
            "会话由宿主提供,不要自己编造 session id。",
        parameters = mapOf(
            "todos" to
                "Required. JSON array of todo items: " +
                "[{\"content\":\"...\",\"activeForm\":\"...\"," +
                "\"status\":\"pending|in_progress|completed\"}]",
        ),
        required = setOf("todos"),
        category = "built-in",
        riskLevel = ToolRiskLevel.NORMAL,
    )

    fun execute(args: Map<String, String>): String {
        val sessionId = args["session_id"]?.trim()
            ?: return "Error: session_id parameter is required."
        return executeForSession(sessionId, args)
    }

    /**
     * v2.6.7 fix (AUDIT F5): 用宿主会话 id 执行。
     *
     * 旧实现要求模型自己传 `session_id`，但模型无可靠途径得知当前会话 UUID
     * （系统提示不注入，`{{session_id}}` 只在用户自写模板里展开）。
     * 现在由 `ToolExecutionContext.sessionId` 注入，模型不再需要也不应该猜。
     */
    fun executeForSession(sessionId: String, args: Map<String, String>): String {
        val scoped = sessionId.trim()
        val todosJson = args["todos"]?.trim().orEmpty()
        val todos = parseTodos(todosJson)
        return when {
            scoped.isEmpty() -> "Error: current session is unavailable."
            todosJson.isEmpty() -> "Error: todos parameter is required."
            todos == null -> "Error: failed to parse todos JSON."
            else -> applyTodos(scoped, todos)
        }
    }

    /** 写入内存 + 发布快照，并生成给模型的摘要。 */
    private fun applyTodos(scoped: String, todos: TodoList): String {
        sessionTodos[scoped] = todos
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
        } else {
            null
        }

        return buildString {
            append("Todo list updated: $total total, ")
            append("${counts["completed"]} completed, ")
            append("$inProgressCount in progress, ")
            append("${counts["pending"]} pending.")
            if (warning != null) append("\n$warning")
        }
    }

    fun getTodos(sessionId: String): TodoList? = sessionTodos[sessionId]

    /**
     * 解析模型传入的 todos。
     *
     * 兼容两种形状：裸数组 `[{...}]` 与包装对象 `{"todos":[...]}`。
     * 都失败返回 null，由调用方给错误回执。
     */
    private fun parseTodos(todosJson: String): TodoList? {
        val wrapped = runCatching {
            val arr = AppJson.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(TodoItem.serializer()),
                todosJson,
            )
            TodoList(arr)
        }.getOrNull()
        if (wrapped != null) return wrapped
        return runCatching {
            AppJson.decodeFromString(TodoList.serializer(), todosJson)
        }.getOrNull()
    }

    fun clearSession(sessionId: String) {
        sessionTodos.remove(sessionId)
        publish()
    }

    /**
     * v2.6.7 fix (AUDIT F7): 清空全部会话的内存快照。
     *
     * 备份恢复会把 DB 里的 session_todos 整体替换，但 UI 订阅的是内存流；
     * 不清就会继续显示恢复前的旧清单。
     */
    fun clearAll() {
        sessionTodos.clear()
        publish()
    }

    /**
     * v2.6.7: 持久化出口。由 DI 在启动时注入;null 表示未注入(测试/早期初始化),
     * 此时退化为纯内存,与改造前行为一致。
     */
    @Volatile
    private var persist: (suspend (sessionId: String, list: TodoList) -> Unit)? = null

    /** v2.6.7: 启动时注入持久化实现(写库 + 更新 hasOpenItems)。 */
    fun installPersistence(handler: suspend (sessionId: String, list: TodoList) -> Unit) {
        persist = handler
    }

    /** v2.6.7: 从库里预热内存快照(进程启动/切会话时调用)。 */
    fun primeSnapshot(sessionId: String, list: TodoList) {
        sessionTodos[sessionId] = list
        publish()
    }

    /**
     * v2.6.7 fix (AUDIT F7): 用库副本替换内存快照，包括空清单。
     *
     * [primeSnapshot] 的调用方只在非空时预热，于是「库里已经是空的」这个事实
     * 永远覆盖不掉内存里的旧值。恢复备份后尤其明显：旧待办会一直挂在。
     */
    fun syncFromDatabase(sessionId: String, list: TodoList) {
        if (list.todos.isEmpty()) sessionTodos.remove(sessionId) else sessionTodos[sessionId] = list
        publish()
    }

    /**
     * v2.6.7: 带持久化的执行入口。
     *
     * 与 [execute] 的差别只有一处:写内存后同时落库。分开两个入口是为了让
     * 既有纯函数式测试不必构造数据库。
     */
    suspend fun executePersisted(args: Map<String, String>): String {
        val sessionId = args["session_id"]?.trim().orEmpty()
        if (sessionId.isEmpty()) return "Error: session_id parameter is required."
        return executePersistedForSession(sessionId, args)
    }

    /**
     * v2.6.7 fix (AUDIT F5/F6): 宿主注入会话 id 的持久化入口。
     *
     * 与旧 [executePersisted] 的两处差别：
     *  1. 会话 id 由宿主管，不依赖模型传参与猜测；
     *  2. 落库失败不再只记日志 —— 回执里明确告知模型「本条不会保留」，
     *     否则用户听到「记下了」而库里没有，重启就没了。
     */
    suspend fun executePersistedForSession(sessionId: String, args: Map<String, String>): String {
        val result = executeForSession(sessionId, args)
        val list = sessionTodos[sessionId.trim()]
        val handler = persist
        if (result.startsWith("Error:") || list == null || handler == null) return result
        return runCatching { handler(sessionId.trim(), list) }
            .fold(
                onSuccess = { result },
                onFailure = { error ->
                    io.zer0.common.Logger.w("TodoTool", "待办落库失败 session=$sessionId: ${error.message}")
                    "$result\n[注意] 待办未能写入本地数据库（${error.message ?: "未知原因"}），" +
                        "本次清单只存在于当前内存，重启后会丢失。请告知用户。"
                },
            )
    }
}
