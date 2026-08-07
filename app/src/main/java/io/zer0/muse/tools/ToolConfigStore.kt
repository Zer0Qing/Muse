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

// v1.x 修复: DataStore delegate 必须定义在文件顶层(单例)。
// 定义在类内部时,每个实例都会注册独立 delegate;
// 多实例(Koin 注入 + 直接 new)会触发
// "multiple DataStores active for the same file" 崩溃。
private val Context.toolDataStore: androidx.datastore.core.DataStore<Preferences> by
    preferencesDataStore(name = "muse_tool_config")

/**
 * 工具审批策略持久化存储。
 *
 * 按工具名保存审批策略到 DataStore；未显式配置的工具默认 ALWAYS_ALLOW。
 * 存储格式为 JSON 对象，DataStore key 固定为 `tool_policies`。
 */
class ToolConfigStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    companion object {
        private val TOOL_POLICIES_KEY = stringPreferencesKey("tool_policies")
    }

    @Serializable
    data class ToolPolicies(
        val policies: Map<String, ToolApprovalPolicy> = emptyMap(),
    )

    /** Flow of all tool policies. */
    val policiesFlow: Flow<Map<String, ToolApprovalPolicy>> =
        context.toolDataStore.data.map { prefs ->
            decodePolicies(prefs).policies
        }

    /** Get the approval policy for a specific tool. */
    suspend fun getPolicy(toolName: String): ToolApprovalPolicy {
        val policies = policiesFlow.first()
        return policies[toolName] ?: ToolApprovalPolicy.ALWAYS_ALLOW
    }

    /** 设置指定工具的审批策略。 */
    suspend fun setPolicy(toolName: String, policy: ToolApprovalPolicy) {
        context.toolDataStore.edit { prefs ->
            val current = decodePolicies(prefs)
            val updated = current.copy(
                policies = current.policies.toMutableMap().apply {
                    if (policy == ToolApprovalPolicy.ALWAYS_ALLOW) {
                        remove(toolName) // default, no need to store
                    } else {
                        put(toolName, policy)
                    }
                }
            )
            prefs[TOOL_POLICIES_KEY] = encodePolicies(updated)
        }
    }

    /** 根据已存储的策略解析工具调用的审批状态。 */
    suspend fun resolveApprovalState(toolName: String): ToolApprovalState {
        val policy = getPolicy(toolName)
        return when (policy) {
            ToolApprovalPolicy.ALWAYS_ALLOW -> ToolApprovalState.Auto
            ToolApprovalPolicy.ALWAYS_DENY -> ToolApprovalState.Denied("Tool disabled by user")
            ToolApprovalPolicy.ASK_EVERY_TIME -> ToolApprovalState.Pending
        }
    }

    private fun decodePolicies(prefs: Preferences): ToolPolicies {
        val raw = prefs[TOOL_POLICIES_KEY] ?: return ToolPolicies()
        return try {
            json.decodeFromString<ToolPolicies>(raw)
        } catch (_: Exception) {
            ToolPolicies()
        }
    }

    private fun encodePolicies(policies: ToolPolicies): String =
        json.encodeToString(policies)

}
