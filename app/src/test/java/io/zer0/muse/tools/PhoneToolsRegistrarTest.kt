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
class PhoneToolsRegistrarTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun register_registersPhoneTools() {
        val registry = ToolRegistry(context)
        PhoneToolsRegistrar(context, registry)
        val names = registry.listTools().map { it.name }
        listOf(
            "set_alarm", "set_timer", "open_app", "share_text", "get_location",
            "get_device_info", "get_contacts_count", "get_contacts_list",
            "send_sms", "add_contact", "make_phone_call", "open_maps",
        ).forEach { name -> assertTrue("missing $name", name in names) }
    }

    @Test
    fun execute_getDeviceInfoWorks() = runBlocking {
        val registry = ToolRegistry(context)
        PhoneToolsRegistrar(context, registry)
        val outcome = registry.execute("get_device_info", emptyMap())
        assertFalse(outcome.isError)
        assertTrue(outcome.content.isNotBlank())
    }
}
