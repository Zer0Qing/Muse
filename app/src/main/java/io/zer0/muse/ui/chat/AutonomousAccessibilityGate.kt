package io.zer0.muse.ui.chat

import io.zer0.muse.automation.core.AutonomousAccessibilityApproval
import io.zer0.muse.automation.core.AutonomousAccessibilityRuntimeSnapshot
import io.zer0.muse.automation.core.AutonomousAccessibilityTaskScope
import io.zer0.muse.automation.core.AutomationAction
import io.zer0.muse.tools.ToolApprovalState
import kotlinx.coroutines.sync.withLock

/**
 * Last gate before a pending accessibility tool becomes automatic.
 *
 * Every fact is re-read from the persisted task grant and current runtime. A
 * settings-page time window is a permission boundary, never a standalone grant.
 */
object AutonomousAccessibilityGate {
    private val mutex = kotlinx.coroutines.sync.Mutex()
    private var reservedDate = ""
    private var reservedRuns = 0

    /**
     * Reserve one run before allowing the gesture. The success callback only
     * commits that reservation; it never increments blindly a second time.
     */
    suspend fun reserve(
        sessionId: String,
        toolName: String,
        pending: ToolApprovalState,
        snapshot: AutonomousAccessibilityRuntimeSnapshot,
    ): AutonomousAccessibilityApproval.Result {
        val decision = resolve(sessionId, toolName, pending, snapshot)
        if (decision.approval !== ToolApprovalState.Auto) return decision
        return mutex.withLock {
            if (reservedDate != snapshot.dateKey) {
                reservedDate = snapshot.dateKey
                reservedRuns = 0
            }
            val next = maxOf(snapshot.runCountToday, reservedRuns)
            if (next >= snapshot.window.maxRunsPerDay) {
                AutonomousAccessibilityApproval.Result(pending, "autonomous_daily_limit_reached")
            } else {
                reservedRuns = next + 1
                decision
            }
        }
    }

    fun resolve(
        sessionId: String,
        toolName: String,
        pending: ToolApprovalState,
        snapshot: AutonomousAccessibilityRuntimeSnapshot,
    ): AutonomousAccessibilityApproval.Result {
        if (pending !== ToolApprovalState.Pending) {
            return AutonomousAccessibilityApproval.Result(pending, "")
        }
        val grant = snapshot.grant
            ?: return AutonomousAccessibilityApproval.Result(pending, "autonomous_grant_missing")
        if (grant.sessionId != sessionId) {
            return AutonomousAccessibilityApproval.Result(pending, "autonomous_grant_session_mismatch")
        }
        val currentPackage = snapshot.currentPackage ?: return AutonomousAccessibilityApproval.Result(
            pending,
            "autonomous_foreground_unknown",
        )
        if (grant.packageName != currentPackage) {
            return AutonomousAccessibilityApproval.Result(pending, "autonomous_grant_package_mismatch")
        }
        val latest = snapshot.latestUserMessage
        if (
            latest.isNullOrBlank() ||
            AutonomousAccessibilityTaskScope.fingerprint(latest) != grant.taskFingerprint ||
            !AutonomousAccessibilityTaskScope.confirms(latest, currentPackage)
        ) {
            return AutonomousAccessibilityApproval.Result(pending, "autonomous_task_mismatch")
        }
        val action = AutonomousAccessibilityApproval.actionFor(toolName)
        if (action == AutomationAction.INPUT) {
            return AutonomousAccessibilityApproval.Result(pending, "autonomous_input_blocked")
        }
        return AutonomousAccessibilityApproval.evaluate(
            window = snapshot.window,
            toolName = toolName,
            currentPackage = currentPackage,
            minuteOfDay = snapshot.minuteOfDay,
            runCountToday = snapshot.runCountToday,
            userConfirmed = true,
            pending = pending,
        )
    }
}
