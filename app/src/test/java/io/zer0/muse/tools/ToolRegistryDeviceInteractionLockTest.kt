package io.zer0.muse.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ToolRegistryDeviceInteractionLockTest {
    @Test
    fun `agent holds exclusive device session against standalone screen tools`() = runTest {
        val registry = ToolRegistry(
            context = ApplicationProvider.getApplicationContext<Context>(),
            browserManager = mockk<BrowserManager>(relaxed = true),
        )
        val agentEntered = CompletableDeferred<Unit>()
        val releaseAgent = CompletableDeferred<Unit>()
        val screenToolEntered = CompletableDeferred<Unit>()

        registry.register(
            ToolRegistry.ToolDef("ui_agent", "agent", mapOf("task" to "task"), required = setOf("task"), riskLevel = ToolRiskLevel.HIGH),
        ) {
            agentEntered.complete(Unit)
            releaseAgent.await()
            "agent done"
        }
        registry.register(
            ToolRegistry.ToolDef(
                "screen_tap",
                "tap",
                mapOf("x" to "x", "y" to "y"),
                required = setOf("x", "y"),
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) {
            screenToolEntered.complete(Unit)
            "tap done"
        }

        val agentCall = async { registry.executeFromJson("ui_agent", """{"task":"tap settings"}""") }
        withTimeout(1_000L) { agentEntered.await() }
        val screenCall = async { registry.executeFromJson("screen_tap", """{"x":10,"y":20}""") }
        yield()
        assertFalse("screen action must wait until the Agent's observe-act loop ends", screenToolEntered.isCompleted)

        releaseAgent.complete(Unit)
        withTimeout(1_000L) { agentCall.await() }
        withTimeout(1_000L) { screenCall.await() }
        assertTrue(screenToolEntered.isCompleted)
    }

    @Test
    fun `future screen and UI tool names inherit device serialization`() {
        assertTrue(ToolRegistry.requiresDeviceInteractionLock("screen_future_action"))
        assertTrue(ToolRegistry.requiresDeviceInteractionLock("ui_future_action"))
        assertFalse(ToolRegistry.requiresDeviceInteractionLock("safe_future_action"))
    }

    @Test
    fun `JSON virtual-screen route shares the same device interaction lock`() = runTest {
        val registry = ToolRegistry(
            context = ApplicationProvider.getApplicationContext<Context>(),
            browserManager = mockk<BrowserManager>(relaxed = true),
        )
        val virtualScreenEntered = CompletableDeferred<Unit>()
        val releaseVirtualScreen = CompletableDeferred<Unit>()
        val screenToolEntered = CompletableDeferred<Unit>()
        registry.registerJson(
            ToolRegistry.ToolDef(
                name = "virtual_screen",
                description = "virtual display",
                parameters = emptyMap(),
                riskLevel = ToolRiskLevel.HIGH,
                rawParametersJsonSchema = """{"type":"object","properties":{},"additionalProperties":false}""",
            ),
        ) {
            virtualScreenEntered.complete(Unit)
            releaseVirtualScreen.await()
            "virtual display ready"
        }
        registry.register(
            ToolRegistry.ToolDef(
                "screen_tap",
                "tap",
                mapOf("x" to "x", "y" to "y"),
                required = setOf("x", "y"),
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) {
            screenToolEntered.complete(Unit)
            "tap done"
        }

        val virtualScreenCall = async { registry.executeFromJson("virtual_screen", "{}") }
        withTimeout(1_000L) { virtualScreenEntered.await() }
        val screenCall = async { registry.executeFromJson("screen_tap", """{"x":10,"y":20}""") }
        yield()
        assertFalse("JSON tool route must hold the same device lock", screenToolEntered.isCompleted)

        releaseVirtualScreen.complete(Unit)
        withTimeout(1_000L) { virtualScreenCall.await() }
        withTimeout(1_000L) { screenCall.await() }
        assertTrue(screenToolEntered.isCompleted)
    }

    @Test
    fun `unrelated safe tool remains concurrent with device interaction`() = runTest {
        val registry = ToolRegistry(
            context = ApplicationProvider.getApplicationContext<Context>(),
            browserManager = mockk<BrowserManager>(relaxed = true),
        )
        val deviceEntered = CompletableDeferred<Unit>()
        val releaseDevice = CompletableDeferred<Unit>()
        val safeToolEntered = CompletableDeferred<Unit>()

        registry.register(
            ToolRegistry.ToolDef(
                "ui_agent",
                "agent",
                mapOf("task" to "task"),
                required = setOf("task"),
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) {
            deviceEntered.complete(Unit)
            releaseDevice.await()
            "agent done"
        }
        registry.register(
            ToolRegistry.ToolDef(
                "echo_test",
                "echo",
                mapOf("value" to "value"),
                required = setOf("value"),
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) {
            safeToolEntered.complete(Unit)
            "safe done"
        }

        val deviceCall = async { registry.executeFromJson("ui_agent", """{"task":"inspect"}""") }
        withTimeout(1_000L) { deviceEntered.await() }
        val safeCall = async { registry.executeFromJson("echo_test", """{"value":"hello"}""") }
        withTimeout(1_000L) { safeToolEntered.await() }

        releaseDevice.complete(Unit)
        withTimeout(1_000L) { deviceCall.await() }
        withTimeout(1_000L) { safeCall.await() }
    }
}
