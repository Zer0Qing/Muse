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
class ToolExecutionContextTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun contextToolRequiresHostContext() = runBlocking {
        val registry = ToolRegistry(context)
        registry.registerWithContext(
            ToolRegistry.ToolDef(
                name = "context_probe",
                description = "test",
                parameters = mapOf("value" to "value"),
            ),
        ) { args, executionContext ->
            "${executionContext.scope}/${executionContext.spaceId}:${args["value"]}"
        }

        val denied = registry.execute("context_probe", mapOf("value" to "x"))
        assertTrue(denied.isError)
        assertTrue(denied.content.contains("执行上下文"))

        val allowed = registry.execute(
            "context_probe",
            mapOf("value" to "x"),
            ToolExecutionContext(scope = "assistant-1", spaceId = "work"),
        )
        assertEquals("assistant-1/work:x", allowed.content)
    }

    @Test
    fun jsonContextIsPassedWithoutModelArguments() = runBlocking {
        val registry = ToolRegistry(context)
        registry.registerWithContext(
            ToolRegistry.ToolDef("context_probe", "test", mapOf("value" to "value")),
        ) { args, executionContext ->
            "${executionContext.scope}/${executionContext.spaceId}:${args["value"]}"
        }

        val result = registry.executeFromJson(
            "context_probe",
            "{\"value\":\"x\",\"scope\":\"forged\",\"spaceId\":\"forged\"}",
            ToolExecutionContext(scope = "main", spaceId = "default"),
        )

        assertEquals("main/default:x", result)
    }
}
