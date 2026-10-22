package io.zer0.muse.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.zer0.common.AppJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

/**
 * 用户偏好学习 —— 把「别用空泛鼓励」「别列三点总结」这类习惯沉淀成硬约束。
 *
 * ## 为什么单独存，而不是塞进 ChatPreferences
 *
 * ChatPreferences 是一组**固定字段**的显示/行为开关，加一条新偏好就要改数据类、
 * 改迁移、改设置页三处。而偏好是**开放集合**：用户会不断说「以后别…」，
 * 数量不可预知，内容也不适合做 UI 开关。所以存成列表。
 *
 * ## 生效方式：注入提示词，且标为「用户明确要求」
 *
 * 只写进系统提示词还不够 —— 模型会把偏好当成「建议」而非常规。
 * 所以注入时用强约束措辞，并放在人格段之后、工具纪律之前，确保优先级高于一般风格指引。
 *
 * ## 与「记忆」的区别
 *
 * 记忆是模型对用户的观察（可能是错的），偏好是用户**亲口提出**的要求（必须遵守）。
 * 两者混在一起会让模型分不清哪些是事实、哪些是指令，所以分开存。
 */
@Serializable
data class UserPreference(
    /** 稳定 id，用于删除与去重。 */
    val id: String,
    /** 偏好正文，写成祈使句（「回答不要用空泛的鼓励」）。 */
    val rule: String,
    /** 用户原话（便于追溯为什么有这条）。 */
    val source: String = "",
    /** 创建时间。 */
    val createdAt: Long = 0L,
    /** 是否启用 —— 用户可能想临时关掉某条而不删。 */
    val enabled: Boolean = true,
)

class PreferenceStore(private val context: Context) {

    private val store get() = context.museSettingsDataStore

    /** 全部偏好（含已停用的）。 */
    val preferencesFlow: Flow<List<UserPreference>> = store.data.map { prefs ->
        decode(prefs[KEY_PREFERENCES].orEmpty())
    }

    /** 只有启用的偏好 —— 提示词注入用这个。 */
    val enabledPreferencesFlow: Flow<List<UserPreference>> = preferencesFlow.map { list ->
        list.filter { it.enabled && it.rule.isNotBlank() }
    }

    /**
     * 新增一条偏好。
     *
     * 去重规则：正文归一化后相同则不重复添加（用户可能在不同轮次说同一件事）。
     *
     * @return true 表示真的新增了；false 表示已存在。
     */
    suspend fun add(rule: String, source: String = ""): Boolean {
        val trimmed = rule.trim()
        if (trimmed.isEmpty()) return false
        var added = false
        store.edit { prefs ->
            val current = decode(prefs[KEY_PREFERENCES].orEmpty())
            val normalized = normalize(trimmed)
            if (current.none { normalize(it.rule) == normalized }) {
                val next =
                    current + UserPreference(
                        id = "pref_${System.currentTimeMillis().toString(36)}_${current.size}",
                        rule = trimmed,
                        source = source.trim(),
                        createdAt = System.currentTimeMillis(),
                    )
                prefs[KEY_PREFERENCES] = AppJson.encodeToString(serializer(), next)
                added = true
            }
        }
        return added
    }

    /** 按 id 删除。 */
    suspend fun remove(id: String) {
        store.edit { prefs ->
            val current = decode(prefs[KEY_PREFERENCES].orEmpty())
            val next = current.filterNot { it.id == id }
            prefs[KEY_PREFERENCES] = AppJson.encodeToString(serializer(), next)
        }
    }

    /** 按 id 启用/停用。 */
    suspend fun setEnabled(id: String, enabled: Boolean) {
        store.edit { prefs ->
            val current = decode(prefs[KEY_PREFERENCES].orEmpty())
            val next = current.map { if (it.id == id) it.copy(enabled = enabled) else it }
            prefs[KEY_PREFERENCES] = AppJson.encodeToString(serializer(), next)
        }
    }

    /** 清空（设置页「重置」用）。 */
    suspend fun clear() {
        store.edit { it.remove(KEY_PREFERENCES) }
    }

    /** 同步读取一次，供提示词构建（那里是 suspend 上下文）。 */
    suspend fun enabledPreferences(): List<UserPreference> = enabledPreferencesFlow.first()

    /**
     * 归一化用于去重：去空白、去结尾标点、小写。
     *
     * 不做语义合并 —— 「别用鼓励」和「不要空泛表扬」在代码看来不同，那属于模型判断，
     * 不该由字符串规则硬猜。
     */
    private fun normalize(text: String): String =
        text.trim().lowercase().replace(Regex("""\s+"""), "").trimEnd('。', '.', '！', '!', '？', '?')

    private fun decode(json: String): List<UserPreference> {
        if (json.isBlank()) return emptyList()
        return runCatching {
            AppJson.decodeFromString(serializer(), json)
        }.getOrDefault(emptyList())
    }

    private fun serializer() = kotlinx.serialization.builtins.ListSerializer(UserPreference.serializer())

    private companion object {
        private val KEY_PREFERENCES = stringPreferencesKey("user_preferences")
    }
}
