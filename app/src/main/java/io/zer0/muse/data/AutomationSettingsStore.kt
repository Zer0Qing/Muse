package io.zer0.muse.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.muse.automation.core.AutonomousAccessibilityGrant
import io.zer0.muse.automation.core.AutonomousAccessibilityWindow
import kotlinx.serialization.Serializable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@Serializable
data class AutonomousAccessibilityLedger(
    val dateKey: String = "",
    val runCount: Int = 0,
)

/** Persistent local-only authorization for autonomous accessibility actions. */
class AutomationSettingsStore(
    private val context: Context,
) {
    private val store get() = context.museSettingsDataStore

    val autonomousAccessibilityWindowFlow: Flow<AutonomousAccessibilityWindow> =
        store.data
            .map { prefs ->
                val raw = prefs[KEY_AUTONOMOUS_ACCESSIBILITY_WINDOW] ?: return@map AutonomousAccessibilityWindow()
                runCatching {
                    AppJson.decodeFromString(AutonomousAccessibilityWindow.serializer(), raw)
                }.getOrElse { error ->
                    Logger.w(TAG, "autonomous accessibility window decode failed: ${error.message}")
                    AutonomousAccessibilityWindow()
                }
            }
            .catch { error ->
                Logger.w(TAG, "autonomous accessibility window flow failed: ${error.message}")
                emit(AutonomousAccessibilityWindow())
            }
            .distinctUntilChanged()

    val autonomousAccessibilityGrantFlow: Flow<AutonomousAccessibilityGrant?> =
        store.data
            .map { prefs ->
                prefs[KEY_AUTONOMOUS_ACCESSIBILITY_GRANT]?.let { raw ->
                    runCatching {
                        AppJson.decodeFromString(AutonomousAccessibilityGrant.serializer(), raw)
                    }.getOrNull()
                }
            }
            .catch { emit(null) }
            .distinctUntilChanged()

    val autonomousAccessibilityLedgerFlow: Flow<AutonomousAccessibilityLedger> =
        store.data
            .map { prefs ->
                prefs[KEY_AUTONOMOUS_ACCESSIBILITY_LEDGER]?.let { raw ->
                    runCatching {
                        AppJson.decodeFromString(AutonomousAccessibilityLedger.serializer(), raw)
                    }.getOrNull()
                } ?: AutonomousAccessibilityLedger()
            }
            .catch { emit(AutonomousAccessibilityLedger()) }
            .distinctUntilChanged()

    suspend fun saveAutonomousAccessibilityWindow(window: AutonomousAccessibilityWindow) {
        store.edit {
            it[KEY_AUTONOMOUS_ACCESSIBILITY_WINDOW] =
                AppJson.encodeToString(AutonomousAccessibilityWindow.serializer(), window)
        }
    }

    suspend fun saveAutonomousAccessibilityGrant(grant: AutonomousAccessibilityGrant) {
        store.edit {
            it[KEY_AUTONOMOUS_ACCESSIBILITY_GRANT] =
                AppJson.encodeToString(AutonomousAccessibilityGrant.serializer(), grant)
        }
    }

    /** Compare-and-clear avoids deleting a newer task's confirmation. */
    suspend fun clearAutonomousAccessibilityGrant(expected: AutonomousAccessibilityGrant) {
        store.edit { prefs ->
            val current =
                prefs[KEY_AUTONOMOUS_ACCESSIBILITY_GRANT]?.let { raw ->
                    runCatching {
                        AppJson.decodeFromString(AutonomousAccessibilityGrant.serializer(), raw)
                    }.getOrNull()
                }
            if (current == expected) prefs.remove(KEY_AUTONOMOUS_ACCESSIBILITY_GRANT)
        }
    }

    /**
     * Ledger `edit` serializes competing tool calls, so one invocation cannot
     * read the same old count and exceed the daily cap together.
     */
    suspend fun incrementAutonomousRunCount(dateKey: String): Int {
        var updated = 0
        store.edit { prefs ->
            val current =
                prefs[KEY_AUTONOMOUS_ACCESSIBILITY_LEDGER]?.let { raw ->
                    runCatching {
                        AppJson.decodeFromString(AutonomousAccessibilityLedger.serializer(), raw)
                    }.getOrNull()
                }
            val next =
                if (current?.dateKey == dateKey) {
                    AutonomousAccessibilityLedger(dateKey, current.runCount + 1)
                } else {
                    AutonomousAccessibilityLedger(dateKey, 1)
                }
            prefs[KEY_AUTONOMOUS_ACCESSIBILITY_LEDGER] =
                AppJson.encodeToString(AutonomousAccessibilityLedger.serializer(), next)
            updated = next.runCount
        }
        return updated
    }

    private companion object {
        const val TAG = "AutomationSettings"
        val KEY_AUTONOMOUS_ACCESSIBILITY_WINDOW =
            stringPreferencesKey("autonomous_accessibility_window")
        val KEY_AUTONOMOUS_ACCESSIBILITY_GRANT =
            stringPreferencesKey("autonomous_accessibility_grant")
        val KEY_AUTONOMOUS_ACCESSIBILITY_LEDGER =
            stringPreferencesKey("autonomous_accessibility_ledger")
    }
}
