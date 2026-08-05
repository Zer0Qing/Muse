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
class ScheduledTaskToolsRegistrarTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun register_registersScheduledTaskTools() {
        val registry = ToolRegistry(context)
        ScheduledTaskToolsRegistrar(context, registry)
        val names = registry.listTools().map { it.name }
        listOf(
            "scheduled_task_create", "scheduled_task_list", "scheduled_task_update",
            "scheduled_task_delete", "scheduled_task_execute", "scheduled_task_get_history",
        ).forEach { name -> assertTrue("missing $name", name in names) }
    }
}
