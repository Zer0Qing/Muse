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
class NetworkTextToolsRegistrarTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun register_registersNetworkTextTools() {
        val registry = ToolRegistry(context)
        NetworkTextToolsRegistrar(context, registry)
        val names = registry.listTools().map { it.name }
        listOf("ping_host", "dns_lookup", "get_public_ip", "json_pretty", "generate_password")
            .forEach { name -> assertTrue("missing $name", name in names) }
    }

    @Test
    fun execute_jsonPrettyFormatsJson() = runBlocking {
        val registry = ToolRegistry(context)
        NetworkTextToolsRegistrar(context, registry)
        val outcome = registry.execute("json_pretty", mapOf("json" to """{"a":1,"b":[true,null]}"""))
        assertFalse(outcome.isError)
        assertTrue(outcome.content.contains("\"a\""))
    }

    @Test
    fun execute_generatePasswordHonorsLength() = runBlocking {
        val registry = ToolRegistry(context)
        NetworkTextToolsRegistrar(context, registry)
        val outcome = registry.execute("generate_password", mapOf("length" to "12"))
        assertFalse(outcome.isError)
        assertTrue(outcome.content.contains("12"))
    }
}
