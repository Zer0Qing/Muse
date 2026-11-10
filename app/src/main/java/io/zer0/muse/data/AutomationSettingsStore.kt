package io.zer0.muse.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.muse.automation.core.AutonomousAccessibilityGrant
import io.zer0.muse.automation.core.AutonomousAccessibilityWindow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

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
                decode(
                    KEY_AUTONOMOUS_ACCESSIBILITY_WINDOW,
                    prefs[KEY_AUTONOMOUS_ACCESSIBILITY_WINDOW],
                    "本次用默认时段窗口（用户会看到设置没生效）",
                ) { raw -> AppJson.decodeFromString(AutonomousAccessibilityWindow.serializer(), raw) }
                    ?: AutonomousAccessibilityWindow()
            }
            .catch { error ->
                Logger.w(TAG, "${KEY_AUTONOMOUS_ACCESSIBILITY_WINDOW.name} 读取流失败，本次用默认时段窗口: ${error.message}")
                emit(AutonomousAccessibilityWindow())
            }
            .distinctUntilChanged()

    /**
     * 已授权的自主行动许可。
     *
     * 注意这里的降级方向：解不动就当作「没授权」，所以用户会看到
     * 之前允许的自主行动突然被拦下来重新询问 —— 这不是 bug（宁可不放行），
     * 但**必须留下痕迹**，否则排查时分不清是「用户从来没勾」还是「授权丢了」。
     */
    val autonomousAccessibilityGrantFlow: Flow<AutonomousAccessibilityGrant?> =
        store.data
            .map { prefs ->
                decode(
                    KEY_AUTONOMOUS_ACCESSIBILITY_GRANT,
                    prefs[KEY_AUTONOMOUS_ACCESSIBILITY_GRANT],
                    "本次按未授权处理（自主行动会被拦下并重新询问）",
                ) { raw -> AppJson.decodeFromString(AutonomousAccessibilityGrant.serializer(), raw) }
            }
            .catch { error ->
                Logger.w(TAG, "${KEY_AUTONOMOUS_ACCESSIBILITY_GRANT.name} 读取流失败，本次按未授权处理: ${error.message}")
                emit(null)
            }
            .distinctUntilChanged()

    val autonomousAccessibilityLedgerFlow: Flow<AutonomousAccessibilityLedger> =
        store.data
            .map { prefs ->
                decode(
                    KEY_AUTONOMOUS_ACCESSIBILITY_LEDGER,
                    prefs[KEY_AUTONOMOUS_ACCESSIBILITY_LEDGER],
                    "本次按今日 0 次处理（当日上限会重新起算）",
                ) { raw -> AppJson.decodeFromString(AutonomousAccessibilityLedger.serializer(), raw) }
                    ?: AutonomousAccessibilityLedger()
            }
            .catch { error ->
                Logger.w(TAG, "${KEY_AUTONOMOUS_ACCESSIBILITY_LEDGER.name} 读取流失败，本次按今日 0 次处理: ${error.message}")
                emit(AutonomousAccessibilityLedger())
            }
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
            val current = decode(
                KEY_AUTONOMOUS_ACCESSIBILITY_GRANT,
                prefs[KEY_AUTONOMOUS_ACCESSIBILITY_GRANT],
                "无法确认是否同一条授权，故本次不删除（下次保存会覆盖）",
            ) { raw -> AppJson.decodeFromString(AutonomousAccessibilityGrant.serializer(), raw) }
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
            val current = decode(
                KEY_AUTONOMOUS_ACCESSIBILITY_LEDGER,
                prefs[KEY_AUTONOMOUS_ACCESSIBILITY_LEDGER],
                "今日的计数将从 1 重新开始（当日上限因此可能多放行几次）",
            ) { raw -> AppJson.decodeFromString(AutonomousAccessibilityLedger.serializer(), raw) }
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

    /**
     * 解一处 JSON。**两种「没有值」必须分开**：
     *
     *  - `raw == null` 是用户**从来没设过** —— 正常状态，不打日志（否则每次读设置都刷一条）；
     *  - 有值但解不动 是「设过但丢了」—— 一定要留痕，因为这正是用户会说「我明明开过」的那种情况，
     *    而每种降级对用户意味着什么并不相同，所以由调用方传 [consequence] 说清楚。
     *
     * 以前这五处各写各的：只有窗口那条有日志，其余直接 `.getOrNull()` 丢掉原因。
     */
    private fun <T : Any> decode(key: Preferences.Key<String>, raw: String?, consequence: String, parser: (String) -> T): T? {
        if (raw == null) return null
        return runCatching { parser(raw) }
            .onFailure { error ->
                Logger.w(TAG, "${key.name} 存的是解不动的 JSON，$consequence | 解析: ${error.message}")
            }
            .getOrNull()
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
