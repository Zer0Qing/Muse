package io.zer0.muse.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TtsToolsRegistrarTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun register_registersSpeakText() {
        val registry = ToolRegistry(context)
        TtsToolsRegistrar(context, registry)
        val names = registry.listTools().map { it.name }
        assertTrue("missing speak_text", "speak_text" in names)
    }
}
