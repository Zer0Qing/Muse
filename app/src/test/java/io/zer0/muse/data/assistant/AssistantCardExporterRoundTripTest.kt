package io.zer0.muse.data.assistant

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
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
@Config(application = android.app.Application::class, sdk = [33])
class AssistantCardExporterRoundTripTest {

    /**
     * E3 改了这条测试的期望,理由要留在这里:
     *
     * 以前它断言 `toolIdsJson` 与 `customHeadersJson` **会**随角色卡往返 ——
     * 而 `customHeadersJson` 的常见用法就是放 Authorization 头,角色卡是要转发给别人的。
     * 一条把泄漏固化下来的测试比没有测试更坏:它会让未来的修复看起来像"改坏了功能"。
     * 现在期望换成:人设过去,凭据与设备本地绑定留在原地。
     */
    @Test
    fun `export then import carries the persona and leaves credentials behind`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = mockk<AssistantRepository>()
        coEvery { repository.upsert(any()) } just runs

        val source = AssistantEntity(
            id = "orig-id",
            name = "导出助手",
            systemPrompt = "角色设定",
            personaDefinition = "冷静、爱较真",
            toolIdsJson = """["web_search"]""",
            customHeadersJson = """{"Authorization":"Bearer sk-leak-me"}""",
            providerId = "prov-local",
            modelId = "model-local",
            messageCount = 421,
        )
        val zip = File.createTempFile("assistant_card_", ".muse-assistant", context.cacheDir)
        AssistantCardExporter.export(context, source, Uri.fromFile(zip))

        val bytes = zip.readBytes()
        val visible = String(bytes, Charsets.ISO_8859_1)
        assertTrue("zip 里不该出现凭据", !visible.contains("sk-leak-me"))
        assertTrue("zip 里不该出现本机 provider id", !visible.contains("prov-local"))

        val imported = AssistantCardExporter.import(context, repository, Uri.fromFile(zip))
        assertNotNull(imported)
        imported!!
        assertNotEquals("orig-id", imported.id)
        assertTrue(imported.id.isNotBlank())
        assertEquals("导出助手", imported.name)
        assertEquals("角色设定", imported.systemPrompt)
        assertEquals("冷静、爱较真", imported.personaDefinition)

        // 凭据与设备本地引用一律回落到默认值:导入别人的人设不该改变"它用哪个模型、带哪个头"。
        assertEquals("[]", imported.toolIdsJson)
        assertEquals("{}", imported.customHeadersJson)
        assertNull(imported.providerId)
        assertNull(imported.modelId)
        assertEquals("使用统计也不该跟着卡走", 0, imported.messageCount)
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
