package io.zer0.muse.data.agentbot

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 专职队友与外部渠道数字身份绑定存储。
 *
 * 允许为每个专职 Bot 绑定其负责监听/代理的外部渠道 id（飞书 / Telegram / QQ / WeClaw / 钉钉），
 * 以及角色专长标签，实现「工具共享、角色身份与渠道归属清晰隔离」。
 */
class AgentSquadIdentityStore(context: Context) {

    data class IdentityBinding(
        val botId: String,
        val channelIds: Set<String> = emptySet(),
        val specialty: String = "",
    )

    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val _bindings = MutableStateFlow(readAll())
    val bindings: StateFlow<Map<String, IdentityBinding>> = _bindings.asStateFlow()

    fun toggleChannelForBot(botId: String, channelId: String) {
        if (botId.isBlank() || channelId.isBlank()) return
        val current = _bindings.value[botId] ?: IdentityBinding(botId)
        val nextChannels = if (channelId in current.channelIds) {
            current.channelIds - channelId
        } else {
            current.channelIds + channelId
        }
        saveBinding(current.copy(channelIds = nextChannels))
    }

    fun setSpecialty(botId: String, specialty: String) {
        if (botId.isBlank()) return
        val current = _bindings.value[botId] ?: IdentityBinding(botId)
        saveBinding(current.copy(specialty = specialty.trim().take(48)))
    }

    fun channelsOf(botId: String): Set<String> =
        _bindings.value[botId]?.channelIds.orEmpty()

    fun specialtyOf(botId: String): String =
        _bindings.value[botId]?.specialty.orEmpty()

    fun botForChannel(channelId: String): String? =
        _bindings.value.values.firstOrNull { channelId in it.channelIds }?.botId

    private fun saveBinding(binding: IdentityBinding) {
        prefs.edit()
            .putStringSet("$KEY_CHANNELS_PREFIX${binding.botId}", binding.channelIds)
            .putString("$KEY_SPECIALTY_PREFIX${binding.botId}", binding.specialty)
            .apply()
        _bindings.value = readAll()
    }

    private fun readAll(): Map<String, IdentityBinding> {
        val botIds = mutableSetOf<String>()
        prefs.all.keys.forEach { key ->
            when {
                key.startsWith(KEY_CHANNELS_PREFIX) -> botIds += key.removePrefix(KEY_CHANNELS_PREFIX)
                key.startsWith(KEY_SPECIALTY_PREFIX) -> botIds += key.removePrefix(KEY_SPECIALTY_PREFIX)
            }
        }
        return botIds.associateWith { botId ->
            IdentityBinding(
                botId = botId,
                channelIds = prefs.getStringSet("$KEY_CHANNELS_PREFIX$botId", emptySet()).orEmpty().toSet(),
                specialty = prefs.getString("$KEY_SPECIALTY_PREFIX$botId", "").orEmpty(),
            )
        }
    }

    companion object {
        private const val PREF_NAME = "muse_agent_squad_identities"
        private const val KEY_CHANNELS_PREFIX = "channels_"
        private const val KEY_SPECIALTY_PREFIX = "specialty_"
    }
}
