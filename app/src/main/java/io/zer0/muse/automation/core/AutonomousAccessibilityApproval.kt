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
        if (action == AutomationAction.INPUT) {
            return Result(pending, "输入动作不会自主执行")
        }
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
