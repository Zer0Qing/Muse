package io.zer0.muse.tools

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.data.knowledge.KnowledgeDocEntity
import io.zer0.muse.data.session.MuseDb
import kotlinx.coroutines.runBlocking
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

    @Test
    fun includeInternalSearchReturnsSeededInternalDocument() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, MuseDb::class.java).build()
        try {
            db.knowledgeDocDao().upsert(
                KnowledgeDocEntity(
                    id = "devdoc-agent",
                    title = "Agent capabilities",
                    content = "virtual display recovery",
                    fileType = "devdoc",
                    isInternal = true,
                ),
            )
            val tools = SkillSearchToolsImpl(
                context = context,
                client = OkHttpClient(),
                webSearchService = null,
                knowledgeDocDao = db.knowledgeDocDao(),
                ragService = null,
            )

            val result = tools.execKnowledgeSearch(
                mapOf("query" to "virtual", "include_internal" to "true"),
            )

            assertTrue(result.contains("Agent capabilities"))
        } finally {
            db.close()
        }
    }
}
