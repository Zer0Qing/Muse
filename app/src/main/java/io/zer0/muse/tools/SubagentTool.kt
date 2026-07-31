package io.zer0.muse.tools

import io.zer0.common.Logger
// v1.0.53: 持久化版 SubagentThreadStore(替代旧 tools.SubagentThreadStore 内存版)
import io.zer0.muse.data.subagent.SubagentThreadStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * subagent_task 工具(openhanako subagent-tool.ts 移植)。
 *
 * 非阻塞后台子 agent 任务执行,支持线程续接与关闭。
 *
 * 三件套操作(参考 openhanako):
 *  - launch:启动新子 agent 任务,返回 taskId + threadId
 *  - reply :续接同一子 agent(按 threadId),传入新 task
 *  - close :关闭子 agent 线程,释放资源
 *
 * 辅助操作:
 *  - status :查询任务进度
 *  - cancel :中止任务
 *  - list  :列出所有任务
 *
 * 权限档继承(改造 3):
 *  - access 参数(read/write,省略时默认 read,继承父会话档)
 *  - 非法值直接拒绝,不静默降级
 *  - 通过 [DelegationContract.DelegationRequest.access] 传给 [SkillExecutor.delegateAgent],
 *    由其校验 delegateDepth 与 access(简化方案:本工具只做参数解析)
 *
 * 异步结果回灌(改造 4):
 *  - launch/reply 立即返回 taskId,不阻塞主对话
 *  - 子任务完成后通过 [DeferredResultStore] 回灌结果
 *  - ChatViewModel 可订阅 [DeferredResultStore.completedTasks] 获取已完成任务并注入主对话
 *  - [cleanupCompletedTasks] 定时清理已完成任务,与 DeferredResultStore 同步
 */
object SubagentTool {

    /** 已完成任务保留时长:30 分钟后清理。 */
    private const val COMPLETED_TASK_TTL_MS = 30L * 60 * 1000

    data class TaskState(
        val taskId: String,
        val agentId: String,
        val description: String,
        val status: String, // pending / running / completed / failed / cancelled
        val progress: String = "",
        val result: String? = null,
        val createdAt: Long = System.currentTimeMillis(),
        /** v1.202: 任务完成时间(用于清理),null 表示未完成。 */
        val completedAt: Long? = null,
        /** v1.202: 关联的子 agent 线程 ID(用于 reply/close 续接)。 */
        val threadId: String? = null,
    )

    private val tasks = ConcurrentHashMap<String, TaskState>()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val _taskListFlow = MutableStateFlow<List<TaskState>>(emptyList())
    val taskListFlow: StateFlow<List<TaskState>> = _taskListFlow

    fun toolDef() = ToolRegistry.ToolDef(
        name = "subagent_task",
        description = "Launch / continue / close a non-blocking sub-agent task. " +
            "Actions: launch (start new task, returns taskId+threadId), " +
            "reply (continue same sub-agent by threadId), " +
            "close (release thread), " +
            "status (check progress), cancel (abort task), list (all tasks).",
        parameters = mapOf(
            "action" to "Required. One of: launch / reply / close / status / cancel / list.",
            "agent_id" to "Required for launch. The assistant id to run the task.",
            "task" to "Required for launch/reply. Task description / prompt.",
            "task_id" to "Required for status/cancel. The task id returned by launch/reply.",
            "thread_id" to "Required for reply/close. The thread id returned by launch.",
            "parent_session_id" to "Optional. Parent session id for context isolation and result回灌.",
            "access" to "Optional. Permission tier: read / write (default read, inherits parent session).",
            "label" to "Optional. Display label for the task.",
        ),
        required = setOf("action"),
        category = "built-in",
        // 与 ToolPermissionResolver.EXPLICIT_RISK_OVERRIDES 保持一致:subagent_task 归 HIGH
        riskLevel = ToolRiskLevel.HIGH,
    )

