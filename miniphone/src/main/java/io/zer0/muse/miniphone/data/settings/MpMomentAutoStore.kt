package io.zer0.muse.miniphone.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * 模块级后台设置:自动发朋友圈的开关与上一次生成时间,以及角色主动私信的开关与上次时间。
 *
 * 这些都放在模块自己的 DataStore 里:`mp_profile` 是本机用户资料的业务表,
 * 加调度字段会把「用户是谁」和「后台跑了没」混在一张表里,还得改 Room schema。
 * 同一个 DataStore 里加键就行 —— 多声明一个 `preferencesDataStore` 会在同名文件上开第二个实例而崩溃。
 *
 * @see miniPhoneMomentAutoStore 生产实现
 */
interface MpMomentAutoStore {
    /** 开关状态,默认开。 */
    val enabledFlow: Flow<Boolean>

    suspend fun isEnabled(): Boolean

    suspend fun setEnabled(enabled: Boolean)

    /** 上次成功生成的时间(毫秒);从没生成过返回 `null`。 */
    suspend fun lastGeneratedAt(): Long?

    /** 记下这次生成的时间,给下一轮的防抖用。 */
    suspend fun markGenerated(atMillis: Long)

    /** 「角色主动私信」开关,默认关:没人开就一直不打扰。 */
    val chatProactiveEnabledFlow: Flow<Boolean>

    suspend fun isChatProactiveEnabled(): Boolean

    suspend fun setChatProactiveEnabled(enabled: Boolean)

    /** 上次主动私信成功的时间(毫秒);从没发过返回 `null`。 */
    suspend fun lastProactiveChatAt(): Long?

    /** 记下这次主动私信的时间,给 4 小时的间隔判定用。只在真的落库之后写。 */
    suspend fun markProactiveChat(atMillis: Long)
}

/** 模块内 DataStore 文件名:只放手机相关的设置,不与宿主共用。 */
private const val STORE_NAME: String = "miniphone_settings"

private val Context.miniPhoneSettingsDataStore: DataStore<Preferences> by preferencesDataStore(name = STORE_NAME)

/** 取模块内设置,进程内同一份 DataStore(DataStore 不允许同文件开两个实例)。 */
fun miniPhoneMomentAutoStore(context: Context): MpMomentAutoStore =
    DataStoreMpMomentAutoStore(context.applicationContext.miniPhoneSettingsDataStore)

/**
 * DataStore 实现。
 *
 * 读失败(磁盘坏、文件被占)当「用默认值」处理:自动发朋友圈的开关退回默认的开、
 * 角色主动私信退回默认的关,上次时间当成没有,后台任务不该因为一次读失败就永久停摆。
 */
internal class DataStoreMpMomentAutoStore(
    private val dataStore: DataStore<Preferences>,
) : MpMomentAutoStore {

    private val preferences: Flow<Preferences> = dataStore.data.catch { throwable ->
        if (throwable is IOException) emit(emptyPreferences()) else throw throwable
    }

    override val enabledFlow: Flow<Boolean> = preferences.map { prefs ->
        prefs[KEY_AUTO_ENABLED] ?: DEFAULT_AUTO_ENABLED
    }

    override suspend fun isEnabled(): Boolean = enabledFlow.first()

    override suspend fun setEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_AUTO_ENABLED] = enabled }
    }

    override suspend fun lastGeneratedAt(): Long? = preferences.first()[KEY_LAST_GENERATED_AT]?.takeIf { it > 0L }

    override suspend fun markGenerated(atMillis: Long) {
        dataStore.edit { prefs -> prefs[KEY_LAST_GENERATED_AT] = atMillis }
    }

    override val chatProactiveEnabledFlow: Flow<Boolean> = preferences.map { prefs ->
        prefs[KEY_CHAT_PROACTIVE_ENABLED] ?: DEFAULT_CHAT_PROACTIVE_ENABLED
    }

    override suspend fun isChatProactiveEnabled(): Boolean = chatProactiveEnabledFlow.first()

    override suspend fun setChatProactiveEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_CHAT_PROACTIVE_ENABLED] = enabled }
    }

    override suspend fun lastProactiveChatAt(): Long? = preferences.first()[KEY_LAST_PROACTIVE_CHAT_AT]?.takeIf { it > 0L }

    override suspend fun markProactiveChat(atMillis: Long) {
        dataStore.edit { prefs -> prefs[KEY_LAST_PROACTIVE_CHAT_AT] = atMillis }
    }

    companion object {
        private const val DEFAULT_AUTO_ENABLED: Boolean = true

        /** 主动私信默认关:它比朋友圈更打扰,必须由用户自己打开。 */
        private const val DEFAULT_CHAT_PROACTIVE_ENABLED: Boolean = false

        private val KEY_AUTO_ENABLED = booleanPreferencesKey("auto_moment_enabled")

        private val KEY_LAST_GENERATED_AT = longPreferencesKey("last_auto_moment_at")

        private val KEY_CHAT_PROACTIVE_ENABLED = booleanPreferencesKey("chat_proactive_enabled")

        private val KEY_LAST_PROACTIVE_CHAT_AT = longPreferencesKey("last_proactive_chat_at")
    }
}
