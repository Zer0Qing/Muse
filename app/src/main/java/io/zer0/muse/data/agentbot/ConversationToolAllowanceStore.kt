package io.zer0.muse.data.agentbot

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.zer0.common.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 「本对话内允许」的持久化存储。
 *
 * ## 为什么要重做
 *
 * 旧实现（[io.zer0.muse.tools.SessionPermissionStore.sessionAllowedTools]）是纯内存 Map，
 * 注释写着"不持久化，App 进程死亡自然丢失"。后果是：用户点了"本会话允许"，
 * 切去看另一个对话再切回来，同一个工具又开始弹审批 —— 用户会认为按钮坏了，
 * 转而点更危险的"始终允许"。
 *
 * 这里把它持久化到独立的 DataStore，键是 sessionId，只在该会话被删除时清理。
 *
 * ## 为什么不复用旧存储
 *
 * 旧的 `muse_session_permission` 里已经存了权限模式；混进去会让"清模式"和
 * "清允许列表"两个操作互相牵连。分开存，各自的删除语义才说得清。
 */
class ConversationToolAllowanceStore(private val context: Context) {

    private val Context.allowanceDataStore by preferencesDataStore(name = "muse_conversation_allowance")

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Serializable
    data class StoredAllowances(
        /** sessionId → 已允许的工具名集合。 */
        val allowances: Map<String, Set<String>> = emptyMap(),
    )

    /** 全部允许列表流（诊断与"已放行"页用）。 */
    val allowancesFlow: Flow<Map<String, Set<String>>> =
        context.allowanceDataStore.data.map { prefs -> parse(prefs).allowances }

    /** 某会话是否已允许某工具。 */
    suspend fun isAllowed(sessionId: String, toolName: String): Boolean {
        if (sessionId.isBlank()) return false
        return allowancesFlow.first()[sessionId]?.contains(toolName) == true
    }

    /** 读取某会话已允许的工具名集合。 */
    suspend fun allowedTools(sessionId: String): Set<String> {
        if (sessionId.isBlank()) return emptySet()
        return allowancesFlow.first()[sessionId].orEmpty()
    }

    /** 允许某工具在本会话内不再询问。 */
    suspend fun allow(sessionId: String, toolName: String) {
        if (sessionId.isBlank() || toolName.isBlank()) return
        context.allowanceDataStore.edit { prefs ->
            val current = parse(prefs).allowances.toMutableMap()
            current[sessionId] = (current[sessionId] ?: emptySet()) + toolName
            prefs[KEY] = json.encodeToString(StoredAllowances(current))
        }
    }

    /**
     * 收回某工具的允许。
     *
     * 这是"已放行列表"里那一行"收回"的落点 —— 用户必须能撤销自己给过的授权。
     */
    suspend fun revoke(sessionId: String, toolName: String): Boolean {
        if (sessionId.isBlank()) return false
        var removed = false
        context.allowanceDataStore.edit { prefs ->
            val current = parse(prefs).allowances.toMutableMap()
            val set = current[sessionId] ?: return@edit
            if (toolName !in set) return@edit
            val next = set - toolName
            if (next.isEmpty()) current.remove(sessionId) else current[sessionId] = next
            prefs[KEY] = json.encodeToString(StoredAllowances(current))
            removed = true
        }
        return removed
    }

    /** 会话删除时清掉它的允许列表。 */
    suspend fun clearSession(sessionId: String) {
        if (sessionId.isBlank()) return
        context.allowanceDataStore.edit { prefs ->
            val current = parse(prefs).allowances.toMutableMap()
            if (current.remove(sessionId) == null) return@edit
            prefs[KEY] = json.encodeToString(StoredAllowances(current))
        }
    }

    /** 全部清空（数据管理"清除全部对话"时用）。 */
    suspend fun clearAll() {
        context.allowanceDataStore.edit { prefs ->
            prefs[KEY] = json.encodeToString(StoredAllowances(emptyMap()))
        }
    }

    private fun parse(prefs: androidx.datastore.preferences.core.Preferences): StoredAllowances {
        val raw = prefs[KEY] ?: return StoredAllowances()
        return runCatching { json.decodeFromString<StoredAllowances>(raw) }
            .onFailure { Logger.w("ConversationAllowance", "允许列表解析失败，按空处理: ${it.message}") }
            .getOrDefault(StoredAllowances())
    }

    private companion object {
        val KEY = stringPreferencesKey("conversation_tool_allowances")
    }
}
