package io.zer0.muse.tools

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

/**
 * 异步委派任务结果回灌 — 非阻塞委派的核心基础设施。
 *
 * 按 既有实现 DeferredResultStore:
 *  - 主 agent 调 subagent 立即返回 taskId,不阻塞主对话
 *  - 子任务完成后通过 [resolve] 回灌结果
 *  - ChatViewModel 订阅 [completedTasks] 获取已完成任务,作为 interlude 注入主对话
 *
 * 审查修复 (2.0):
 *  - B-33: 全部读-改-写改为 StateFlow.update CAS 变换内完成,多子任务并发
 *    resolve/fail 不再互相覆盖丢失条目。
 *  - A-15: resolve/fail 对已 ABORTED 任务拒绝回灌(中止后后台完成的结果不得注入
 *    主对话);abort 同时取消 [attachJob] 登记的后台任务,停止/切会话后不再有
 *    幽灵结果在后台继续跑。
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

    /** A-15: 任务 → 后台 Job 登记(abort 时取消,防幽灵任务继续跑)。 */
    private val jobs = ConcurrentHashMap<String, Job>()

    /** A-15: 登记后台任务 Job,供 [abort] 取消。 */
    fun attachJob(taskId: String, job: Job) {
        jobs[taskId] = job
    }

    /** 注册延迟任务。 */
    fun defer(taskId: String, parentSessionId: String, threadId: String?, label: String?, taskSummary: String) {
        val task = DeferredTask(taskId, parentSessionId, threadId, label, taskSummary, TaskStatus.PENDING)
        _tasks.update { it + (taskId to task) }
    }

    /** 标记任务完成(成功)。 */
    fun resolve(taskId: String, result: String) {
        val completed = markCompleted(taskId) { it.copy(status = TaskStatus.RESOLVED, result = result) } ?: return
        enqueueCompleted(completed)
    }

    /** 标记任务失败。 */
    fun fail(taskId: String, error: String) {
        val completed = markCompleted(taskId) { it.copy(status = TaskStatus.FAILED, error = error) } ?: return
        enqueueCompleted(completed)
    }

    /**
     * A-15: 在 CAS 变换内完成"读任务 → 校验非 ABORTED → 标记完成"。
     * 已中止任务直接拒绝回灌(返回 null),后台在取消传播前完成的旧结果不会进入待回灌队列。
     */
    private fun markCompleted(taskId: String, transform: (DeferredTask) -> DeferredTask): DeferredTask? {
        var completed: DeferredTask? = null
        _tasks.update { map ->
            val task = map[taskId] ?: return@update map
            if (task.status == TaskStatus.ABORTED) return@update map
            completed = transform(task).copy(completedAt = System.currentTimeMillis())
            map + (taskId to completed!!)
        }
        return completed
    }

    /** B-33: 待回灌队列追加在 CAS 变换内完成。 */
    private fun enqueueCompleted(completed: DeferredTask) {
        jobs.remove(completed.taskId)
        _completedTasks.update { map ->
            val list = map[completed.parentSessionId]?.toMutableList() ?: mutableListOf()
            list.add(completed)
            map + (completed.parentSessionId to list)
        }
    }

    /** 中止任务:A-15 同时取消后台 Job。 */
    fun abort(taskId: String) {
        jobs.remove(taskId)?.cancel()
        _tasks.update { map ->
            val task = map[taskId] ?: return@update map
            map + (taskId to task.copy(status = TaskStatus.ABORTED, completedAt = System.currentTimeMillis()))
        }
    }

    /** 消费并清除指定会话的待回灌任务(回灌后调用)。 */
    fun consumeCompleted(sessionId: String): List<DeferredTask> {
        var result: List<DeferredTask> = emptyList()
        _completedTasks.update { map ->
            result = map[sessionId] ?: emptyList()
            if (result.isEmpty()) map else map - sessionId
        }
        return result
    }

    /**
     * A-10: 消费并清除“未归属”的待回灌任务 —— 即存入空串父会话 id 下的结果。
     *
     * 业务原因:subagent_task 依赖 LLM 传 parent_session_id 来归属结果,但 LLM 可能省略该参数,
     * 此时 [defer] 以空串作 key 存入,而 [consumeCompleted] 按真实 session id 精确匹配,
     * 空串结果永远匹配不上,会被 [cleanupCompleted] 在 30 分钟后清掉,用户看不到结果。
     *
     * ChatViewModel 消费当前会话结果后,再调用本方法兜底拉取这些未归属结果,
     * 注入当前活跃会话(降级行为,须由调用方记录 ERROR 日志说明原因)。
     */
    fun consumeUnowned(): List<DeferredTask> {
        return consumeCompleted("")
    }

    /** 获取任务状态。 */
    fun getTask(taskId: String): DeferredTask? = _tasks.value[taskId]

    /** 列出待处理任务。 */
    fun listPendingTasks(): List<DeferredTask> = _tasks.value.values.filter { it.status == TaskStatus.PENDING }

    /** 清理已完成任务(定期调用避免内存泄漏)。 */
    fun cleanupCompleted(maxAgeMs: Long = 30 * 60 * 1000L) {
        val now = System.currentTimeMillis()
        _tasks.update { map ->
            map.filter { (_, task) ->
                task.status == TaskStatus.PENDING || (task.completedAt != null && now - task.completedAt < maxAgeMs)
            }
        }
    }
}