    /**
     * 执行 subagent_task 工具。
     *
     * @param args 工具参数
     * @param skillExecutor Skill 执行器(用于 delegateAgent 跑子助手 LLM)
     * @param subagentThreadStore 子 agent 线程管理器(线程续接/关闭/串行化)
     * @param deferredResultStore 异步结果回灌存储(非阻塞结果回灌主对话)
     * @param appScope 应用级 CoroutineScope(后台执行非阻塞任务,切页/后台不中断)
     */
    suspend fun execute(
        args: Map<String, String>,
        skillExecutor: SkillExecutor,
        subagentThreadStore: SubagentThreadStore,
        deferredResultStore: DeferredResultStore,
        appScope: CoroutineScope,
    ): String {
        val action = args["action"]?.trim() ?: return "Error: action parameter is required."

        // list/status 时顺便清理已完成任务,避免内存泄漏
        if (action == "list" || action == "status") {
            cleanupCompletedTasks(deferredResultStore)
        }

        return when (action) {
            "launch" -> doLaunch(args, skillExecutor, subagentThreadStore, deferredResultStore, appScope)
            "reply" -> doReply(args, skillExecutor, subagentThreadStore, deferredResultStore, appScope)
            "close" -> doClose(args, subagentThreadStore)

            "status" -> {
                val taskId = args["task_id"]?.trim()
                    ?: return "Error: task_id is required for status."
                val state = tasks[taskId] ?: return "Error: task '$taskId' not found."
                buildString {
                    append("Task ${state.taskId}: status=${state.status}, progress=${state.progress}")
                    if (state.threadId != null) append(", threadId=${state.threadId}")
                    if (state.result != null) append("\nResult: ${state.result.take(200)}")
                }
            }

            "cancel" -> {
                val taskId = args["task_id"]?.trim()
                    ?: return "Error: task_id is required for cancel."
                jobs[taskId]?.cancel()
                // v1.131: 任务可能已被清理,用安全更新替代 !! — 防止 NPE。
                tasks[taskId]?.let { current ->
                    tasks[taskId] = current.copy(
                        status = "cancelled",
                        completedAt = System.currentTimeMillis(),
                    )
                }
                deferredResultStore.abort(taskId)
                updateFlow()
                "Task $taskId cancelled."
            }

            "list" -> {
                val all = tasks.values.sortedByDescending { it.createdAt }
                if (all.isEmpty()) return "No background tasks."
                buildString {
                    appendLine("Background tasks (${all.size}):")
                    for (t in all.take(20)) {
                        appendLine("- ${t.taskId}: ${t.status} | ${t.description.take(40)} | ${t.progress}")
                    }
                }.trimEnd()
            }

            else -> "Error: action must be launch/reply/close/status/cancel/list."
        }
    }

    /**
     * 启动新子 agent 任务(改造 2:launch)。
     *
     * 流程:
     *  1. 解析参数(agent_id / task / access / label / parent_session_id)
     *  2. 生成 threadId + taskId
     *  3. 注册线程到 [SubagentThreadStore]
     *  4. 注册延迟结果到 [DeferredResultStore]
     *  5. appScope 后台异步执行 delegateAgent,完成后回灌结果
     *  6. 立即返回 taskId + threadId
     */
    private suspend fun doLaunch(
        args: Map<String, String>,
        skillExecutor: SkillExecutor,
        subagentThreadStore: SubagentThreadStore,
        deferredResultStore: DeferredResultStore,
        appScope: CoroutineScope,
    ): String {
        val agentId = args["agent_id"]?.trim()
            ?: return "Error: agent_id is required for launch."
        val task = args["task"]?.trim()
            ?: return "Error: task is required for launch."
        val parentSessionId = args["parent_session_id"]?.trim()?.takeIf { it.isNotBlank() }
        val label = args["label"]?.trim()?.takeIf { it.isNotBlank() }
        // 改造 3:权限档解析,非法值直接拒绝
        val access = parseAccess(args["access"])
            ?: return "Error: access must be 'read' or 'write' (got: ${args["access"]})."

        val threadId = "thread-" + UUID.randomUUID().toString().take(12)
        val taskId = UUID.randomUUID().toString().take(12)

        // 注册线程(供后续 reply/close 续接)
        subagentThreadStore.beginRun(threadId, agentId, parentSessionId)
        // 注册延迟任务(供 ChatViewModel 回灌主对话)
        deferredResultStore.defer(taskId, parentSessionId ?: "", threadId, label, task)
        // 注册本地 TaskState(供 status/list 查询)
        tasks[taskId] = TaskState(
            taskId = taskId,
            agentId = agentId,
            description = task,
            status = "pending",
            progress = "Queued",
            threadId = threadId,
        )
        updateFlow()

        val job = appScope.launch(Dispatchers.IO) {
            runSubagent(
                taskId = taskId,
                threadId = threadId,
                agentId = agentId,
                task = task,
                parentSessionId = parentSessionId,
                access = access,
                label = label,
                skillExecutor = skillExecutor,
                subagentThreadStore = subagentThreadStore,
                deferredResultStore = deferredResultStore,
            )
        }
        jobs[taskId] = job

        return "Task launched: taskId=$taskId, threadId=$threadId, agent=$agentId. " +
            "Use action=status&task_id=$taskId to check progress, " +
            "action=reply&thread_id=$threadId to continue, " +
            "action=close&thread_id=$threadId to release."
    }

