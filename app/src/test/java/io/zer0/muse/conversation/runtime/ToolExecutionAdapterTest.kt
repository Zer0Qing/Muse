package io.zer0.muse.conversation.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.conversation.core.ToolInvocation
import io.zer0.muse.conversation.core.ToolOutcome
import io.zer0.muse.conversation.core.ToolRisk
import io.zer0.muse.tools.ToolOutcome as RegistryOutcome
import io.zer0.muse.tools.ToolRegistry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ToolExecutionAdapterTest {
    @Test
    fun `successful registry output maps to domain success`() = runTest {
        val executor = ToolExecutionAdapter(registryWith(output = "done"))

        val result = executor.execute(invocation("{\"text\":\"value\"}"))

        assertEquals(ToolOutcome.Succeeded("done"), result)
    }

    @Test
    fun `registry errors remain failures`() = runTest {
        val executor = ToolExecutionAdapter(registryWith(error = true))

        val result = executor.execute(invocation("{\"text\":\"value\"}"))

        assertEquals(ToolOutcome.Failed("bad"), result)
    }

    @Test
    fun `registry risk maps without weakening`() {
        val registry = ToolRegistry(ApplicationProvider.getApplicationContext<Context>())
        val adapter = ToolExecutionAdapter(registry)

        assertEquals(ToolRisk.High, adapter.riskOf("execute_javascript"))
    }

    private fun invocation(arguments: String) =
        ToolInvocation(
            toolCallId = "call-1",
            toolName = "test",
            argumentsJson = arguments,
            sideEffectUnknown = false,
        )

    private fun registryWith(error: Boolean = false, output: String = "done"): ToolRegistry {
        val registry = ToolRegistry(ApplicationProvider.getApplicationContext<Context>())
        val def = ToolRegistry.ToolDef(
            name = "test",
            description = "test",
            parameters = mapOf("text" to "text"),
            required = setOf("text"),
            riskLevel = io.zer0.muse.tools.ToolRiskLevel.NORMAL,
        )
        registry.registerOutcome(def) {
            if (error) RegistryOutcome.error("bad") else RegistryOutcome.ok(output)
        }
        return registry
    }
}
