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
class QuickNoteToolsRegistrarTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun register_registersQuickNoteTools() {
        val registry = ToolRegistry(context)
        QuickNoteToolsRegistrar(context, registry)
        val names = registry.listTools().map { it.name }
        listOf("quick_note_add", "quick_note_list", "quick_note_search", "quick_note_get", "quick_note_update", "quick_note_delete", "quick_note_pin")
            .forEach { name -> assertTrue("missing $name", name in names) }
    }
}