    /**
     * 续接同一子 agent(改造 2:reply)。
     *
     * 流程:
     *  1. 解析 thread_id / task / access / label
     *  2. 验证线程存在且 ACTIVE
     *  3. 注册新 taskId(复用同一 threadId)
     *  4. appScope 后台异步执行,通过 runSerialized 串行化(同一 threadId 任务排队)
     */
    private suspend fun doReply(
        args: Map<String, String>,
        skillExecutor: SkillExecutor,
        subagentThreadStore: SubagentThreadStore,
        deferredResultStore: DeferredResultStore,
        appScope: CoroutineScope,
    ): String {
        val threadId = args["thread_id"]?.trim()
            ?: return "Error: thread_id is required for reply."
        val task = args["task"]?.trim()
            ?: return "Error: task is required for reply."
        val label = args["label"]?.trim()?.takeIf { it.isNotBlank() }
        val access = parseAccess(args["access"])
            ?: return "Error: access must be 'read' or 'write' (got: ${args["access"]})."

        val entry = subagentThreadStore.getThread(threadId)
            ?: return "Error: thread '$threadId' not found."
        if (entry.status == SubagentThreadStore.ThreadStatus.CLOSED) {
            return "Error: thread '$threadId' is closed. Start a new task with action=launch."
        }

        val taskId = UUID.randomUUID().toString().take(12)
        deferredResultStore.defer(taskId, entry.parentSessionId ?: "", threadId, label, task)
        tasks[taskId] = TaskState(
            taskId = taskId,
            agentId = entry.assistantId,
            description = task,
            status = "pending",
            progress = "Queued",
            threadId = threadId,
        )
        updateFlow()

        val job = appScope.launch(Dispatchers.IO) {
            runSubagent(
                taskId = taskId,
                threadId = threadId,
                agentId = entry.assistantId,
                task = task,
                parentSessionId = entry.parentSessionId,
                access = access,
                label = label,
                skillExecutor = skillExecutor,
                subagentThreadStore = subagentThreadStore,
                deferredResultStore = deferredResultStore,
            )
        }
        jobs[taskId] = job

        return "Reply queued: taskId=$taskId, threadId=$threadId. " +
            "Use action=status&task_id=$taskId to check progress."
    }

    /**
     * 关闭子 agent 线程(改造 2:close)。
     *
     * 关闭后该 threadId 不可再 reply;正在执行的任务会跑完,但后续 reply 会失败。
     */
    private suspend fun doClose(
        args: Map<String, String>,
        subagentThreadStore: SubagentThreadStore,
    ): String {
        val threadId = args["thread_id"]?.trim()
            ?: return "Error: thread_id is required for close."
        val entry = subagentThreadStore.getThread(threadId)
            ?: return "Error: thread '$threadId' not found."
        if (entry.status == SubagentThreadStore.ThreadStatus.CLOSED) {
            return "Thread '$threadId' is already closed."
        }
        subagentThreadStore.closeThread(threadId)
        return "Thread '$threadId' closed."
    }

