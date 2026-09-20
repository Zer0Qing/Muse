package io.zer0.muse.data.plugin.market

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.muse.data.museSettingsDataStore
import io.zer0.muse.data.plugin.PluginSecurityGate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * 插件市场设置：目录 URL 与目录信任根公钥。
 *
 * 默认值来自安装包内置的官方目录 [PluginMarketDefaults]，因此市场开箱即用、无需配置；
 * 用户仍可覆盖目录地址（测试目录或应急切换），但**内置信任根始终参与验签且不可移除**，
 * 避免误配置让官方目录失效。未配置任何信任根时目录校验直接拒绝，不会退化为「信任任何目录」。
 * 信任根公钥只能由应用内置或用户显式配置，绝不从目录内容中读取。
 */
class PluginMarketSettings(private val context: Context) {

    private val store get() = context.museSettingsDataStore

    /** 生效的目录 URL：用户覆盖优先，否则使用内置官方目录。 */
    val catalogUrlFlow: Flow<String> = store.data.map { prefs ->
        prefs[KEY_CATALOG_URL]?.trim()?.takeIf { it.isNotEmpty() } ?: PluginMarketDefaults.CATALOG_URL
    }

    /** 用户显式配置的目录覆盖值；空表示正在使用内置官方目录。 */
    val catalogUrlOverrideFlow: Flow<String> = store.data.map { prefs ->
        prefs[KEY_CATALOG_URL].orEmpty().trim()
    }

    /** 生效的目录信任根：内置官方信任根 + 用户追加的信任根，内置项不可被覆盖或移除。 */
    val catalogRootKeysFlow: Flow<Map<String, String>> = store.data.map { prefs ->
        effectiveRootKeys(decodeRootKeys(prefs[KEY_CATALOG_ROOT_KEYS]))
    }

    suspend fun catalogUrl(): String = catalogUrlFlow.first()

    suspend fun catalogUrlOverride(): String = catalogUrlOverrideFlow.first()

    suspend fun catalogRootKeys(): Map<String, String> = catalogRootKeysFlow.first()

    suspend fun saveCatalogUrl(url: String) {
        val trimmed = url.trim()
        store.edit { prefs ->
            if (trimmed.isEmpty()) prefs.remove(KEY_CATALOG_URL) else prefs[KEY_CATALOG_URL] = trimmed
        }
    }

    /** 清除用户覆盖，回到内置官方目录。 */
    suspend fun resetCatalogUrl() = saveCatalogUrl("")

    /**
     * 保存/更新一个目录信任根公钥。
     *
     * 公钥在写入前先规范化校验；非法输入返回 failure 且不修改设置。
     * keyId 与内置官方信任根同名时拒绝写入，防止覆盖内置信任根。
     */
    suspend fun saveCatalogRootKey(keyId: String, publicKeyBase64: String): Result<String> {
        val id = keyId.trim()
        if (id.isEmpty()) return Result.failure(IllegalArgumentException("keyId 不能为空"))
        if (id in PluginMarketDefaults.catalogRootKeys) {
            return Result.failure(IllegalArgumentException("不能覆盖内置的官方目录信任根"))
        }
        val normalized = PluginSecurityGate.canonicalPublicKey(publicKeyBase64.trim())
            .getOrElse { return Result.failure(IllegalArgumentException("公钥不是合法的 Base64 X.509 公钥")) }
        // 只持久化用户自己追加的信任根：内置项始终由安装包提供，不写进设置。
        val next = userRootKeys() + (id to normalized)
        store.edit { prefs ->
            prefs[KEY_CATALOG_ROOT_KEYS] = encodeRootKeys(next)
        }
        return Result.success(normalized)
    }

    /** 移除一个用户追加的目录信任根；内置官方信任根不可移除。 */
    suspend fun removeCatalogRootKey(keyId: String) {
        val id = keyId.trim()
        if (id in PluginMarketDefaults.catalogRootKeys) return
        val next = userRootKeys() - id
        store.edit { prefs ->
            if (next.isEmpty()) prefs.remove(KEY_CATALOG_ROOT_KEYS) else prefs[KEY_CATALOG_ROOT_KEYS] = encodeRootKeys(next)
        }
    }

    /** 用户显式追加的信任根（不含内置项）。 */
    private suspend fun userRootKeys(): Map<String, String> =
        decodeRootKeys(store.data.first()[KEY_CATALOG_ROOT_KEYS])

    /** 生效信任根 = 用户追加项与内置项合并；同名时以内置项为准。 */
    private fun effectiveRootKeys(configured: Map<String, String>): Map<String, String> =
        configured + PluginMarketDefaults.catalogRootKeys

    private fun decodeRootKeys(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            AppJson.decodeFromString(ROOT_KEYS_SERIALIZER, raw)
                .filterKeys { it.isNotBlank() }
                .filterValues { it.isNotBlank() }
        }.getOrElse { error ->
            Logger.w(TAG, "目录信任根解析失败，按未配置处理", error)
            emptyMap()
        }
    }

    private fun encodeRootKeys(keys: Map<String, String>): String =
        AppJson.encodeToString(ROOT_KEYS_SERIALIZER, keys)

    private companion object {
        private const val TAG = "PluginMarketSettings"
        private val KEY_CATALOG_URL = stringPreferencesKey("plugin_market_catalog_url")
        private val KEY_CATALOG_ROOT_KEYS = stringPreferencesKey("plugin_market_catalog_root_keys")
        private val ROOT_KEYS_SERIALIZER = MapSerializer(String.serializer(), String.serializer())
    }
}
