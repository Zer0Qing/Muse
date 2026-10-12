package io.zer0.muse.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ToolRegistryJsonToolTest {

    @Test
    fun `JSON tool receives numeric and boolean values without string coercion`() = runBlocking {
        val registry = ToolRegistry(ApplicationProvider.getApplicationContext<Context>())
        val schema = """{"type":"object","properties":{"count":{"type":"integer"},"enabled":{"type":"boolean"},"filters":{"type":"array","items":{"type":"string"}}},"required":["count","enabled"]}"""
        registry.registerJson(
            ToolRegistry.ToolDef(
                name = "mcp_test__typed",
                description = "typed test tool",
                parameters = mapOf(
                    "count" to "number",
                    "enabled" to "boolean",
                ),
                required = setOf("count", "enabled"),
                category = "mcp",
                parameterTypes = mapOf("count" to "integer", "enabled" to "boolean"),
                rawParametersJsonSchema = schema,
            ),
        ) { args ->
            "count=${args["count"]};enabled=${args["enabled"]}"
        }

        val result = registry.executeFromJson(
            "mcp_test__typed",
            """{"count":3,"enabled":true}""",
        )

        assertEquals("count=3;enabled=true", result)
        assertTrue(result.contains("count=3"))
        val exposed = registry.listToolsAsToolDefinitions()
            .first { it.name == "mcp_test__typed" }
        assertTrue(exposed.parametersJsonSchema.contains("\"items\""))
    }

    @Test
    fun `nested object passed as escaped JSON string is decoded for the tool`() = runBlocking {
        // 回归：render_data 这类工具需要嵌套对象参数，但 schema 常被模型按 string 传成
        // 转义 JSON（"{\"labels\":[\"a\"]}"）。旧实现用 toString().trim('"') 只剥最外层引号，
        // 内层 \" 转义残留导致解析失败（报“data 必须是 JSON 对象”）。
        val registry = ToolRegistry(ApplicationProvider.getApplicationContext<Context>())
        registry.register(
            ToolRegistry.ToolDef(
                name = "nested_object_probe",
                description = "probe",
                parameters = mapOf("data" to "object payload"),
                required = setOf("data"),
                parameterTypes = mapOf("data" to "object"),
            ),
        ) { args ->
            // 工具内部像 render_data 那样把 data 当 JSON 解析，成功即返回解出的 labels 数量。
            val parsed = io.zer0.common.AppJson.parseToJsonElement(args["data"].orEmpty())
            val labels = (parsed as? kotlinx.serialization.json.JsonObject)?.get("labels")
            val count = (labels as? kotlinx.serialization.json.JsonArray)?.size ?: -1
            "ok:$count"
        }

        val escaped = """{"data":"{\"labels\":[\"a\",\"b\",\"c\"],\"values\":[1,2,3]}"}"""
        assertEquals("ok:3", registry.executeFromJson("nested_object_probe", escaped))

        // 直接传对象也照样可用。
        val direct = """{"data":{"labels":["x"],"values":[1]}}"""
        assertEquals("ok:1", registry.executeFromJson("nested_object_probe", direct))
    }
}
