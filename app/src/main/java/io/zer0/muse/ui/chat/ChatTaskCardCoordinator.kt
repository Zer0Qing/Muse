package io.zer0.muse.ui.chat

import io.zer0.common.resultOf
import io.zer0.muse.tools.ToolRegistry
import io.zer0.muse.ui.taskcard.TaskCardPhase
import io.zer0.muse.ui.taskcard.TaskStep
import io.zer0.muse.ui.taskcard.TaskStepStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * v1.134 P1-5: 任务卡 Coordinator — 从 ChatViewModel 抽离的任务卡状态与重试逻辑。
 *
 * 职责:
 *  - [updateTaskCardPhase]: 更新任务卡阶段(PLANNING / EXECUTING / DONE)
 *  - [updateTaskCardStep]: 精准更新单个 TaskStep,避免全表重建
 *  - [toggleTaskCardExpand]: 切换任务卡展开 / 折叠
 *  - [retryFailedStep]: 重试任务卡中失败的步骤(用户主动触发)
 *  - [isToolResultSuccess]: 工具执行结果成功 / 失败判定
 *
 * 设计说明:
 *  - 与 ChatStreamCoordinator / ChatMiscCoordinator 同源,均通过 [ChatStateAccessor] 读写 state,
 *    不反向依赖 ChatViewModel。
 *  - launchStream 主体仍保留在 ChatViewModel(因捕获大量闭包变量,完全抽离回归风险高,
 *    按 project_memory Lessons Learned),其内部对 updateTaskCardPhase / updateTaskCardStep 的调用
 *    通过 ChatViewModel 转发到本 Coordinator。
 */
