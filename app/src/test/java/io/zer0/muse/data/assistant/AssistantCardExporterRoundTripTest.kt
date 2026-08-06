package io.zer0.muse.data.assistant

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.just
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * R-TEST-16: 助手角色卡导出/导入往返。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class AssistantCardExporterRoundTripTest {

    @Test
    fun `export then import preserves fields and regenerates id`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = mockk<AssistantRepository>()
        coEvery { repository.upsert(any()) } just runs

        val source = AssistantEntity(
            id = "orig-id",
            name = "导出助手",
            systemPrompt = "角色设定",
            toolIdsJson = """["web_search"]""",
            customHeadersJson = """{"X-Test":"1"}""",
        )
        val zip = File.createTempFile("assistant_card_", ".muse-assistant", context.cacheDir)
        AssistantCardExporter.export(context, source, Uri.fromFile(zip))

        val imported = AssistantCardExporter.import(context, repository, Uri.fromFile(zip))
        assertNotNull(imported)
        imported!!
        assertNotEquals("orig-id", imported.id)
        assertTrue(imported.id.isNotBlank())
        assertEquals("导出助手", imported.name)
        assertEquals("角色设定", imported.systemPrompt)
        assertEquals("""["web_search"]""", imported.toolIdsJson)
        assertEquals("""{"X-Test":"1"}""", imported.customHeadersJson)
        coVerify(exactly = 1) { repository.upsert(imported) }
    }

    @Test
    fun `corrupt card returns null`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = mockk<AssistantRepository>()
        val bad = File.createTempFile("bad_card_", ".muse-assistant", context.cacheDir)
        bad.writeText("not a zip")
        assertNull(AssistantCardExporter.import(context, repository, Uri.fromFile(bad)))
    }
}
