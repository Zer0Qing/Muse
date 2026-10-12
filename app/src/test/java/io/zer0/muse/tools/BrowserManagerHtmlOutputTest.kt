package io.zer0.muse.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BrowserManagerHtmlOutputTest {

    @Test
    fun `evaluated html larger than fifty kilobytes remains complete`() {
        val manager = BrowserManager(ApplicationProvider.getApplicationContext<Context>())
        val html = "<article>${"page-content".repeat(8_000)}</article>"

        manager.updateCurrentHtmlFromEvaluation(JsonPrimitive(html).toString())

        assertEquals(html, manager.currentHtml.value)
    }
}
