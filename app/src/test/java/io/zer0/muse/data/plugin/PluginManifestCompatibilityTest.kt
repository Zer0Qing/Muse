package io.zer0.muse.data.plugin

import io.zer0.common.AppJson
import io.zer0.muse.tools.script.ToolDeclaration
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 清单兼容契约的回归（P1-B3，用户已拍板"读取永久兼容"）。
 *
 * 规则很简单，但必须钉住，否则一定会被"顺手收紧"：
 *  - **读取永久兼容**：已装出去的包不可能被召回重写，一旦新客户端读不动旧清单，
 *    用户看到的就是"我的插件突然全没了"，而且没有回滚办法；
 *  - **写入一律 v2**：新字段在写出时带上，读旧包时按默认值补齐。
 *
 * 兼容机制本身来自共享 JSON 配置（`ignoreUnknownKeys` + `coerceInputValues` +
 * 字段默认值），这里测的是**行为**而不是配置项 —— 配置被改动时，这些用例会红。
 */
class PluginManifestCompatibilityTest {

    private val minimalV1 = """
        {
          "id": "legacy-tool",
          "name": "老插件",
          "capabilities": ["resource.read", "network"]
        }
    """.trimIndent()

    @Test
    fun `a v1 manifest without any v2 fields still loads`() {
        val manifest = AppJson.decodeFromString(PluginManifest.serializer(), minimalV1)

        assertEquals("legacy-tool", manifest.id)
        assertEquals("老插件", manifest.name)
        assertEquals(listOf("resource.read", "network"), manifest.capabilities)
    }

    @Test
    fun `missing optional fields fall back to defaults instead of failing the whole package`() {
        val manifest = AppJson.decodeFromString(PluginManifest.serializer(), minimalV1)

        // 这些字段都是 v2 前后陆续加的：旧包里没有，必须落到默认值而不是抛异常
        assertEquals("main.js", manifest.entry)
        assertEquals("js", manifest.runtime)
        assertEquals("tool", manifest.kind)
        assertTrue("缺 tools 的旧包不该被判为非法", manifest.tools.isEmpty())
        assertTrue("缺签名也不该炸：只是验签会失败并给出原因", manifest.signature == null)
    }

    @Test
    fun `unknown future fields are ignored rather than rejected`() {
        val withFuture = """
            {
              "id": "future-tool",
              "name": "未来的包",
              "apiVersion": "3.0",
              "contributions": { "widgets": [{ "id": "w1" }] },
              "capabilities": ["network"]
            }
        """.trimIndent()

        val manifest = AppJson.decodeFromString(PluginManifest.serializer(), withFuture)

        assertEquals("future-tool", manifest.id)
        assertEquals(listOf("network"), manifest.capabilities)
    }

    @Test
    fun `v1 capability strings still reach the same bridge actions through the gateway`() {
        // 旧包用的 storage.* 与新代码用的 resource.* 必须落到同一套桥动作上，
        // 否则"读取兼容"只是解析通过，运行时仍然全被拒。
        // 走网关而不是自己查映射表：归一化发生在网关里，抄一份查找逻辑只会测到一个假象。
        val legacy = AppJson.decodeFromString(
            PluginManifest.serializer(),
            """{"id":"legacy","name":"老插件","capabilities":["storage.read","storage.write"]}""",
        )
        val modern = AppJson.decodeFromString(
            PluginManifest.serializer(),
            """{"id":"modern","name":"新插件","capabilities":["resource.read","resource.write"]}""",
        )

        val legacyActions = gatewayFor("legacy").allowedActions("legacy", legacy.capabilities)
        val modernActions = gatewayFor("modern").allowedActions("modern", modern.capabilities)

        assertEquals("两种拼写必须等价", modernActions, legacyActions)
        assertTrue("该有 fs_write: $legacyActions", legacyActions.contains("fs_write"))
        assertTrue("该有 fs_read: $legacyActions", legacyActions.contains("fs_read"))
        assertFalse("未声明的动作不该进放行集", legacyActions.contains("clipboard_write"))
        assertFalse("也不该带网络", legacyActions.contains("http_get"))
    }

    @Test
    fun `writing a manifest keeps the fields needed by other components`() {
        val manifest = PluginManifest(
            id = "round-trip",
            name = "回写",
            capabilities = listOf("network"),
            tools = listOf(
                ToolDeclaration(
                    name = "lookup",
                    description = "查询",
                    parametersJson = """{"type":"object","properties":{}}""",
                    functionName = "lookup",
                ),
            ),
        )

        val encoded = AppJson.parseToJsonElement(AppJson.encodeToString(PluginManifest.serializer(), manifest)).jsonObject

        assertEquals("round-trip", (encoded["id"] as? JsonPrimitive)?.content)
        assertEquals(1, encoded["tools"]?.jsonArray?.size)
        assertEquals("lookup", encoded["tools"]!!.jsonArray[0].jsonObject["functionName"]!!.jsonPrimitive.content)
    }

    private fun gatewayFor(pluginId: String) = PluginCapabilityGateway(
        java.nio.file.Files.createTempDirectory("compat-$pluginId").resolve("permissions.json").toFile(),
    ) { 1_000L }
}
