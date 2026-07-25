package io.zer0.muse.data.account

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.zer0.common.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import java.util.UUID

private val Context.accountDataStore: DataStore<Preferences> by preferencesDataStore(name = "muse_account")

/**
 * Phase 4 4A: 账户系统管理器 — 匿名 ID + 可选邮箱绑定。
 *
 * 设计:
 * - 首次启动时生成 deviceId (UUID v4),作为本地唯一标识
 * - 可选绑定邮箱(6位验证码流程,由 UI 层处理)
 * - 数据存储在 DataStore,跨进程安全
 */
class AccountManager(
    private val context: Context,
) {
    private val store get() = context.accountDataStore

    companion object {
        private const val TAG = "AccountManager"
        private val KEY_DEVICE_ID = stringPreferencesKey("device_id")
        private val KEY_USER_ID = stringPreferencesKey("user_id")
        private val KEY_EMAIL = stringPreferencesKey("email")
        private val KEY_EMAIL_VERIFIED = booleanPreferencesKey("email_verified")
        private val KEY_SYNC_ENABLED = booleanPreferencesKey("sync_enabled")
        private val KEY_CREATED_AT = longPreferencesKey("created_at")
    }

    /** 账户状态 Flow。 */
    val accountStateFlow: Flow<AccountState> = store.data.map { prefs ->
        AccountState(
            deviceId = prefs[KEY_DEVICE_ID] ?: "",
            userId = prefs[KEY_USER_ID],
            email = prefs[KEY_EMAIL],
            emailVerified = prefs[KEY_EMAIL_VERIFIED] ?: false,
            syncEnabled = prefs[KEY_SYNC_ENABLED] ?: false,
            createdAt = prefs[KEY_CREATED_AT] ?: 0L,
        )
    }

    /**
     * 获取或生成 deviceId。首次调用时生成 UUID v4 并持久化。
     */
    suspend fun getOrCreateDeviceId(): String {
        val existing = store.data.first()[KEY_DEVICE_ID]
        if (!existing.isNullOrBlank()) return existing
        val newId = UUID.randomUUID().toString()
        store.edit { prefs ->
            prefs[KEY_DEVICE_ID] = newId
            prefs[KEY_CREATED_AT] = System.currentTimeMillis()
        }
        Logger.i(TAG, "Generated new deviceId: ${newId.take(8)}...")
        return newId
    }

    /** 绑定邮箱(由 UI 验证码流程确认后调用)。 */
    suspend fun bindEmail(email: String) {
        store.edit { prefs ->
            prefs[KEY_EMAIL] = email
            prefs[KEY_EMAIL_VERIFIED] = true
            prefs[KEY_USER_ID] = generateUserId()
        }
        Logger.i(TAG, "Email bound: ${email.take(3)}***")
    }

    /** 解绑邮箱。 */
    suspend fun unbindEmail() {
        store.edit { prefs ->
            prefs.remove(KEY_EMAIL)
            prefs[KEY_EMAIL_VERIFIED] = false
        }
    }

    /** 开关云同步。 */
    suspend fun setSyncEnabled(enabled: Boolean) {
        store.edit { prefs -> prefs[KEY_SYNC_ENABLED] = enabled }
    }

    /** 获取当前账户状态(挂起)。 */
    suspend fun getAccountState(): AccountState = accountStateFlow.first()

    private fun generateUserId(): String = "user-${UUID.randomUUID().toString().take(12)}"
}

/**
 * Phase 4 4A: 账户状态。
 */
@Serializable
data class AccountState(
    /** 设备唯一 ID (UUID v4, 首次启动时生成)。 */
    val deviceId: String = "",
    /** 用户 ID (绑定邮箱后生成)。 */
    val userId: String? = null,
    /** 绑定邮箱。 */
    val email: String? = null,
    /** 邮箱是否已验证。 */
    val emailVerified: Boolean = false,
    /** 是否开启云同步。 */
    val syncEnabled: Boolean = false,
    /** 账户创建时间。 */
    val createdAt: Long = 0L,
)
