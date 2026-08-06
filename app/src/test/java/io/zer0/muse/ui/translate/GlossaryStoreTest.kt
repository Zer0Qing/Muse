package io.zer0.muse.ui.translate

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R-TEST-20: 翻译术语表存储测试。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class GlossaryStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val store = GlossaryStore(context)

    @Test
    fun `add and list round trip`() {
        store.clear()
        store.add("API", "应用程序接口")
        assertEquals(mapOf("API" to "应用程序接口"), store.list())
    }

    @Test
    fun `add trims keys and values`() {
        store.clear()
        store.add("  GPU  ", "  图形处理器  ")
        assertEquals(mapOf("GPU" to "图形处理器"), store.list())
    }

    @Test
    fun `add ignores blank original`() {
        store.clear()
        store.add("  ", "value")
        assertTrue(store.isEmpty())
    }

    @Test
    fun `remove deletes existing entry only`() {
        store.clear()
        store.add("API", "应用程序接口")
        assertTrue(store.remove("API"))
        assertFalse(store.remove("API"))
        assertTrue(store.isEmpty())
    }

    @Test
    fun `clear empties glossary`() {
        store.clear()
        store.add("A", "B")
        store.clear()
        assertTrue(store.isEmpty())
    }

    @Test
    fun `toPromptSnippet formats entries and returns empty for empty glossary`() {
        store.clear()
        assertEquals("", store.toPromptSnippet())
        store.add("API", "应用程序接口")
        store.add("GPU", "图形处理器")
        val snippet = store.toPromptSnippet()
        assertTrue(snippet.contains("API→应用程序接口"))
        assertTrue(snippet.contains("GPU→图形处理器"))
    }
}
