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
class ClipboardToolsRegistrarTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun register_registersClipboardTools() {
        val registry = ToolRegistry(context)
        ClipboardToolsRegistrar(context, registry)
        val names = registry.listTools().map { it.name }
        assertTrue("clipboard_read" in names)
        assertTrue("clipboard_write" in names)
    }

    @Test
    fun execute_clipboardWriteReadRoundTrip() = runBlocking {
        val registry = ToolRegistry(context)
        ClipboardToolsRegistrar(context, registry)
        val write = registry.execute("clipboard_write", mapOf("text" to "muse-test-clipboard"))
        assertFalse(write.isError)

        val read = registry.execute("clipboard_read", emptyMap())
        assertFalse(read.isError)
        assertTrue(read.content.contains("muse-test-clipboard"))
        assertTrue(read.details["hasText"] == true)
        assertTrue(read.details["length"] == 19)
    }
}
