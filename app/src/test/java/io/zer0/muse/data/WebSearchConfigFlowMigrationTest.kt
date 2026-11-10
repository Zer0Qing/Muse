package io.zer0.muse.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import io.zer0.muse.web.WebSearchConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R6 最高风险项的**集成**证据：把历史版本写出的 v2 JSON 直接放进真实 DataStore，
 * 走完整的「读盘 → decodePrefsOrNull → decrypted() → migrateToV3」链路，
 * 断言旧 Key / 端点 / 自定义引擎 / 顺序一个不丢。
 *
 * 与 `WebSearchConfigMigrationTest`（只测纯函数 migrateToV3）互补：这里证明
 * `SettingsRepository.webSearchConfigFlow` 真的调用了迁移，而不只是函数本身正确。
 * 若哪天有人把 flow 里的 migrateToV3 删掉，这组断言会红。
 *
 * 写入的是历史 schema 的 JSON（不含 providerEntries 字段），模拟旧 APK 落盘的数据，
 * 不是用当前 data class 序列化出来的新格式 —— 否则测不到「旧字段归并」这条路径。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = SettingsRepositoryTestApplication::class)
class WebSearchConfigFlowMigrationTest {

    private fun context(): Context = ApplicationProvider.getApplicationContext()
    private fun repo() = SettingsRepository(context(), mockk(relaxed = true))

    /** 与 SettingsRepository.KEY_WEB_SEARCH_CONFIG 同名（DataStore key 相等性基于 name）。 */
    private val legacyKey = stringPreferencesKey("web_search_config_json")

    private fun writeLegacy(json: String) = runBlocking {
        val store: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> =
            context().museSettingsDataStore
        // 静态 DataStore 缓存按 filesDir 复用，Robolectric 用例间可能共享同一实例，
        // 先清掉该 key 再写，避免前一个用例的值串进来造成假红/假绿。
        store.edit { it.remove(legacyKey) }
        store.edit { it[legacyKey] = json }
    }

    private fun readMigrated(): WebSearchConfig = runBlocking { repo().webSearchConfigFlow.first() }

    @Test
    fun `历史单 apiKey 与 endpoint 升级后保住 且抬到 v3`() {
        writeLegacy(
            """{"mode":"AUTO","providerName":"Tavily","apiKey":"legacy-main-key",""" +
                """"endpoint":"https://legacy.example.com/v1","enabled":true,""" +
                """"maxSearchesPerTurn":2,"maxResults":5,"fallbackEnabled":true,""" +
                """"providerOrder":[],"policyVersion":2,"apiKeys":{},"customEngines":[]}""",
        )
        val out = readMigrated()

        assertEquals(3, out.policyVersion)
        // 旧字段保留做兼容读取
        assertEquals("legacy-main-key", out.apiKey)
        assertEquals("https://legacy.example.com/v1", out.endpoint)
        assertEquals("Tavily", out.providerName)
        val entry = out.providerEntries.single { it.type == "Tavily" }
        assertEquals(listOf("legacy-main-key"), entry.apiKeys)
        assertEquals("https://legacy.example.com/v1", entry.customUrl)
    }

    @Test
    fun `历史 apiKeys 映射与自定义引擎升级后各成条目`() {
        writeLegacy(
            """{"providerName":"Auto","apiKey":"","endpoint":"","enabled":true,""" +
                """"maxSearchesPerTurn":50,"maxResults":5,"fallbackEnabled":true,""" +
                """"providerOrder":["Brave","Tavily"],"policyVersion":2,""" +
                """"apiKeys":{"Brave":"legacy-brave-key"},"customEngines":[""" +
                """{"id":"c1","name":"MyEngine","endpoint":"https://my.example.com/search",""" +
                """"apiKey":"legacy-custom-key","method":"GET","queryParam":"q",""" +
                """"bodyTemplate":"","resultsPath":"","titlePath":"title","urlPath":"url",""" +
                """"snippetPath":"content","enabled":true}]}""",
        )
        val out = readMigrated()

        assertEquals(3, out.policyVersion)
        assertEquals(listOf("Brave", "Tavily"), out.providerOrder)
        assertTrue(out.fallbackEnabled)
        assertEquals("legacy-brave-key", out.providerEntries.single { it.type == "Brave" }.apiKeys.single())
        val custom = out.providerEntries.single { it.id == "c1" }
        assertEquals("https://my.example.com/search", custom.customUrl)
        assertEquals(listOf("legacy-custom-key"), custom.apiKeys)
    }

    @Test
    fun `自托管实例只有端点没有 key 也保住`() {
        writeLegacy(
            """{"providerName":"SearXNG","apiKey":"",""" +
                """"endpoint":"https://searx.example.com","enabled":true,""" +
                """"maxSearchesPerTurn":50,"maxResults":5,"fallbackEnabled":true,""" +
                """"providerOrder":[],"policyVersion":2,"apiKeys":{},"customEngines":[]}""",
        )
        val out = readMigrated()

        val searx = out.providerEntries.single { it.type == "SearXNG" }
        assertEquals("https://searx.example.com", searx.customUrl)
        assertTrue("自托管实例不该凭空造出 key", searx.apiKeys.isEmpty())
    }

    @Test
    fun `已是 v3 的配置不被二次归并 条目不重复`() {
        writeLegacy(
            """{"providerName":"Auto","apiKey":"","endpoint":"","enabled":true,""" +
                """"maxSearchesPerTurn":50,"maxResults":5,"fallbackEnabled":true,""" +
                """"providerOrder":[],"policyVersion":3,"apiKeys":{},"customEngines":[],""" +
                """"providerEntries":[{"id":"Tavily","type":"Tavily",""" +
                """"customUrl":"","apiKeys":["current-key"],"model":"",""" +
                """"extras":{},"enabled":true}]}""",
        )
        val out = readMigrated()

        assertEquals(3, out.policyVersion)
        assertEquals(1, out.providerEntries.size)
        assertEquals(listOf("current-key"), out.providerEntries.single().apiKeys)
        assertFalse("v3 不该再追加旧字段归并出来的条目", out.providerEntries.any { it.apiKeys.contains("legacy-main-key") })
    }

    @Test
    fun `损坏的 JSON 回落可用默认配置而不让 Flow 永久失效`() {
        writeLegacy("{ this is not json")
        // first() 能返回本身就证明 Flow 没被异常永久打断。
        val out = readMigrated()

        // 回落的默认配置同样会走一遍预算迁移 + migrateToV3，所以版本号是 3，
        // 不是 data class 的初始值 0 —— 断言 0 会把正确的兜底行为误判成红。
        // 真正要守住的是「损坏内容一点都没残留进来」。
        assertTrue("损坏数据不能残留成条目", out.providerEntries.isEmpty())
        assertTrue("损坏内容不能被当成 key", out.apiKey.isBlank())
        assertTrue("损坏内容不能被当成每家 key", out.apiKeys.isEmpty())
        assertTrue("自定义引擎不能残留", out.customEngines.isEmpty())
        assertEquals("回落后的预算必须是可用值", 50, out.maxSearchesPerTurn)
        assertEquals("回落默认配置后仍应完成迁移", 3, out.policyVersion)
    }
}
