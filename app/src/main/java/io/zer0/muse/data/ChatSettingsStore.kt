package io.zer0.muse.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.zer0.common.AppJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

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
    val chatPreferencesFlow: Flow<ChatPreferences> = store.data.map { prefs ->
        decodeChatPreferences(prefs[KEY_CHAT_PREFERENCES])
    }
    /** C3: 最近浏览会话 id 列表(最近优先,去重置顶,最多 [RECENT_SESSIONS_CAP] 条)。 */
    val recentSessionsFlow: Flow<List<String>> = store.data.map { prefs ->
        decodeRecentSessions(prefs[KEY_RECENT_SESSIONS])
    }

    suspend fun getChatPreferences(): ChatPreferences = chatPreferencesFlow.first()

    suspend fun saveChatPreferences(prefs: ChatPreferences) {
        store.edit { it[KEY_CHAT_PREFERENCES] = AppJson.encodeToString(ChatPreferences.serializer(), prefs) }
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

    /**
     * C3: 记录一次会话浏览 — 去重置顶(同 id 移到最前),超容量裁剪尾部。
     * 调用点: ChatViewModel.switchSession(会话切换统一漏斗)。
     */
    suspend fun recordSessionViewed(sessionId: String) {
        store.edit { prefs ->
            val current = decodeRecentSessions(prefs[KEY_RECENT_SESSIONS])
            val updated = listOf(sessionId) + current.filterNot { it == sessionId }
            prefs[KEY_RECENT_SESSIONS] =
                AppJson.encodeToString(ListSerializer(String.serializer()), updated.take(RECENT_SESSIONS_CAP))
        }
    }

    private fun decodeChatPreferences(value: String?): ChatPreferences {
        if (value.isNullOrBlank()) return ChatPreferences()
        return runCatching {
            AppJson.decodeFromString(ChatPreferences.serializer(), value)
        }.getOrElse { ChatPreferences() }
    }

    private fun decodeRecentSessions(value: String?): List<String> {
        if (value.isNullOrBlank()) return emptyList()
        // 历史数据损坏时回退空列表,不影响主流程(浏览历史属辅助功能)
        return runCatching {
            AppJson.decodeFromString(ListSerializer(String.serializer()), value)
        }.getOrElse { emptyList() }
    }

    private companion object {
        private const val RECENT_SESSIONS_CAP = 10
        private val KEY_RECENT_SESSIONS = stringPreferencesKey("recent_sessions_json")
        private val KEY_TOKEN_ESTIMATE_ENABLED = booleanPreferencesKey("token_estimate_enabled")
        private val KEY_PASTE_AS_FILE_ENABLED = booleanPreferencesKey("paste_as_file_enabled")
        private val KEY_PASTE_AS_FILE_THRESHOLD = intPreferencesKey("paste_as_file_threshold")
        private val KEY_FLOOR_LIMITER_ENABLED = booleanPreferencesKey("floor_limiter_enabled")
        private val KEY_FLOOR_LIMIT = intPreferencesKey("floor_limit")
        private val KEY_CHAT_PREFERENCES = stringPreferencesKey("chat_preferences_json")
    }
}
