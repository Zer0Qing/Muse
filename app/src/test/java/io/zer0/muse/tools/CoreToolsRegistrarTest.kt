package io.zer0.muse.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CoreToolsRegistrarTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun register_registersCoreTools() {
        val registry = ToolRegistry(context)
        CoreToolsRegistrar(context, registry)
        val names = registry.listTools().map { it.name }
        listOf("get_current_time", "calculator", "echo").forEach { name ->
            assertTrue("missing $name", name in names)
        }
    }

    @Test
    fun execute_calculatorWorks() = runBlocking {
        val registry = ToolRegistry(context)
        CoreToolsRegistrar(context, registry)
        val outcome = registry.execute("calculator", mapOf("expression" to "1+2*3"))
        assertFalse(outcome.isError)
        assertTrue(outcome.content.contains("7"))
    }

    @Test
    fun execute_echoWorks() = runBlocking {
        val registry = ToolRegistry(context)
        CoreToolsRegistrar(context, registry)
        val outcome = registry.execute("echo", mapOf("text" to "hello"))
        assertFalse(outcome.isError)
        assertTrue(outcome.content == "hello")
    }

    @Test
    fun execute_getCurrentTimeWorks() = runBlocking {
        val registry = ToolRegistry(context)
        CoreToolsRegistrar(context, registry)
        val outcome = registry.execute("get_current_time", mapOf("timezone" to "UTC"))
        assertFalse(outcome.isError)
        assertTrue(outcome.content.isNotBlank())
    }
}
