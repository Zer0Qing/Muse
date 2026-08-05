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
class ReminderToolsRegistrarTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun register_registersReminderTools() {
        val registry = ToolRegistry(context)
        ReminderToolsRegistrar(context, registry)
        val names = registry.listTools().map { it.name }
        listOf("schedule_reminder", "cancel_reminder", "list_reminders").forEach { name ->
            assertTrue("missing $name", name in names)
        }
    }

    @Test
    fun execute_listRemindersEmptyReturnsFriendlyMessage() = runBlocking {
        val registry = ToolRegistry(context)
        ReminderToolsRegistrar(context, registry)
        val outcome = registry.execute("list_reminders", emptyMap())
        assertFalse(outcome.isError)
        assertTrue(outcome.content.isNotBlank())
    }

    @Test
    fun execute_cancelUnknownReminderReturnsNotFound() = runBlocking {
        val registry = ToolRegistry(context)
        ReminderToolsRegistrar(context, registry)
        val outcome = registry.execute("cancel_reminder", mapOf("id" to "missing-123"))
        assertFalse(outcome.isError)
        assertTrue(outcome.content.contains("missing-123"))
    }
}