    /**
     * 后台执行子 agent 任务(串行化运行)。
     *
     * 通过 [SubagentThreadStore.runSerialized] 保证同一 threadId 的多个任务串行执行,
     * 避免并发竞争。完成后通过 [DeferredResultStore] 回灌结果(成功 resolve / 失败 fail)。
     */
    private suspend fun runSubagent(
        taskId: String,
        threadId: String,
        agentId: String,
        task: String,
        parentSessionId: String?,
        access: String,
        label: String?,
        skillExecutor: SkillExecutor,
        subagentThreadStore: SubagentThreadStore,
        deferredResultStore: DeferredResultStore,
    ) {
        // 标记 running
        tasks[taskId]?.let { current ->
            tasks[taskId] = current.copy(status = "running", progress = "Running...")
        }
        updateFlow()

        try {
            val request = DelegationContract.DelegationRequest(
                requestId = taskId,
                task = task,
                targetType = DelegationContract.DelegationRequest.TargetType.ASSISTANT,
                targetId = agentId,
                parentSessionId = parentSessionId,
                threadId = threadId,
                access = access,
                nonBlocking = true,
                label = label,
            )
            val result = subagentThreadStore.runSerialized(threadId) {
                skillExecutor.delegateAgent(request)
            }
            if (result.success) {
                tasks[taskId]?.let { current ->
                    tasks[taskId] = current.copy(
                        status = "completed",
                        progress = "Done",
                        result = result.resultText,
                        completedAt = System.currentTimeMillis(),
                    )
                }
                deferredResultStore.resolve(taskId, result.resultText)
            } else {
                val err = result.error ?: "Unknown error"
                tasks[taskId]?.let { current ->
                    tasks[taskId] = current.copy(
                        status = "failed",
                        progress = "Error: $err",
                        completedAt = System.currentTimeMillis(),
                    )
                }
                deferredResultStore.fail(taskId, err)
            }
        } catch (e: Exception) {
            Logger.w("SubagentTool", "子 agent 任务执行失败: taskId=$taskId, threadId=$threadId", e)
            val err = e.message ?: e.javaClass.simpleName
            tasks[taskId]?.let { current ->
                tasks[taskId] = current.copy(
                    status = "failed",
                    progress = "Error: $err",
                    completedAt = System.currentTimeMillis(),
                )
            }
            deferredResultStore.fail(taskId, err)
        }
        updateFlow()
    }

    /**
     * 解析 access 参数(改造 3:权限档继承)。
     *
     * - 省略/空白 → "read"(继承父会话默认档)
     * - "read" / "write"(大小写不敏感)→ 对应值
     * - 其他 → null(表示非法值,由调用方返回错误,不静默降级)
     */
    private fun parseAccess(raw: String?): String? {
        if (raw.isNullOrBlank()) return "read"
        return when (raw.trim().lowercase()) {
            "read" -> "read"
            "write" -> "write"
            else -> null
        }
    }

    private fun updateFlow() {
        _taskListFlow.value = tasks.values.toList()
    }

    fun getTask(taskId: String): TaskState? = tasks[taskId]

    /**
     * 清理已完成超过 [COMPLETED_TASK_TTL_MS] 的任务(改造 4:tasks 清理机制)。
     *
     * - 移除本地 [tasks] / [jobs] 中已完成(pending 之外)且超时的条目
     * - 同步调用 [DeferredResultStore.cleanupCompleted] 保持两边状态一致
     *
     * 调用时机:
     *  - list / status 操作时顺便调用(本工具内部触发)
     *  - 外部定时器(如 ChatViewModel 的定时器)显式调用
     */
    fun cleanupCompletedTasks(deferredResultStore: DeferredResultStore? = null) {
        val now = System.currentTimeMillis()
        val expiredKeys = tasks.entries.filter { (_, state) ->
            state.completedAt != null && (now - state.completedAt) > COMPLETED_TASK_TTL_MS
        }.map { it.key }
        if (expiredKeys.isNotEmpty()) {
            expiredKeys.forEach { id ->
                tasks.remove(id)
                jobs.remove(id)
            }
            updateFlow()
        }
        // 同步清理 DeferredResultStore,保持两边状态一致
        deferredResultStore?.cleanupCompleted(COMPLETED_TASK_TTL_MS)
    }
}
