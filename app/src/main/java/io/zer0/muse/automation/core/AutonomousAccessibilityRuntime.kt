package io.zer0.muse.automation.core

import io.zer0.muse.data.AutomationSettingsStore
import io.zer0.muse.data.audit.AuditLogger
import kotlinx.coroutines.flow.first
import java.time.Clock
import java.time.ZoneId

/**
 * Read side used by the tool approval gate. It never decides approval itself;
 * it only supplies the current plan, task grant, clock, foreground package, and
 * today's successful autonomous invocation count.
 */
interface AutonomousAccessibilityRuntime {
    suspend fun snapshot(sessionId: String): AutonomousAccessibilityRuntimeSnapshot
}

data class AutonomousAccessibilityRuntimeSnapshot(
    val window: AutonomousAccessibilityWindow = AutonomousAccessibilityWindow(),
    val grant: AutonomousAccessibilityGrant? = null,
    val currentPackage: String? = null,
    val minuteOfDay: Int = 0,
    val dateKey: String = "",
    val runCountToday: Int = 0,
    val latestUserMessage: String? = null,
)

interface AutonomousAccessibilityRunRecorder {
    suspend fun recordSuccessfulRun(sessionId: String, packageName: String): Boolean
}

class PersistentAutonomousAccessibilityRuntime(
    private val store: AutomationSettingsStore,
    private val foregroundPackage: suspend () -> String?,
    private val latestUserMessage: suspend (String) -> String?,
    private val auditLogger: AuditLogger? = null,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) : AutonomousAccessibilityRuntime, AutonomousAccessibilityRunRecorder {
    override suspend fun snapshot(sessionId: String): AutonomousAccessibilityRuntimeSnapshot {
        val now = clock.millis()
        val dateKey = AutonomousAccessibilityState.dateKey(now, zoneId)
        val window = store.autonomousAccessibilityWindowFlow.first()
        val grant = store.autonomousAccessibilityGrantFlow.first()
        val ledger = store.autonomousAccessibilityLedgerFlow.first()
        return AutonomousAccessibilityRuntimeSnapshot(
            window = window,
            grant = grant,
            currentPackage = runCatching { foregroundPackage() }.getOrNull(),
            minuteOfDay = AutonomousAccessibilityState.minuteOfDay(now, zoneId),
            dateKey = dateKey,
            runCountToday = ledger.runCount.takeIf { ledger.dateKey == dateKey } ?: 0,
            latestUserMessage = runCatching { latestUserMessage(sessionId) }.getOrNull(),
        )
    }

    override suspend fun recordSuccessfulRun(sessionId: String, packageName: String): Boolean {
        val now = clock.millis()
        val dateKey = AutonomousAccessibilityState.dateKey(now, zoneId)
        val snapshot = snapshot(sessionId)
        val grant = snapshot.grant ?: return false
        if (
            grant.sessionId != sessionId ||
            grant.packageName != packageName ||
            snapshot.latestUserMessage.isNullOrBlank() ||
            AutonomousAccessibilityTaskScope.fingerprint(snapshot.latestUserMessage) != grant.taskFingerprint ||
            !AutonomousAccessibilityTaskScope.confirms(snapshot.latestUserMessage, packageName)
        ) {
            return false
        }
        store.incrementAutonomousRunCount(dateKey)
        auditLogger?.log(
            category = "system",
            action = "autonomous_accessibility_run",
            target = sessionId,
            detail =
                mapOf(
                    "package" to packageName,
                    "task" to grant.taskFingerprint,
                    "date" to dateKey,
                    "runs" to (snapshot.runCountToday + 1),
                    "cap" to snapshot.window.maxRunsPerDay,
                ),
        )
        if (snapshot.runCountToday + 1 >= snapshot.window.maxRunsPerDay.coerceAtLeast(0)) {
            store.clearAutonomousAccessibilityGrant(expected = grant)
        }
        return true
    }

    /**
     * Removes a task grant when its source message changes. The new message may
     * replace the old one with a different task without a new send event.
     */
    suspend fun pruneGrantIfTaskChanged(sessionId: String): Boolean {
        val grant = store.autonomousAccessibilityGrantFlow.first() ?: return false
        val latest = runCatching { latestUserMessage(sessionId) }.getOrNull()
        val fingerprint = latest?.let(AutonomousAccessibilityTaskScope::fingerprint)
        if (
            grant.sessionId == sessionId &&
            grant.taskFingerprint == fingerprint &&
            AutonomousAccessibilityTaskScope.confirms(latest, grant.packageName)
        ) {
            return false
        }
        store.clearAutonomousAccessibilityGrant(expected = grant)
        return true
    }
}

/** Helpers used by the settings UI to keep editable grant fields consistent. */
object AutonomousAccessibilityDraft {
    fun packageName(lastAttempt: String?, current: Set<String>): String =
        lastAttempt?.trim()?.takeIf(String::isNotBlank)
            ?: current.firstOrNull()
            ?: DEFAULT_PACKAGE

    const val DEFAULT_PACKAGE = "com.xingin.xhs"
}
