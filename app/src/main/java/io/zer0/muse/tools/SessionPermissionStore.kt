package io.zer0.muse.tools

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 会话级权限模式持久化。
 *
 * 按 sessionId 存储当前会话的工具权限模式,切换会话时自动恢复。
 *
 * v1.x: 新增"本会话允许"临时缓存([sessionAllowedTools])— 内存态,不持久化,
 * 会话切换/结束时自动失效。提供"始终允许"(持久)与"批准本次"(单次)之间的中间地带:
 * 用户在审批卡片点击"本会话允许"后,该工具在本会话内不再弹审批卡片,
 * 切换会话或冷启动后自动回到询问状态。
 */
class SessionPermissionStore(private val context: Context) {

    private val Context.permissionDataStore by preferencesDataStore(name = "muse_session_permission")

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * 会话级临时允许缓存:sessionId → 已允许工具名集合。
     *
     * 纯内存态([Volatile] + [Synchronized] 保护),不持久化到 DataStore,
     * App 进程死亡自然丢失。切换会话时由调用方(ChatViewModel)显式清除旧会话条目。
     */
    @Volatile
    private var sessionAllowedTools: Map<String, Set<String>> = emptyMap()

    companion object {
        private val PERMISSION_MODES_KEY = stringPreferencesKey("session_permission_modes")
    }

    @Serializable
    data class StoredModes(
        val modes: Map<String, SessionPermissionMode> = emptyMap(),
    )

    /** 全部会话权限模式流。 */
    val modesFlow: Flow<Map<String, SessionPermissionMode>> =
        context.permissionDataStore.data.map { prefs ->
            parseModes(prefs).modes
        }

    /** 读取指定会话的权限模式,未设置时返回 [defaultMode](默认 ASK)。 */
    suspend fun getMode(
        sessionId: String,
        defaultMode: SessionPermissionMode = SessionPermissionMode.ASK,
    ): SessionPermissionMode {
        return modesFlow.first()[sessionId] ?: defaultMode
    }

    /** 设置指定会话的权限模式。 */
    suspend fun setMode(sessionId: String, mode: SessionPermissionMode) {
        context.permissionDataStore.edit { prefs ->
            val current = parseModes(prefs).modes.toMutableMap()
            current[sessionId] = mode
            prefs[PERMISSION_MODES_KEY] = json.encodeToString(StoredModes(current))
        }
    }

    /** 清除指定会话的权限设置(会话删除时调用)。 */
    suspend fun clearMode(sessionId: String) {
        context.permissionDataStore.edit { prefs ->
            val current = parseModes(prefs).modes.toMutableMap()
            current.remove(sessionId)
            prefs[PERMISSION_MODES_KEY] = json.encodeToString(StoredModes(current))
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // v1.x: 会话级临时允许缓存(内存态,不持久化)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 判断某工具在当前会话是否已临时允许(本会话不再问)。
     *
     * @return true 表示用户此前在本会话内点过"本会话允许",应直接 Auto 执行不再弹审批卡片
     */
    fun isAllowedThisSession(sessionId: String, toolName: String): Boolean {
        return sessionAllowedTools[sessionId]?.contains(toolName) == true
    }

    /**
     * 把工具加入当前会话的临时允许集合。
     *
     * 由 ChatViewModel 在用户点击"本会话允许"按钮时调用。
     * 仅影响本会话,切换会话/冷启动后自动失效(由 [clearSession] 或进程死亡清理)。
     */
    @Synchronized
    fun allowToolForSession(sessionId: String, toolName: String) {
        val current = sessionAllowedTools.toMutableMap()
        val set = (current[sessionId] ?: emptySet()).toMutableSet()
        set.add(toolName)
        current[sessionId] = set
        sessionAllowedTools = current
    }

    /**
     * 清除指定会话的临时允许集合。
     *
     * 由 ChatViewModel 在切换会话/新建会话时对旧会话调用,实现"会话结束自动失效"。
     */
    @Synchronized
    fun clearSession(sessionId: String) {
        if (sessionAllowedTools.isEmpty()) return
        val current = sessionAllowedTools.toMutableMap()
        current.remove(sessionId)
        sessionAllowedTools = current
    }

    /**
     * 清除全部会话的临时允许集合(应用进程级清理用,通常不需要,进程死亡自然丢失)。
     */
    @Synchronized
    fun clearAllSessions() {
        sessionAllowedTools = emptyMap()
    }

    private fun parseModes(prefs: Preferences): StoredModes {
        val raw = prefs[PERMISSION_MODES_KEY] ?: return StoredModes()
        return try {
            json.decodeFromString<StoredModes>(raw)
        } catch (_: Exception) {
            StoredModes()
        }
    }
}
