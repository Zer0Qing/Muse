package io.zer0.muse.hook

import io.zer0.muse.notification.MuseNotificationManager

/**
 * 工具阶段的系统通知投影（对比清单 P2-03）。
 *
 * 问题：工具执行阶段只体现在会话里的 `toolProgressMessage`，用户切走应用就看不见了 ——
 * 长任务（构建、批量文件处理、虚拟屏操作）在后台跑时，通知栏什么都没有。
 *
 * 做法：复用既有的 [ToolLifecycleHook] 派发点，把"正在执行哪个工具"投到通知栏，
 * 结果回来即清除。**不新增构造参数、不改编排器** —— 那条链已经有现成的钩子，
 * 再插一个参数只会让它继续膨胀（也确实撞了 `LongParameterList`）。
 *
 * 已知边界：并行工具调用时，第一个结果回来就会清掉通知，
 * 而其它工具可能还在跑。v1 接受这个偏差 —— 宁可少显示，也不要在任务结束后留一条假"正在执行"。
 */
class TaskStageNotificationHook(
    private val notificationManager: MuseNotificationManager,
) : ToolLifecycleHook {

    override val id: String = "task_stage_notification"

    /** 放在最后执行：前面的 hook 可能拦截调用，被拦下的不该出现在通知里。 */
    override val priority: Int = 1

    override suspend fun onToolCallRequested(toolName: String, params: Map<String, Any>): ToolCallAction {
        notificationManager.updateTaskStage("正在执行：$toolName")
        return ToolCallAction.Allow
    }

    /**
     * 审批挂起时改口说「等你批准」。
     *
     * 上面那条 `onToolCallRequested` 在审批**之前**就触发了，所以整个等待期间通知栏挂着
     * 「正在执行：X」—— 用户切出应用看到的是错话：卡住的不是机器，是他自己；
     * 30 秒后这条调用还会静默变成拒绝，而他一直被指着"正在执行"。
     */
    override suspend fun onToolApprovalRequested(toolName: String) {
        notificationManager.updateTaskStage("等你批准：$toolName")
    }

    /**
     * 批准了才回到「正在执行」；没批准（含超时）就撤掉通知。
     * 那件事已经不会再发生了，留一条"正在执行"比不通知更坏。
     */
    override suspend fun onToolPermissionChecked(toolName: String, approved: Boolean) {
        notificationManager.updateTaskStage(if (approved) "正在执行：$toolName" else null)
    }

    override suspend fun onToolExecutionResult(result: ToolExecutionResult) {
        notificationManager.updateTaskStage(null)
    }
}
