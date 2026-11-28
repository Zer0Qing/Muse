package io.zer0.muse.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import io.zer0.common.AppJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ToolRegistryM2Test {

    private lateinit var registry: ToolRegistry

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val browserManager = mockk<BrowserManager>(relaxed = true)
        registry = ToolRegistry(context = context, browserManager = browserManager)
    }

    @Test
    fun `parameterEnums and parameterSchemas serialize into ToolDefinition schema`() {
        registry.register(SaveMemoryTool.toolDef()) { "ok" }
        registry.register(TodoTool.toolDef()) { "ok" }

        val defs = registry.listToolsAsToolDefinitions().associateBy { it.name }

        val saveMemSchema = AppJson.parseToJsonElement(defs.getValue("save_memory").parametersJsonSchema).jsonObject
        val catProp = saveMemSchema["properties"]!!.jsonObject["category"]!!.jsonObject
        val catEnum = catProp["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue("preference" in catEnum)
        assertTrue("medical" in catEnum)

        val todoSchema = AppJson.parseToJsonElement(defs.getValue("todo_write").parametersJsonSchema).jsonObject
        val todosProp = todoSchema["properties"]!!.jsonObject["todos"]!!.jsonObject
        assertEquals("array", todosProp["type"]?.jsonPrimitive?.content)
        val statusEnum = todosProp["items"]!!.jsonObject["properties"]!!.jsonObject["status"]!!
            .jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("pending", "in_progress", "completed"), statusEnum)
    }

    @Test
    fun `hiddenFromSchema aliases stay executable while omitted from exposed schemas`() = runBlocking {
        registry.register(
            ToolRegistry.ToolDef(
                name = "visible_tool",
                description = "visible",
                parameters = emptyMap(),
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { "visible_ok" }
        registry.register(
            ToolRegistry.ToolDef(
                name = "legacy_hidden_alias",
                description = "hidden",
                parameters = emptyMap(),
                riskLevel = ToolRiskLevel.SAFE,
                hiddenFromSchema = true,
            ),
        ) { "alias_ok" }

        val exposedNames = registry.listToolsAsToolDefinitions().map { it.name }
        assertTrue("visible_tool" in exposedNames)
        assertFalse("legacy_hidden_alias" in exposedNames)
        assertEquals("alias_ok", registry.executeFromJson("legacy_hidden_alias", "{}"))
    }

    @Test
    fun `tool exception message is sanitized and redacted`() = runBlocking {
        registry.register(
            ToolRegistry.ToolDef(
                name = "boom_tool",
                description = "boom",
                parameters = emptyMap(),
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) {
            throw IllegalStateException("read /data/user/0/io.zer0.muse/files/token.txt failed sk-secret123456")
        }

        val result = registry.executeFromJson("boom_tool", "{}")
        assertTrue("应保留异常类型名以便模型诊断", result.contains("IllegalStateException"))
        assertTrue("内部存储路径应脱敏", result.contains("<path>"))
        assertFalse("不得泄露真实私有路径", result.contains("/data/user/0"))
        assertFalse("不得泄露密钥", result.contains("sk-secret123456"))
    }
}