class ChatTaskCardCoordinator(
    private val accessor: ChatStateAccessor,
    private val toolRegistry: ToolRegistry,
    /** Phase 3: 单步重试执行超时(默认 2 分钟,与工具执行超时对齐);测试可注入更短值。 */
    private val retryTimeoutMs: Long = DEFAULT_RETRY_TIMEOUT_MS,
) {
    private val routeGuard = io.zer0.muse.tools.ToolRouteExecutionGuard(toolRegistry)

    /** 更新任务卡阶段(PLANNING / EXECUTING / DONE)。 */
    fun updateTaskCardPhase(taskCardId: String?, phase: TaskCardPhase) {
        // v1.0.53: send_sticker-only 场景不建卡,可空 taskCardId 直接跳过
        if (taskCardId == null) return
        accessor.update { state ->
            val card = state.taskCards[taskCardId] ?: return@update state
            state.copy(taskCards = state.taskCards + (taskCardId to card.copy(phase = phase)))
        }
    }

    /**
     * v1.98 (P-TOOL): 精准更新单个 TaskStep,避免 mapValues + mapIndexed 全表重建。
     *
     * 旧实现每次工具步骤状态变更都遍历整个 taskCards map + 每个 card 的 steps 列表,
     * 高频 onProgress 回调下导致 Compose 重组风暴。新实现直接按 key 定位 + 按 index 更新。
     */
    fun updateTaskCardStep(
        taskCardId: String?,
        stepIndex: Int,
        transform: (TaskStep) -> TaskStep,
    ) {
        // v1.0.53: send_sticker-only 场景不建卡,可空 taskCardId 直接跳过
        if (taskCardId == null) return
        accessor.update { state ->
            val card = state.taskCards[taskCardId] ?: return@update state
            if (stepIndex !in card.steps.indices) return@update state
            val newSteps = card.steps.toMutableList()
            newSteps[stepIndex] = transform(newSteps[stepIndex])
            // v1.0.47 P8-3: 工具失败时自动展开 TaskCard,让用户立即看到错误详情
            val shouldAutoExpand = newSteps[stepIndex].status == TaskStepStatus.FAILED
            state.copy(
                taskCards = state.taskCards + (taskCardId to card.copy(
                    steps = newSteps,
                    isExpanded = if (shouldAutoExpand) true else card.isExpanded,
                )),
            )
        }
    }

    /** 切换任务卡展开 / 折叠状态。 */
    fun toggleTaskCardExpand(taskCardId: String) {
        accessor.update { state ->
            val card = state.taskCards[taskCardId] ?: return@update state
            state.copy(taskCards = state.taskCards + (taskCardId to card.copy(isExpanded = !card.isExpanded)))
        }
    }

    /**
     * 重试任务卡中失败/超时的步骤。
     * - stepId = "ALL_FAILED":重试全部可重试步骤(FAILED / TIMED_OUT)
     * - stepId = 具体 step id:重试单个可重试步骤
     *
     * Phase 3 可靠性增强:
     *  - 单步重试受 [retryTimeoutMs] 约束,超时写回 [TaskStepStatus.TIMED_OUT];
     *  - 取消(CancellationException)不被吞掉 — 先把步骤写成 [TaskStepStatus.CANCELLED]
     *    再向上抛出,避免 UI 永远停在 RUNNING。
     *
     * 注意:重试仅更新 UI 状态(RUNNING → SUCCESS / FAILED / TIMED_OUT / CANCELLED),
     * 不重新请求 LLM(工具参数已在步骤中保留)。
     * 若需要让 LLM 基于新结果继续,用户应手动重生成。
     */
    fun retryFailedStep(taskCardId: String, stepId: String) {
        val taskCard = accessor.snapshot.taskCards[taskCardId] ?: return
        accessor.coroutineScope.launch {
            val stepsToUpdate = if (stepId == "ALL_FAILED") {
                taskCard.steps.filter { it.status.isRetryable }
            } else {
                taskCard.steps.filter { it.id == stepId && it.status.isRetryable }
            }
            if (stepsToUpdate.isEmpty()) return@launch

            // 标记为 RUNNING
            accessor.update {
                it.copy(
                    taskCards = it.taskCards.mapValues { (k, v) ->
                        if (k == taskCardId) {
                            v.copy(
                                phase = TaskCardPhase.EXECUTING,
                                steps = v.steps.map { s ->
                                    if (stepsToUpdate.any { it.id == s.id }) s.copy(
                                        status = TaskStepStatus.RUNNING,
                                        startedAt = System.currentTimeMillis(),
                                        finishedAt = null,
                                        result = "",
                                    ) else s
                                },
                            )
                        } else v
                    },
                )
            }

            // 逐个重新执行 — 审计修复 (2.6): 优先用完整 rawArgs(原实现只有截断 200 字符的
            // detail,复杂工具参数被截断后重试必失败);rawArgs 为空时才回退 detail。
            stepsToUpdate.forEach { step ->
                val startedAt = System.currentTimeMillis()
                val retryArgs = step.rawArgs.ifBlank { step.detail }
                // Phase 3: 重试套 withTimeoutOrNull;resultOf 已保证不吞 CancellationException,
                // 这里再显式 catch 以区分"超时(null)"与"外部取消(抛出)"。
                val retryResult: String? = try {
                    withTimeoutOrNull(retryTimeoutMs) {
                        when (val r = resultOf {
                            routeGuard.executeFromJson(step.title, retryArgs)
                        }) {
                            is io.zer0.common.Result.Success -> r.data
                            is io.zer0.common.Result.Error -> "重试执行异常: ${r.message}"
                        }
                    }
                } catch (ce: CancellationException) {
                    // 取消必须向上传播(调用方负责收尾),但先把步骤写成终态再抛。
                    withContext(NonCancellable) {
                        writeRetriedStep(
                            taskCardId = taskCardId,
                            stepId = step.id,
                            status = TaskStepStatus.CANCELLED,
                            result = "[取消] 重试 ${step.title} 已取消",
                            startedAt = startedAt,
                            finishedAt = System.currentTimeMillis(),
                            autoExpand = false,
                        )
                        updateTaskCardPhase(taskCardId, TaskCardPhase.DONE)
                    }
                    throw ce
                }

                val timedOut = retryResult == null
                val toolResult = retryResult
                    ?: "[超时] 重试 ${step.title} 超过 ${retryTimeoutMs / 1000} 秒未响应,已终止"
                val isSuccess = !timedOut && isToolResultSuccess(toolResult)
                writeRetriedStep(
                    taskCardId = taskCardId,
                    stepId = step.id,
                    status = when {
                        timedOut -> TaskStepStatus.TIMED_OUT
                        isSuccess -> TaskStepStatus.SUCCESS
                        else -> TaskStepStatus.FAILED
                    },
                    result = toolResult,
                    startedAt = startedAt,
                    finishedAt = System.currentTimeMillis(),
                    autoExpand = !isSuccess,
                )
            }

            // 重试完毕,切回 DONE
            updateTaskCardPhase(taskCardId, TaskCardPhase.DONE)
        }
    }

    /**
     * Phase 3: 把重试终态按状态写回指定步骤(可选自动展开卡片展示失败详情)。
     */
    private fun writeRetriedStep(
        taskCardId: String,
        stepId: String,
        status: TaskStepStatus,
        result: String,
        startedAt: Long,
        finishedAt: Long,
        autoExpand: Boolean,
    ) {
        accessor.update {
            it.copy(
                taskCards = it.taskCards.mapValues { (k, v) ->
                    if (k == taskCardId) {
                        // v1.0.47 P8-3: 工具失败时自动展开 TaskCard,让用户立即看到错误详情
                        v.copy(
                            isExpanded = if (autoExpand) true else v.isExpanded,
                            steps = v.steps.map { s ->
                                if (s.id == stepId) s.copy(
                                    status = status,
                                    result = result,
                                    startedAt = startedAt,
                                    finishedAt = finishedAt,
                                ) else s
                            },
                        )
                    } else v
                },
            )
        }
    }

    /**
     * 判定工具执行结果是否成功(P2-22:委托共享判定器 [io.zer0.muse.tools.ToolResultJudge],
     * 与子代理/定时任务/测试共用同一实现,消除中文子串判定漂移)。
     */
    fun isToolResultSuccess(result: String): Boolean =
        io.zer0.muse.tools.ToolResultJudge.isSuccess(result)

    private companion object {
        /** Phase 3: 单步重试执行超时(2 分钟,与 ToolOrchestrator 的工具超时对齐)。 */
        const val DEFAULT_RETRY_TIMEOUT_MS = 120_000L
    }
}
