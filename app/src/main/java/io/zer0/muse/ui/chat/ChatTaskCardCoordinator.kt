package io.zer0.muse.ui.chat

import io.zer0.common.resultOf
import io.zer0.muse.tools.ToolRegistry
import io.zer0.muse.ui.taskcard.TaskCardPhase
import io.zer0.muse.ui.taskcard.TaskStep
import io.zer0.muse.ui.taskcard.TaskStepStatus
import kotlinx.coroutines.launch

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
) {

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
     * 重试任务卡中失败的步骤。
     * - stepId = "ALL_FAILED":重试全部失败步骤
     * - stepId = 具体 step id:重试单个失败步骤
     *
     * 注意:重试仅更新 UI 状态(FAILED → RUNNING → SUCCESS/FAILED),
     * 不重新请求 LLM(工具参数已在步骤中保留)。
     * 若需要让 LLM 基于新结果继续,用户应手动重生成。
     */
    fun retryFailedStep(taskCardId: String, stepId: String) {
        val taskCard = accessor.snapshot.taskCards[taskCardId] ?: return
        accessor.coroutineScope.launch {
            val stepsToUpdate = if (stepId == "ALL_FAILED") {
                taskCard.steps.filter { it.status == TaskStepStatus.FAILED }
            } else {
                taskCard.steps.filter { it.id == stepId && it.status == TaskStepStatus.FAILED }
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
                val toolResult = when (val r = resultOf {
                    toolRegistry.executeFromJson(step.title, retryArgs)
                }) {
                    is io.zer0.common.Result.Success -> r.data
                    is io.zer0.common.Result.Error -> "重试执行异常: ${r.message}"
                }
                val isSuccess = isToolResultSuccess(toolResult)
                val finishedAt = System.currentTimeMillis()
                accessor.update {
                    it.copy(
                        taskCards = it.taskCards.mapValues { (k, v) ->
                            if (k == taskCardId) {
                                // v1.0.47 P8-3: 工具失败时自动展开 TaskCard,让用户立即看到错误详情
                                v.copy(
                                    isExpanded = if (!isSuccess) true else v.isExpanded,
                                    steps = v.steps.map { s ->
                                        if (s.id == step.id) s.copy(
                                            status = if (isSuccess) TaskStepStatus.SUCCESS
                                            else TaskStepStatus.FAILED,
                                            result = toolResult,
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

            // 重试完毕,切回 DONE
            updateTaskCardPhase(taskCardId, TaskCardPhase.DONE)
        }
    }

    /**
     * 判定工具执行结果是否成功(基于错误前缀列表)。
     *
     * 与 ToolRegistry 内的错误返回格式约定一致:工具失败时返回以中文错误描述开头的字符串。
     */
    fun isToolResultSuccess(result: String): Boolean {
        val errorPrefixes = listOf(
            "工具不存在", "工具执行异常", "参数解析失败", "skill 执行异常",
            "路径越权", "缺少参数", "文件过大", "文件不存在", "未知 skill",
            "URL 必须", "无法", "未找到 id 为", "子助手",
            // v1.0.72: 补全失败判定 — 此前"图片生成失败""错误:..."等真实失败
            // 被误判为成功,模型以为工具成功继续回复(前台却无结果)
            "错误", "失败", "异常", "超时", "取消", "未配置", "为空", "不可用",
        )
        return errorPrefixes.none { result.startsWith(it) }
    }
}
