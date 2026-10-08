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

    val tokenEstimateEnabledFlow: Flow<Boolean> =
        store.data.map { prefs ->
            prefs[KEY_TOKEN_ESTIMATE_ENABLED] ?: false
        }
    val pasteAsFileEnabledFlow: Flow<Boolean> =
        store.data.map { prefs ->
            prefs[KEY_PASTE_AS_FILE_ENABLED] ?: true
        }
    val pasteAsFileThresholdFlow: Flow<Int> =
        store.data.map { prefs ->
            prefs[KEY_PASTE_AS_FILE_THRESHOLD] ?: 2000
        }
    val floorLimiterEnabledFlow: Flow<Boolean> =
        store.data.map { prefs ->
            prefs[KEY_FLOOR_LIMITER_ENABLED] ?: false
        }
    val floorLimitFlow: Flow<Int> =
        store.data.map { prefs ->
            prefs[KEY_FLOOR_LIMIT] ?: 16
        }

    /** v2.x: 工具轮次上限(0=无限制;默认无限制,长任务不再被轮次卡断)。 */
    val toolLoopMaxRoundsFlow: Flow<Int> =
        store.data.map { prefs ->
            prefs[KEY_TOOL_LOOP_MAX_ROUNDS] ?: 0
        }
    val chatPreferencesFlow: Flow<ChatPreferences> =
        store.data.map { prefs ->
            decodeChatPreferences(prefs[KEY_CHAT_PREFERENCES])
        }

    /** C3: 最近浏览会话 id 列表(最近优先,去重置顶,最多 [RECENT_SESSIONS_CAP] 条)。 */
    val recentSessionsFlow: Flow<List<String>> =
        store.data.map { prefs ->
            decodeRecentSessions(prefs[KEY_RECENT_SESSIONS])
        }

    suspend fun getChatPreferences(): ChatPreferences = chatPreferencesFlow.first()

    /**
     * v2.5.3 (P1-1): 函数式更新 ChatPreferences —— 内存唯一事实源 + 原子读-改-写。
     *
     * 关键：读旧值、应用 block、写回这三步全部在 edit 的同一事务内完成，
     * DataStore 的 edit 块保证拿到的是最新已提交值，因此并发写不会互相覆盖。
     * 这是替换“传整份 data class 回写”的正确入口：调用方只描述“我要改什么”，
     * 而不是先 snapshot 再整份写回（后者会拿过时快照覆盖别处的修改）。
     */
    suspend fun updateChatPreferences(block: (ChatPreferences) -> ChatPreferences) {
        store.edit { prefs ->
            val raw = prefs[KEY_CHAT_PREFERENCES]
            // P1-2: 写前检测损坏 —— 存量 JSON 非空但解不开时，先把原始内容备份，
            // 避免默认值被当成用户数据写回、静默清空全部设置。
            backupIfCorrupt(raw)
            val current = decodeChatPreferences(raw)
            prefs[KEY_CHAT_PREFERENCES] = AppJson.encodeToString(ChatPreferences.serializer(), block(current))
        }
    }

    /**
     * v2.5.3 (P1-2): 存量偏好 JSON 损坏时备份原始串，供事后找回。
     *
     * 只在“原文非空但解不开”时触发；备份写入独立键，不覆盖当前值。
     */
    private fun backupIfCorrupt(raw: String?) {
        if (raw.isNullOrBlank()) return
        val ok = runCatching { AppJson.decodeFromString(ChatPreferences.serializer(), raw) }.isSuccess
        val prefs = context.getSharedPreferences(CORRUPT_BACKUP_PREFS, Context.MODE_PRIVATE)
        // 损坏且尚未备份时才写（保留最初的问题现场，不反复覆盖）
        if (!ok && !prefs.contains(CORRUPT_BACKUP_KEY)) {
            prefs
                .edit()
                .putString(CORRUPT_BACKUP_KEY, raw)
                .putLong(CORRUPT_BACKUP_TS_KEY, System.currentTimeMillis())
                .apply()
            io.zer0.common.Logger.w(
                "ChatSettingsStore",
                "chat_preferences_json 损坏，已备份原始内容(${raw.length} 字符)到 $CORRUPT_BACKUP_PREFS/$CORRUPT_BACKUP_KEY",
            )
        }
    }

    /**
     * @Deprecated v2.5.3 (P1-1): 整份 data class 回写会拿过时快照覆盖别处修改。
     * 改用 [updateChatPreferences] 做函数式更新。保留此方法仅为兼容存量调用与测试。
     */
    @Deprecated("改用 updateChatPreferences(block) 做原子函数式更新，避免整份回写覆盖并发修改")
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

    /** v2.x: 保存工具轮次上限(0=无限制)。 */
    suspend fun saveToolLoopMaxRounds(limit: Int) {
        store.edit { it[KEY_TOOL_LOOP_MAX_ROUNDS] = limit }
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

        // v2.5.3 (P1-2): 损坏偏好 JSON 的备份位置(SharedPreferences，独立于 DataStore，
        // 避免 DataStore 自身损坏时备份也丢失)。
        private const val CORRUPT_BACKUP_PREFS = "muse_settings_corrupt_backup"
        private const val CORRUPT_BACKUP_KEY = "chat_preferences_json_corrupt"
        private const val CORRUPT_BACKUP_TS_KEY = "chat_preferences_json_corrupt_at"
        private val KEY_RECENT_SESSIONS = stringPreferencesKey("recent_sessions_json")
        private val KEY_TOKEN_ESTIMATE_ENABLED = booleanPreferencesKey("token_estimate_enabled")
        private val KEY_PASTE_AS_FILE_ENABLED = booleanPreferencesKey("paste_as_file_enabled")
        private val KEY_PASTE_AS_FILE_THRESHOLD = intPreferencesKey("paste_as_file_threshold")
        private val KEY_FLOOR_LIMITER_ENABLED = booleanPreferencesKey("floor_limiter_enabled")
        private val KEY_FLOOR_LIMIT = intPreferencesKey("floor_limit")
        private val KEY_TOOL_LOOP_MAX_ROUNDS = intPreferencesKey("tool_loop_max_rounds")
        private val KEY_CHAT_PREFERENCES = stringPreferencesKey("chat_preferences_json")
    }
}
