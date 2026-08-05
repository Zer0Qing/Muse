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
class ResourceToolsRegistrarTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun register_registersResourceTools() {
        val registry = ToolRegistry(context)
        ResourceToolsRegistrar(context, registry)
        val names = registry.listTools().map { it.name }
        listOf("resource_add", "resource_list", "resource_search", "resource_get", "resource_delete")
            .forEach { name -> assertTrue("missing $name", name in names) }
    }

    @Test
    fun execute_addListGetDeleteRoundTrip() = runBlocking {
        val registry = ToolRegistry(context)
        ResourceToolsRegistrar(context, registry)
        val add = registry.execute("resource_add", mapOf("title" to "测试资源", "content" to "正文"))
        assertFalse(add.isError)
        val id = add.content.substringAfterLast(" ").trim()
        val get = registry.execute("resource_get", mapOf("id" to id))
        assertFalse(get.isError)
        assertTrue(get.content.contains("测试资源"))
        val del = registry.execute("resource_delete", mapOf("id" to id))
        assertFalse(del.isError)
    }
}
