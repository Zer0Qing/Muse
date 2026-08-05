package io.zer0.muse.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.zer0.common.AppJson
import io.zer0.muse.data.ChatPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer

/**
 * P2-2 拆分：聊天行为设置子仓库。
 *
 * 承载 Token 估算、粘贴转文件、楼层上下文限制、富文本输入、聊天偏好 JSON。
 */
class ChatSettingsStore(private val context: Context) {

    private val store get() = context.museSettingsDataStore

    val tokenEstimateEnabledFlow: Flow<Boolean> = store.data.map { prefs ->
        prefs[KEY_TOKEN_ESTIMATE_ENABLED] ?: false
    }
    val pasteAsFileEnabledFlow: Flow<Boolean> = store.data.map { prefs ->
        prefs[KEY_PASTE_AS_FILE_ENABLED] ?: true
    }
    val pasteAsFileThresholdFlow: Flow<Int> = store.data.map { prefs ->
        prefs[KEY_PASTE_AS_FILE_THRESHOLD] ?: 2000
    }
    val floorLimiterEnabledFlow: Flow<Boolean> = store.data.map { prefs ->
        prefs[KEY_FLOOR_LIMITER_ENABLED] ?: false
    }
    val floorLimitFlow: Flow<Int> = store.data.map { prefs ->
        prefs[KEY_FLOOR_LIMIT] ?: 16
    }
    val richInputEnabledFlow: Flow<Boolean> = store.data.map { prefs ->
        prefs[KEY_RICH_INPUT_ENABLED] ?: false
    }
    val chatPreferencesFlow: Flow<ChatPreferences> = store.data.map { prefs ->
        decodeChatPreferences(prefs[KEY_CHAT_PREFERENCES])
    }

    suspend fun getChatPreferences(): ChatPreferences = chatPreferencesFlow.first()

    suspend fun saveChatPreferences(prefs: ChatPreferences) {
        store.edit { it[KEY_CHAT_PREFERENCES] = AppJson.encodeToString(ChatPreferences.serializer(), prefs) }
    }

    suspend fun saveRichInputEnabled(enabled: Boolean) {
        store.edit { it[KEY_RICH_INPUT_ENABLED] = enabled }
    }

    suspend fun saveTokenEstimateEnabled(enabled: Boolean) {
        store.edit { it[KEY_TOKEN_ESTIMATE_ENABLED] = enabled }
    }

    suspend fun savePasteAsFileEnabled(enabled: Boolean) {
        store.edit { it[KEY_PASTE_AS_FILE_ENABLED] = enabled }
    }

    suspend fun savePasteAsFileThreshold(threshold: Int) {
        store.edit { it[KEY_PASTE_AS_FILE_THRESHOLD] = threshold }
    }

    suspend fun saveFloorLimiterEnabled(enabled: Boolean) {
        store.edit { it[KEY_FLOOR_LIMITER_ENABLED] = enabled }
    }

    suspend fun saveFloorLimit(limit: Int) {
        store.edit { it[KEY_FLOOR_LIMIT] = limit }
    }

    private fun decodeChatPreferences(value: String?): ChatPreferences {
        if (value.isNullOrBlank()) return ChatPreferences()
        return runCatching {
            AppJson.decodeFromString(ChatPreferences.serializer(), value)
        }.getOrElse { ChatPreferences() }
    }

    private companion object {
        private val KEY_TOKEN_ESTIMATE_ENABLED = booleanPreferencesKey("token_estimate_enabled")
        private val KEY_PASTE_AS_FILE_ENABLED = booleanPreferencesKey("paste_as_file_enabled")
        private val KEY_PASTE_AS_FILE_THRESHOLD = intPreferencesKey("paste_as_file_threshold")
        private val KEY_FLOOR_LIMITER_ENABLED = booleanPreferencesKey("floor_limiter_enabled")
        private val KEY_FLOOR_LIMIT = intPreferencesKey("floor_limit")
        private val KEY_RICH_INPUT_ENABLED = booleanPreferencesKey("rich_input_enabled")
        private val KEY_CHAT_PREFERENCES = stringPreferencesKey("chat_preferences_json")
    }
}
