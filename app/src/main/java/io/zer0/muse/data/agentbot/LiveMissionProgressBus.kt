package io.zer0.muse.data.agentbot

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Agent 运行期实时事件总线。
 *
 * 承担三类高频、低延迟状态传递，避免将高频输出与实时日志反复刷写 Room：
 * 1. 动态任务卡的实时步骤进度（持久化落地仍由 `bot_task_steps` 负责）；
 * 2. 本地沙盒（proot Ubuntu / Python / Node / Shell）实时终端日志流；
 * 3. 运行中任务的活体插话队列（Steering）与活跃虚拟屏句柄状态。
 */
class LiveMissionProgressBus {

    data class LiveStep(
        val stepId: String,
        val taskId: String,
        val stepIndex: Int,
        val toolName: String,
        val displayLabel: String,
        val status: String,
        val summary: String = "",
        val assigneeBotId: String = "",
        val startedAt: Long = System.currentTimeMillis(),
        val finishedAt: Long = 0L,
    )

    data class SandboxLogLine(
        val id: Long,
        val taskId: String,
        val source: String,
        val text: String,
        val isError: Boolean = false,
        val timestamp: Long = System.currentTimeMillis(),
    )

    data class ActiveVirtualDisplay(
        val displayId: Int,
        val packageName: String,
        val taskId: String = "",
        /** 当前正在执行的动作字幕；空串表示只展示包名。 */
        val action: String = "",
        val updatedAt: Long = System.currentTimeMillis(),
    )

    private val _liveSteps = MutableStateFlow<Map<String, List<LiveStep>>>(emptyMap())
    val liveSteps: StateFlow<Map<String, List<LiveStep>>> = _liveSteps.asStateFlow()

    private val _sandboxLogs = MutableStateFlow<List<SandboxLogLine>>(emptyList())
    val sandboxLogs: StateFlow<List<SandboxLogLine>> = _sandboxLogs.asStateFlow()

    private val _activeVirtualDisplay = MutableStateFlow<ActiveVirtualDisplay?>(null)
    val activeVirtualDisplay: StateFlow<ActiveVirtualDisplay?> = _activeVirtualDisplay.asStateFlow()

    private val _pendingSteerCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val pendingSteerCounts: StateFlow<Map<String, Int>> = _pendingSteerCounts.asStateFlow()

    private val steerQueues = ConcurrentHashMap<String, ConcurrentLinkedQueue<String>>()
    private val logSeq = java.util.concurrent.atomic.AtomicLong(0L)

    fun onStepStarted(step: LiveStep) {
        _liveSteps.update { current ->
            val list = current[step.taskId].orEmpty().filterNot { it.stepId == step.stepId } + step
            current + (step.taskId to list.sortedBy { it.stepIndex })
        }
        appendSandboxLog(
            taskId = step.taskId,
            source = step.toolName.ifBlank { "agent" },
            text = "▸ ${step.displayLabel.ifBlank { step.toolName }}",
        )
    }

    fun onStepFinished(
        taskId: String,
        stepId: String,
        status: String = StepExecutionStatus.SUCCEEDED,
        summary: String = "",
        finishedAt: Long = System.currentTimeMillis(),
    ) {
        _liveSteps.update { current ->
            val existing = current[taskId] ?: return@update current
            val updated = existing.map { item ->
                if (item.stepId == stepId) {
                    item.copy(
                        status = status,
                        summary = summary.ifBlank { item.summary },
                        finishedAt = finishedAt,
                    )
                } else {
                    item
                }
            }
            current + (taskId to updated)
        }
        if (summary.isNotBlank()) {
            appendSandboxLog(
                taskId = taskId,
                source = "output",
                text = summary.take(MAX_LOG_LINE_CHARS),
                isError = status == StepExecutionStatus.FAILED,
            )
        }
    }

    fun clearTaskSteps(taskId: String) {
        _liveSteps.update { it - taskId }
    }

    fun appendSandboxLog(
        taskId: String,
        source: String,
        text: String,
        isError: Boolean = false,
    ) {
        if (text.isBlank()) return
        val entry = SandboxLogLine(
            id = logSeq.incrementAndGet(),
            taskId = taskId,
            source = source,
            text = text.trimEnd().take(MAX_LOG_LINE_CHARS),
            isError = isError,
        )
        _sandboxLogs.update { current ->
            (current + entry).takeLast(MAX_SANDBOX_LOG_LINES)
        }
    }

    fun clearSandboxLogs() {
        _sandboxLogs.value = emptyList()
    }

    /** 将用户的实时插话指令投入目标任务的待注入队列。 */
    fun enqueueSteer(taskId: String, instruction: String) {
        val cleaned = instruction.trim()
        if (taskId.isBlank() || cleaned.isBlank()) return
        val queue = steerQueues.computeIfAbsent(taskId) { ConcurrentLinkedQueue() }
        queue.add(cleaned)
        _pendingSteerCounts.update { it + (taskId to queue.size) }
        appendSandboxLog(taskId = taskId, source = "steer", text = "↳ 用户补充要求：$cleaned")
    }

    /** 供执行器在下一轮工具调用前取出全部待注入插话指令。 */
    fun drainSteer(taskId: String): List<String> {
        val queue = steerQueues[taskId] ?: return emptyList()
        val drained = mutableListOf<String>()
        while (true) {
            val next = queue.poll() ?: break
            drained += next
        }
        if (drained.isNotEmpty()) {
            _pendingSteerCounts.update { map ->
                if (queue.isEmpty()) map - taskId else map + (taskId to queue.size)
            }
        }
        return drained
    }

    fun reportVirtualDisplay(
        displayId: Int,
        packageName: String,
        taskId: String = "",
        action: String = "",
    ) {
        _activeVirtualDisplay.value = ActiveVirtualDisplay(
            displayId = displayId,
            packageName = packageName,
            taskId = taskId,
            action = action,
        )
    }

    fun clearVirtualDisplay(displayId: Int? = null) {
        _activeVirtualDisplay.update { current ->
            if (displayId == null || current?.displayId == displayId) null else current
        }
    }

    companion object {
        private const val MAX_SANDBOX_LOG_LINES = 240
        private const val MAX_LOG_LINE_CHARS = 400
    }
}
