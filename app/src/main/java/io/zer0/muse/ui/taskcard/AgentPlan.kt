package io.zer0.muse.ui.taskcard

import kotlinx.serialization.Serializable

/**
 * v1.55: Agent 工作流结构化计划。
 *
 * 当 LLM 面对复杂任务时,可先调用 task_plan 工具创建一个分步计划,
 * 再逐步执行。计划在 UI 中以检查清单形式展示,用户可实时看到进度。
 */
@Serializable
data class AgentPlan(
    val id: String,
    val title: String,
    val steps: List<AgentPlanStep>,
    val createdAt: Long = System.currentTimeMillis(),
    /**
     * v1.137: 关联创建此计划的助手消息 ID。
     *
     * 用于将计划卡固定到创建它的消息上,随该消息一起滚动,
     * 而不是始终"跳"到最后一条助手消息(用户反馈"列表固定在底部不跟随滚动")。
     * null 表示未关联(向后兼容),UI 回退到旧逻辑(显示在最后一条助手消息上)。
     */
    val messageId: String? = null,
) {
    val totalSteps: Int get() = steps.size
    val completedSteps: Int get() = steps.count { it.status == AgentPlanStepStatus.DONE }
    val failedSteps: Int get() = steps.count { it.status == AgentPlanStepStatus.FAILED }
    val inProgressSteps: Int get() = steps.count { it.status == AgentPlanStepStatus.IN_PROGRESS }
    // L-TC1 修复: 拆分 isAllSettled(全部结束,含 FAILED/SKIPPED)与 isAllSucceeded(全部成功)。
    // 原先 isAllDone 把 FAILED/SKIPPED 也算"完成",语义不清,标题栏的完成图标会误导用户。
    /** 全部步骤已结束(不再有 PENDING / IN_PROGRESS),含成功/失败/跳过。 */
    val isAllSettled: Boolean get() = steps.isNotEmpty() && steps.all {
        it.status == AgentPlanStepStatus.DONE ||
            it.status == AgentPlanStepStatus.FAILED ||
            it.status == AgentPlanStepStatus.SKIPPED
    }
    /** 全部步骤成功完成(无 FAILED / SKIPPED)。 */
    val isAllSucceeded: Boolean get() = steps.isNotEmpty() && steps.all {
        it.status == AgentPlanStepStatus.DONE
    }
    // 保留 isAllDone 作为 isAllSettled 的别名,向后兼容现有调用方(标题栏判断"是否还在跑")
    val isAllDone: Boolean get() = isAllSettled
    val progress: Float get() = if (steps.isEmpty()) 0f else completedSteps.toFloat() / steps.size
}

@Serializable
data class AgentPlanStep(
    val id: String,
    val title: String,
    val description: String = "",
    val status: AgentPlanStepStatus = AgentPlanStepStatus.PENDING,
    val result: String = "",
    val startedAt: Long = 0,
    val finishedAt: Long = 0,
)

@Serializable
enum class AgentPlanStepStatus(val displayText: String) {
    PENDING("待执行"),
    IN_PROGRESS("执行中"),
    DONE("已完成"),
    FAILED("失败"),
    SKIPPED("已跳过"),
}
