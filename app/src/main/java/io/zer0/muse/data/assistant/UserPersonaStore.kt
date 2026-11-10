package io.zer0.muse.data.assistant

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.IOException

/**
 * 35: 每助手「用户面具」库。
 *
 * 全局用户画像（[io.zer0.muse.data.SettingsRepository.getUserProfile]）描述"默认的你"；
 * 这里给每个助手单独挑一副面具（RP 场景里对不同助手扮演不同身份）。
 * 库是共享的（一次写好多副），选择是每助手独立的。
 *
 * 存模块自己的 DataStore，不碰 Room schema（交接文档把 DB 迁移列为长期风险）。
 * 读失败/格式不认识时回落"无面具"，绝不抛异常阻塞对话。
 */
@Serializable
data class UserPersona(
    val id: String,
    val name: String,
    val text: String,
)

interface UserPersonaStore {
    val personasFlow: Flow<List<UserPersona>>

    suspend fun personas(): List<UserPersona>

    suspend fun upsert(persona: UserPersona)

    suspend fun delete(personaId: String)

    /** 某助手当前选中的面具；未选返回 null（用全局画像）。 */
    suspend fun selectedFor(assistantId: String): UserPersona?

    suspend fun select(assistantId: String, personaId: String?)
}

private val Context.userPersonaDataStore: DataStore<Preferences> by preferencesDataStore(name = "user_persona_library")

class DataStoreUserPersonaStore(context: Context) : UserPersonaStore {
    private val dataStore = context.applicationContext.userPersonaDataStore
    private val json = Json { ignoreUnknownKeys = true }

    private val preferences: Flow<Preferences> = dataStore.data.catch { t ->
        if (t is IOException) emit(emptyPreferences()) else throw t
    }

    override val personasFlow: Flow<List<UserPersona>> = preferences.map { prefs ->
        decodePersonas(prefs[KEY_PERSONAS])
    }

    override suspend fun personas(): List<UserPersona> = personasFlow.first()

    override suspend fun upsert(persona: UserPersona) {
        val current = personas().toMutableList()
        val idx = current.indexOfFirst { it.id == persona.id }
        if (idx >= 0) current[idx] = persona else current.add(persona)
        dataStore.edit { it[KEY_PERSONAS] = json.encodeToString(ListSerializer(UserPersona.serializer()), current) }
    }

    override suspend fun delete(personaId: String) {
        val current = personas().filterNot { it.id == personaId }
        dataStore.edit { prefs ->
            prefs[KEY_PERSONAS] = json.encodeToString(ListSerializer(UserPersona.serializer()), current)
            // 删掉的面具若正被某助手选中，一并解除选择，避免悬空引用。
            val sel = decodeSelection(prefs[KEY_SELECTION]).filterValues { it != personaId }
            prefs[KEY_SELECTION] = json.encodeToString(MapSerializer(String.serializer(), String.serializer()), sel)
        }
    }

    override suspend fun selectedFor(assistantId: String): UserPersona? {
        val prefs = preferences.first()
        val sel = decodeSelection(prefs[KEY_SELECTION])
        val id = sel[assistantId] ?: return null
        return decodePersonas(prefs[KEY_PERSONAS]).firstOrNull { it.id == id }
    }

    override suspend fun select(assistantId: String, personaId: String?) {
        dataStore.edit { prefs ->
            val sel = decodeSelection(prefs[KEY_SELECTION]).toMutableMap()
            if (personaId == null) sel.remove(assistantId) else sel[assistantId] = personaId
            prefs[KEY_SELECTION] = json.encodeToString(MapSerializer(String.serializer(), String.serializer()), sel)
        }
    }

    private fun decodePersonas(raw: String?): List<UserPersona> = if (raw.isNullOrBlank()) {
        emptyList()
    } else {
        runCatching { json.decodeFromString(ListSerializer(UserPersona.serializer()), raw) }.getOrDefault(emptyList())
    }

    private fun decodeSelection(raw: String?): Map<String, String> = if (raw.isNullOrBlank()) {
        emptyMap()
    } else {
        runCatching {
            json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), raw)
        }.getOrDefault(emptyMap())
    }

    private companion object {
        val KEY_PERSONAS = stringPreferencesKey("personas_json")
        val KEY_SELECTION = stringPreferencesKey("selection_json")
    }
}
