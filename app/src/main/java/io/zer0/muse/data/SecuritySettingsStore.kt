package io.zer0.muse.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map


/**
 * P2-2 拆分：安全/行为开关子仓库。
 *
 * 与 SettingsRepository 共用 `muse_settings` DataStore，承载：
 * - 通知策略 / ANR 检测 / 保持唤醒 / 开机自启
 */
class SecuritySettingsStore(
    private val context: Context,
) {

    private val store get() = context.museSettingsDataStore

    val notificationPolicyFlow: Flow<String> = store.data.map { prefs ->
        prefs[KEY_NOTIFICATION_POLICY] ?: "when_unfocused"
    }

    val anrDetectionFlow: Flow<Boolean> = store.data.map { prefs ->
        prefs[KEY_ANR_DETECTION] ?: true
    }

    val keepAwakeFlow: Flow<Boolean> = store.data.map { prefs ->
        prefs[KEY_KEEP_AWAKE] ?: false
    }

    val autoLaunchFlow: Flow<Boolean> = store.data.map { prefs ->
        prefs[KEY_AUTO_LAUNCH] ?: false
    }

    suspend fun saveNotificationPolicy(policy: String) {
        store.edit { it[KEY_NOTIFICATION_POLICY] = policy }
    }

    suspend fun saveAnrDetection(enabled: Boolean) {
        store.edit { it[KEY_ANR_DETECTION] = enabled }
    }

    suspend fun saveKeepAwake(enabled: Boolean) {
        store.edit { it[KEY_KEEP_AWAKE] = enabled }
    }

    suspend fun saveAutoLaunch(enabled: Boolean) {
        store.edit { it[KEY_AUTO_LAUNCH] = enabled }
    }

    private companion object {
        private val KEY_NOTIFICATION_POLICY = stringPreferencesKey("notification_policy")
        private val KEY_ANR_DETECTION = booleanPreferencesKey("anr_detection_enabled")
        private val KEY_KEEP_AWAKE = booleanPreferencesKey("keep_awake")
        private val KEY_AUTO_LAUNCH = booleanPreferencesKey("auto_launch")
    }
}
