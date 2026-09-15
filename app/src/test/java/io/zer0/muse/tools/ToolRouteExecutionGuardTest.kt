package io.zer0.muse.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.zer0.ai.core.ToolDefinition
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ToolRouteExecutionGuardTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `unregistered tool is rejected before execution`() = runBlocking {
        val registry = ToolRegistry(context)
        val result = ToolRouteExecutionGuard(registry).executeFromJson("removed_mcp", "{}")
        assertTrue(result.contains("不存在或已注销"))
    }

    @Test
    fun `snapshot still exposes local route`() {
        val snapshot = RouteTable.snapshot(
            listOf(ToolDefinition("echo", "echo", "{\"type\":\"object\"}")),
            emptyList(),
        )
        assertTrue(snapshot.isExposed("echo"))
    }

    @Test
    fun `generated schema for built in tool remains executable`() = runBlocking {
        val registry = ToolRegistry(context)
        registry.register(
            ToolRegistry.ToolDef(
                name = "calendar_like",
                description = "calendar-like test tool",
                parameters = mapOf("title" to "event title"),
                required = setOf("title"),
            ),
        ) { args: Map<String, String> -> "executed:${args["title"]}" }

        val snapshot = RouteTable.snapshot(registry.listToolsAsToolDefinitions(), emptyList())
        val result = ToolRouteExecutionGuard(registry).executeFromJson(
            name = "calendar_like",
            argumentsJson = "{\"title\":\"demo\"}",
            snapshot = snapshot,
        )

        assertEquals("executed:demo", result)
    }
}
