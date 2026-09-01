package io.zer0.muse.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.zer0.common.Logger
import io.zer0.muse.data.audit.AuditLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map


/**
 * P2-2 拆分：安全/锁屏/行为开关子仓库。
 *
 * 与 SettingsRepository 共用 `muse_settings` DataStore，承载：
 * - 通知策略 / ANR 检测 / 保持唤醒 / 开机自启
 * - 生物识别 / PIN 锁 / PIN 暴力破解防护
 */
class SecuritySettingsStore(
    private val context: Context,
    private val auditLogger: AuditLogger,
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

    val biometricEnabledFlow: Flow<Boolean> = store.data.map { prefs ->
        prefs[KEY_BIOMETRIC_ENABLED] ?: false
    }

    val appPinFlow: Flow<String> = store.data.map { prefs ->
        try {
            prefs[KEY_APP_PIN]?.let { SecureKeyStore.decrypt(it) } ?: ""
        } catch (e: Exception) {
            Logger.w("SecuritySettingsStore", "appPinFlow 解密失败,PIN 已重置", e)
            ""
        }
    }

    val pinFailCountFlow: Flow<Int> = store.data.map { it[KEY_PIN_FAIL_COUNT] ?: 0 }
    val pinLockUntilFlow: Flow<Long> = store.data.map { it[KEY_PIN_LOCK_UNTIL] ?: 0L }

    suspend fun savePinFailState(failCount: Int, lockUntil: Long) {
        store.edit {
            it[KEY_PIN_FAIL_COUNT] = failCount
            it[KEY_PIN_LOCK_UNTIL] = lockUntil
        }
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

    suspend fun saveBiometricEnabled(enabled: Boolean) {
        store.edit { it[KEY_BIOMETRIC_ENABLED] = enabled }
    }

    /** PIN 是敏感凭据，写入前加密；空 PIN 原样保留。 */
    suspend fun saveAppPin(pin: String) {
        store.edit { it[KEY_APP_PIN] = SecureKeyStore.encrypt(pin) }
        auditLogger.log(
            category = "user_action",
            action = "save_app_pin",
            detail = mapOf("changed" to true),
        )
    }

    private companion object {
        private val KEY_NOTIFICATION_POLICY = stringPreferencesKey("notification_policy")
        private val KEY_ANR_DETECTION = booleanPreferencesKey("anr_detection_enabled")
        private val KEY_KEEP_AWAKE = booleanPreferencesKey("keep_awake")
        private val KEY_AUTO_LAUNCH = booleanPreferencesKey("auto_launch")
        private val KEY_APP_PIN = stringPreferencesKey("app_pin")
        private val KEY_BIOMETRIC_ENABLED = booleanPreferencesKey("biometric_enabled")
        private val KEY_PIN_FAIL_COUNT = intPreferencesKey("pin_fail_count")
        private val KEY_PIN_LOCK_UNTIL = longPreferencesKey("pin_lock_until")
    }
}
