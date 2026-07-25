package io.zer0.muse.tools

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 异步委派任务结果回灌 — 非阻塞委派的核心基础设施。
 *
 * 参考 openhanako DeferredResultStore:
 *  - 主 agent 调 subagent 立即返回 taskId,不阻塞主对话
 *  - 子任务完成后通过 [resolve] 回灌结果
 *  - ChatViewModel 订阅 [completedTasks] 获取已完成任务,作为 interlude 注入主对话
 */
class DeferredResultStore {
    enum class TaskStatus { PENDING, RESOLVED, FAILED, ABORTED }

    data class DeferredTask(
        val taskId: String,
        val parentSessionId: String,
        val threadId: String?,
        val label: String?,
        val taskSummary: String,
        val status: TaskStatus,
        val result: String? = null,
        val error: String? = null,
        val createdAt: Long = System.currentTimeMillis(),
        val completedAt: Long? = null,
    )

    private val _tasks = MutableStateFlow<Map<String, DeferredTask>>(emptyMap())
    val tasks: StateFlow<Map<String, DeferredTask>> = _tasks.asStateFlow()

    /** 已完成待回灌的任务(parentSessionId -> 待回灌结果列表)。 */
    private val _completedTasks = MutableStateFlow<Map<String, List<DeferredTask>>>(emptyMap())
    val completedTasks: StateFlow<Map<String, List<DeferredTask>>> = _completedTasks.asStateFlow()

    /** 注册延迟任务。 */
    fun defer(taskId: String, parentSessionId: String, threadId: String?, label: String?, taskSummary: String) {
        val task = DeferredTask(taskId, parentSessionId, threadId, label, taskSummary, TaskStatus.PENDING)
        _tasks.value = _tasks.value + (taskId to task)
    }

    /** 标记任务完成(成功)。 */
    fun resolve(taskId: String, result: String) {
        val task = _tasks.value[taskId] ?: return
        val completed = task.copy(status = TaskStatus.RESOLVED, result = result, completedAt = System.currentTimeMillis())
        _tasks.value = _tasks.value + (taskId to completed)
        // 加入待回灌列表
        val current = _completedTasks.value.toMutableMap()
        val list = current[task.parentSessionId]?.toMutableList() ?: mutableListOf()
        list.add(completed)
        current[task.parentSessionId] = list
        _completedTasks.value = current
    }

    /** 标记任务失败。 */
    fun fail(taskId: String, error: String) {
        val task = _tasks.value[taskId] ?: return
        val failed = task.copy(status = TaskStatus.FAILED, error = error, completedAt = System.currentTimeMillis())
        _tasks.value = _tasks.value + (taskId to failed)
        val current = _completedTasks.value.toMutableMap()
        val list = current[task.parentSessionId]?.toMutableList() ?: mutableListOf()
        list.add(failed)
        current[task.parentSessionId] = list
        _completedTasks.value = current
    }

    /** 中止任务。 */
    fun abort(taskId: String) {
        val task = _tasks.value[taskId] ?: return
        _tasks.value = _tasks.value + (taskId to task.copy(status = TaskStatus.ABORTED, completedAt = System.currentTimeMillis()))
    }

    /** 消费并清除指定会话的待回灌任务(回灌后调用)。 */
    fun consumeCompleted(sessionId: String): List<DeferredTask> {
        val result = _completedTasks.value[sessionId] ?: emptyList()
        if (result.isNotEmpty()) {
            val current = _completedTasks.value.toMutableMap()
            current.remove(sessionId)
            _completedTasks.value = current
        }
        return result
    }

    /** 获取任务状态。 */
    fun getTask(taskId: String): DeferredTask? = _tasks.value[taskId]

    /** 列出待处理任务。 */
    fun listPendingTasks(): List<DeferredTask> = _tasks.value.values.filter { it.status == TaskStatus.PENDING }

    /** 清理已完成任务(定期调用避免内存泄漏)。 */
    fun cleanupCompleted(maxAgeMs: Long = 30 * 60 * 1000L) {
        val now = System.currentTimeMillis()
        _tasks.value = _tasks.value.filter { (_, task) ->
            task.status == TaskStatus.PENDING || (task.completedAt != null && now - task.completedAt < maxAgeMs)
        }
    }
}
