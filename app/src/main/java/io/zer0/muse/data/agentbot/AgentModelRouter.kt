package io.zer0.muse.data.agentbot

import android.content.Context
import android.content.SharedPreferences
import io.zer0.ai.ProviderConfigStore
import io.zer0.ai.core.Model
import io.zer0.ai.core.ProviderConfig
import io.zer0.common.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Agent 分角色模型路由。
 *
 * 允许高级用户为「前台秒回对话 / 后台深度执行 / 视觉与虚拟屏操控」分别指定模型；
 * 任一槽位留空时自动跟随当前主模型，保证默认零配置可用。
 */
class AgentModelRouter(context: Context) {

    enum class Role(val prefKey: String) {
        FOREGROUND("agent_route_foreground"),
        BACKGROUND("agent_route_background"),
        VISION("agent_route_vision"),
    }

    data class Binding(
        val providerId: String = "",
        val modelId: String = "",
    ) {
        val isConfigured: Boolean get() = modelId.isNotBlank()
    }

    data class Snapshot(
        val foreground: Binding = Binding(),
        val background: Binding = Binding(),
        val vision: Binding = Binding(),
    )

    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val _snapshot = MutableStateFlow(readSnapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    fun setBinding(role: Role, providerId: String, modelId: String) {
        val editor = prefs.edit()
        if (modelId.isBlank()) {
            editor.remove(role.prefKey)
        } else {
            editor.putString(role.prefKey, "${providerId.trim()}|${modelId.trim()}")
        }
        editor.apply()
        _snapshot.value = readSnapshot()
    }

    fun clearBinding(role: Role) {
        setBinding(role, "", "")
    }

    /**
     * 解析指定角色实际使用的 (ProviderConfig?, Model?)。
     * 未配置或目标模型已不存在时返回 null to null，由调用方平滑回退到默认主模型。
     */
    suspend fun resolve(role: Role, configStore: ProviderConfigStore): Pair<ProviderConfig?, Model?> {
        val binding = when (role) {
            Role.FOREGROUND -> _snapshot.value.foreground
            Role.BACKGROUND -> _snapshot.value.background
            Role.VISION -> _snapshot.value.vision
        }
        if (!binding.isConfigured) return null to null
        val providers = runCatching { configStore.getAllProviders() }
            .onFailure { Logger.w("AgentModelRouter", "读取供应商列表失败: ${it.message}") }
            .getOrDefault(emptyList())
        val matchedProvider = providers.firstOrNull { provider ->
            binding.providerId.isBlank() || provider.id == binding.providerId
        } ?: providers.firstOrNull { provider ->
            provider.models.any { it.id == binding.modelId }
        } ?: return null to null
        val matchedModel = matchedProvider.models.firstOrNull { it.id == binding.modelId }
            ?: return null to null
        return matchedProvider to matchedModel
    }

    private fun readSnapshot(): Snapshot = Snapshot(
        foreground = parseBinding(prefs.getString(Role.FOREGROUND.prefKey, null)),
        background = parseBinding(prefs.getString(Role.BACKGROUND.prefKey, null)),
        vision = parseBinding(prefs.getString(Role.VISION.prefKey, null)),
    )

    private fun parseBinding(raw: String?): Binding {
        if (raw.isNullOrBlank()) return Binding()
        val sep = raw.indexOf('|')
        return if (sep < 0) {
            Binding(providerId = "", modelId = raw.trim())
        } else {
            Binding(
                providerId = raw.substring(0, sep).trim(),
                modelId = raw.substring(sep + 1).trim(),
            )
        }
    }

    companion object {
        private const val PREF_NAME = "muse_agent_model_router"
    }
}
