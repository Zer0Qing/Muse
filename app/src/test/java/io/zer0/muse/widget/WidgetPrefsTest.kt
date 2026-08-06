package io.zer0.muse.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R-TEST-20: widget 偏好存取测试。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class WidgetPrefsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `chat widget assistant round trips`() {
        WidgetPrefs.saveChatWidgetAssistant(context, appWidgetId = 42, assistantId = "assistant-1")
        assertEquals("assistant-1", WidgetPrefs.getChatWidgetAssistant(context, appWidgetId = 42))
    }

    @Test
    fun `quick widget assistant round trips`() {
        WidgetPrefs.saveQuickWidgetAssistant(context, appWidgetId = 7, assistantId = "assistant-2")
        assertEquals("assistant-2", WidgetPrefs.getQuickWidgetAssistant(context, appWidgetId = 7))
    }

    @Test
    fun `chat widget assistant defaults to default`() {
        assertEquals("default", WidgetPrefs.getChatWidgetAssistant(context, appWidgetId = 999))
    }

    @Test
    fun `quick widget assistant defaults to default`() {
        assertEquals("default", WidgetPrefs.getQuickWidgetAssistant(context, appWidgetId = 999))
    }
}
