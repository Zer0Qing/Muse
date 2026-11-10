package io.zer0.muse.automation.ui

import io.zer0.muse.automation.core.AutomationAction
import io.zer0.muse.automation.core.AutonomousAccessibilityGrant
import io.zer0.muse.automation.core.AutonomousAccessibilityWindow
import io.zer0.muse.data.AutomationSettingsStore

data class AutonomousAccessibilityDraftState(
    val enabled: Boolean = false,
    val startMinute: Int = 14 * 60,
    val endMinute: Int = 17 * 60,
    val allowedPackages: Set<String> = setOf(DEFAULT_PACKAGE),
    val allowedActions: Set<AutomationAction> = AutomationAction.entries.toSet(),
    val maxRunsPerDay: Int = 6,
) {
    fun toggleAction(action: AutomationAction): AutonomousAccessibilityDraftState {
        val next = if (action in allowedActions) allowedActions - action else allowedActions + action
        return copy(allowedActions = next)
    }

    fun toWindow(): AutonomousAccessibilityWindow = AutonomousAccessibilityWindow(
        enabled = enabled,
        startMinuteOfDay = startMinute.coerceIn(0, 24 * 60 - 1),
        endMinuteOfDay = endMinute.coerceIn(0, 24 * 60 - 1),
        allowedPackages = allowedPackages.filter(String::isNotBlank).toSet(),
        allowedActions = allowedActions,
        maxRunsPerDay = maxRunsPerDay.coerceIn(0, 100),
    )

    companion object {
        private const val DEFAULT_PACKAGE = "com.xingin.xhs"

        fun fromWindow(window: AutonomousAccessibilityWindow): AutonomousAccessibilityDraftState = AutonomousAccessibilityDraftState(
            enabled = window.enabled,
            startMinute = window.startMinuteOfDay,
            endMinute = window.endMinuteOfDay,
            allowedPackages = window.allowedPackages.ifEmpty { setOf(DEFAULT_PACKAGE) },
            allowedActions = window.allowedActions,
            maxRunsPerDay = window.maxRunsPerDay,
        )
    }
}

internal fun parseAutonomousPackages(raw: String): Set<String> = raw.lineSequence()
    .map(String::trim)
    .filter(String::isNotBlank)
    .toSet()

internal fun formatMinuteOfDay(minute: Int): String =
    "%02d:%02d".format(minute.coerceIn(0, 24 * 60 - 1) / 60, minute.coerceIn(0, 24 * 60 - 1) % 60)

/**
 * This confirmation is created from an existing user message, never from the
 * settings description itself. It binds the grant to that message fingerprint.
 */
internal fun buildAutonomousGrant(
    sessionId: String,
    latestUserMessage: String?,
    packageName: String,
    nowMillis: Long,
): AutonomousAccessibilityGrant? {
    if (latestUserMessage.isNullOrBlank()) return null
    if (!io.zer0.muse.automation.core.AutonomousAccessibilityTaskScope.confirms(latestUserMessage, packageName)) {
        return null
    }
    return AutonomousAccessibilityGrant(
        sessionId = sessionId,
        packageName = packageName,
        taskFingerprint =
        io.zer0.muse.automation.core.AutonomousAccessibilityTaskScope.fingerprint(latestUserMessage),
        confirmedAtMillis = nowMillis,
    )
}

internal class AutonomousAccessibilityConfigurationStore(
    private val store: AutomationSettingsStore,
) {
    suspend fun save(draft: AutonomousAccessibilityDraftState) {
        store.saveAutonomousAccessibilityWindow(draft.toWindow())
    }
}
