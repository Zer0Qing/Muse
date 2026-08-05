package io.zer0.muse.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.zer0.common.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch


/**
 * P2-2 拆分：应用级设置子仓库。
 *
 * 与 SettingsRepository / AppearanceSettingsStore 共用 `muse_settings` DataStore，
 * 目前承载界面语言与 SharedPreferences 快速缓存；后续语言、主题种子、桌面快捷键等
 * 应用级字段继续收敛到这里。
 */
class AppSettingsStore(private val context: Context) {

    private val store get() = context.museSettingsDataStore

    /** v1.131: 语言快速同步缓存，供主线程 attachBaseContext 同步读取。 */
    private val languageSyncCache = context.getSharedPreferences("muse_language_cache", Context.MODE_PRIVATE)
    private val cacheScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val languageFlow: Flow<String> = store.data.map { prefs -> prefs[KEY_LANGUAGE] ?: "system" }

    init {
        // 历史用户升级到 v1.131+ 时 SP 为空，需从 DataStore 拷贝一次；异步执行不阻塞启动。
        cacheScope.launch {
            try {
                val dsLang = store.data.map { prefs -> prefs[KEY_LANGUAGE] ?: "system" }.first()
                if (languageSyncCache.getString(KEY_LANGUAGE_SP, null) == null) {
                    languageSyncCache.edit().putString(KEY_LANGUAGE_SP, dsLang).apply()
                }
            } catch (e: Exception) {
                Logger.w("AppSettingsStore", "language SP cache migration failed: ${e.message}", e)
            }
        }
    }

    suspend fun saveLanguage(lang: String) {
        store.edit { it[KEY_LANGUAGE] = lang }
        languageSyncCache.edit().putString(KEY_LANGUAGE_SP, lang).apply()
    }

    /** 同步读取语言设置，避免冷启动时主线程阻塞读 DataStore。 */
    fun getLanguageSync(): String = languageSyncCache.getString(KEY_LANGUAGE_SP, "system") ?: "system"

    private companion object {
        private val KEY_LANGUAGE = stringPreferencesKey("language")
        private const val KEY_LANGUAGE_SP = "language"
    }
}
