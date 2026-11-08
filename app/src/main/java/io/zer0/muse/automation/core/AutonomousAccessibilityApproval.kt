package io.zer0.muse.automation.core

import io.zer0.muse.tools.ToolApprovalState

/**
 * Applies the autonomous accessibility allowlist on top of the normal approval result.
 *
 * This can only turn a Pending decision into Auto. Hard deny, parameter rejection,
 * explicit allow, and non-screen tools remain owned by the existing permission chain.
 */
object AutonomousAccessibilityApproval {
    data class Result(
        val approval: ToolApprovalState,
        val reason: String,
    )

    // 守卫链：每个提前返回都带一条**不同的**人类可读原因（策略/谓词的共同形状）。
    // 拧成单一返回会把原因塞进可空值再层层解包，读的人反而看不出谁在挡 ——
    // 这是规则的已知误报（阈值 4），逐点豁免并把理由留在这里。
    @Suppress("ReturnCount")
    fun evaluate(
        window: AutonomousAccessibilityWindow,
        toolName: String,
        currentPackage: String?,
        minuteOfDay: Int,
        runCountToday: Int,
        userConfirmed: Boolean,
        pending: ToolApprovalState,
    ): Result {
        if (pending !== ToolApprovalState.Pending) {
            return Result(pending, "")
        }
        val action = actionFor(toolName) ?: return Result(pending, "")
        // 输入不再硬挡：是否放行完全交给下方动作白名单（window.allowedActions）与时段/白名单/额度策略。
        if (currentPackage.isNullOrBlank()) {
            return Result(pending, "无法确认当前前台应用")
        }
        val decision =
            AutonomousAccessibilityPolicy.evaluate(
                window = window,
                request =
                AutonomousAccessibilityRequest(
                    minuteOfDay = minuteOfDay,
                    packageName = currentPackage,
                    action = action,
                    runCountToday = runCountToday,
                    userConfirmed = userConfirmed,
                ),
            )
        return Result(
            approval = if (decision.allowed) ToolApprovalState.Auto else pending,
            reason = decision.reason,
        )
    }

    fun actionFor(toolName: String): AutomationAction? = when (toolName) {
        "screen_read", "screen_current_app" -> AutomationAction.READ_SCREEN
        "screen_screenshot" -> AutomationAction.READ_SCREEN
        "screen_tap", "screen_tap_text" -> AutomationAction.CLICK
        "screen_swipe", "screen_swipe_path" -> AutomationAction.SCROLL
        "screen_input" -> AutomationAction.INPUT
        else -> null
    }
}
