package io.zer0.muse.data.plugin

import io.zer0.common.AppJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.x（技术债项目 C）：签名 payload 白名单化的守护测试。
 *
 * 三个关键保证：
 *  1. 白名单覆盖 PluginManifest 全部字段（字段集变化时此测试提醒同步维护）
 *  2. 白名单过滤对"全字段在白名单内"的 manifest 输出与直接序列化逐字节一致
 *     （存量包验签兼容性）
 *  3. 白名单之外的新字段会被剔除（新增字段默认不参与签名）
 */
class SignedManifestFieldCoverageTest {

    /** PluginManifest 顶层全部字段名（与序列化器声明序一致）。 */
    private val allManifestFields = setOf(
        "id", "name", "version", "description", "author", "minAppVersion",
        "entry", "kind", "trust", "hidden", "capabilities", "permissions",
        "activationEvents", "hooks", "enabled", "tools", "signature",
        "contributes", "uiPanel", "toolCards",
    )

    @Test
    fun `whitelist covers all current manifest fields`() {
        // 用一个"全默认值可编码"的 manifest 序列化，看实际输出字段
        val manifest = PluginManifest(id = "t", name = "T")
        val encoded = AppJson.encodeToString(PluginManifest.serializer(), manifest)
        val keys = AppJson.parseToJsonElement(encoded).jsonObject.keys
        // AppJson.encodeDefaults=true：所有非 NEVER 字段都会出现
        keys.forEach { key ->
            assertTrue("manifest 字段 '$key' 不在白名单中——若为新增字段，确认是否需要签名保护", key in PluginSecurityGate.SIGNED_TOP_LEVEL_FIELDS)
        }
        // 反向：白名单里不应有 manifest 已不存在的字段（改名/删除时提醒清理）
        PluginSecurityGate.SIGNED_TOP_LEVEL_FIELDS.forEach { field ->
            assertTrue("白名单字段 '$field' 已不存在于 PluginManifest，请同步清理", field in allManifestFields)
        }
    }

    @Test
    fun `whitelist filter is byte-identical for fully-whitelisted manifest`() {
        val manifest = PluginManifest(
            id = "t",
            name = "T",
            capabilities = listOf("network"),
            activationEvents = listOf("onStartup"),
        )
        val direct = AppJson.encodeToString(PluginManifest.serializer(), manifest)
        val viaWhitelist = PluginSecurityGate.signedManifestJson(manifest)
        // JsonObject.toString 与 Json.encodeToString 的对象输出格式一致（均为紧凑无空格）
        // 这里做语义等价 + 字节对比双重断言：若字节不一致说明编码格式漂移，需要排查
        assertEquals(
            AppJson.parseToJsonElement(direct).jsonObject,
            AppJson.parseToJsonElement(viaWhitelist).jsonObject,
        )
        assertEquals(direct, viaWhitelist)
    }

    @Test
    fun `fields outside whitelist are stripped from payload`() {
        val manifest = PluginManifest(id = "t", name = "T")
        val direct = AppJson.encodeToString(PluginManifest.serializer(), manifest)
        // 模拟未来新增字段：往 JSON 里塞一个白名单外的字段，过滤后必须被剔除
        val withNewField = Json.parseToJsonElement(direct).jsonObject
        val mutated = buildJsonObject {
            withNewField.forEach { (k, v) -> put(k, v) }
            put("futureField", "should-be-stripped")
        }
        val reserialized = mutated.toString()
        assertTrue(reserialized.contains("futureField"))
        val filtered = buildJsonObject {
            AppJson.parseToJsonElement(reserialized).jsonObject.forEach { (k, v) ->
                if (k in PluginSecurityGate.SIGNED_TOP_LEVEL_FIELDS) put(k, v)
            }
        }
        assertFalse(filtered.toString().contains("futureField"))
        // 语义上与原 manifest 相同（剔除新增字段后）
        assertEquals(AppJson.parseToJsonElement(direct).jsonObject, filtered)
    }
}
