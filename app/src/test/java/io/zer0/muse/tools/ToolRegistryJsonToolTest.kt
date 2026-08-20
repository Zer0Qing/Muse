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
}
