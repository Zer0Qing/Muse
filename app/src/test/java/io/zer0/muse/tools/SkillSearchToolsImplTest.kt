package io.zer0.muse.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import okhttp3.OkHttpClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SkillSearchToolsImplTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun impl() = SkillSearchToolsImpl(
        context = context,
        client = OkHttpClient(),
        webSearchService = null,
        knowledgeDocDao = null,
        ragService = null,
    )

    @Test
    fun validatePublicUrl_rejectsPrivateAndLoopback() {
        val tools = impl()
        assertFalse(tools.validatePublicUrl("http://localhost:8080/x"))
        assertFalse(tools.validatePublicUrl("http://127.0.0.1/x"))
        assertFalse(tools.validatePublicUrl("http://192.168.1.1/x"))
        assertFalse(tools.validatePublicUrl("http://10.0.0.1/x"))
        assertFalse(tools.validatePublicUrl("file:///etc/passwd"))
    }

    @Test
    fun validatePublicUrl_acceptsPublicHttps() {
        val tools = impl()
        assertTrue(tools.validatePublicUrl("https://example.com/path"))
    }
}
