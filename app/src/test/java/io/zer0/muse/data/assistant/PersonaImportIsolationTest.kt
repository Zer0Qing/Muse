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
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * v2.7.x(E3): 导入的隔离性与「不自动切换」。
 *
 * 计划里 E3 的三条硬要求 —— 严格 schema、按助手隔离、不自动切换 —— 都是
 * "导入之后没有发生的事情"。没有测试盯着,未来的重构很容易顺手加一句
 * `saveDefaultAssistantId(...)`,那在功能上"更贴心",在语义上是越权。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [33])
class PersonaImportIsolationTest {

    private fun writeZip(target: File, entries: Map<String, String>) {
        ZipOutputStream(target.outputStream()).use { zos ->
            entries.forEach { (name, content) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
    }

    @Test
    fun `新格式人设卡导入后是一条全新的助手`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = mockk<AssistantRepository>()
        coEvery { repository.upsert(any()) } just runs

        val card = PersonaCard(name = "阿澈", systemPrompt = "少说多做", memoryEnabled = true)
        val zip = File.createTempFile("persona_", ".muse-assistant", context.cacheDir)
        writeZip(zip, mapOf("persona.json" to PersonaCardCodec.encode(card)))

        val imported = AssistantCardExporter.import(context, repository, Uri.fromFile(zip))
        assertNotNull(imported)
        imported!!
        assertTrue("id 必须是新生成的", imported.id.isNotBlank())
        assertEquals("阿澈", imported.name)
        assertEquals("少说多做", imported.systemPrompt)
        // 按助手隔离的另一半:新建的人设不继承任何 provider/模型引用,
        // 因此它不会一开始就读到某个人的凭据。
        assertNull(imported.providerId)
        assertNull(imported.modelId)
        assertEquals("{}", imported.customHeadersJson)
        coVerify(exactly = 1) { repository.upsert(imported) }
    }

    @Test
    fun `旧版整份实体包仍可导入但只取人设`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = mockk<AssistantRepository>()
        coEvery { repository.upsert(any()) } just runs

        // 用真实的序列化产物造旧包:手写 JSON 会踩到「customHeadersJson 是字符串而不是对象」
        // 这类形状细节,测出来的就不是历史格式了(第一版就是这么红了一次)。
        val legacyEntity = AssistantEntity(
            id = "old-id",
            name = "旧卡",
            systemPrompt = "旧设定",
            customHeadersJson = """{"Authorization":"Bearer sk-old-leak"}""",
            providerId = "prov-old",
            modelId = "model-old",
            messageCount = 777,
        )
        val zip = File.createTempFile("legacy_persona_", ".muse-assistant", context.cacheDir)
        writeZip(
            zip,
            mapOf("assistant.json" to io.zer0.common.AppJson.encodeToString(AssistantEntity.serializer(), legacyEntity)),
        )

        val parsed = AssistantCardExporter.parse(context, Uri.fromFile(zip))
        assertNotNull("旧包不该被拒,它只是要降级成人设", parsed)
        parsed!!
        assertTrue("要标成旧包,预览界面据此说明取舍", parsed.legacy)
        assertEquals("旧设定", parsed.entity.systemPrompt)
        assertEquals("{}", parsed.entity.customHeadersJson)
        assertNull(parsed.entity.providerId)
        assertEquals(0, parsed.entity.messageCount)
    }

    @Test
    fun `未知字段与错 kind 的包整体被拒而不是部分导入`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = mockk<AssistantRepository>()

        val zip = File.createTempFile("bad_persona_", ".muse-assistant", context.cacheDir)
        writeZip(
            zip,
            mapOf("persona.json" to """{"schemaVersion":1,"kind":"muse.persona","name":"甲","unexpected":"x"}"""),
        )

        assertNull(AssistantCardExporter.parse(context, Uri.fromFile(zip)))
        coVerify(exactly = 0) { repository.upsert(any()) }
    }

    @Test
    fun `导入路径不碰默认助手与会话归属`() {
        // 源码级断言(与本项目其它 wiring 测试同一手法):预览确认后只允许 commitParsed,
        // 不许出现切换当前助手 / 改默认指针 / 改会话归属的调用。
        val source = File("src/main/java/io/zer0/muse/ui/AssistantScreen.kt").readText()

        val start = source.indexOf("// v2.x: 导入预览确认对话框")
        assertTrue("找不到导入预览对话框", start > 0)
        // 取「从注释到本函数结束」这一段:第一个顶格的 `}` 就是 AssistantScreen 的右括号。
        val end = source.indexOf("\n}\n", start)
        assertTrue("导入对话框后面找不到函数结尾,断言范围不成立", end > start)
        val block = source.substring(start, end)

        assertTrue("确认按钮必须走 commitParsed", block.contains("commitParsed(context, repo, target)"))
        for (forbidden in listOf("saveDefaultAssistantId", "setSessionAssistant", "setCurrentAssistant")) {
            assertTrue("$forbidden 不该出现在导入确认这一段里", !block.contains(forbidden))
        }
        // 预览必须说差分与「不切换」,否则用户只有点下去才知道会不会影响在用助手。
        assertTrue("预览要列出差分项", block.contains("PersonaDiff.changes"))
        assertTrue("预览要说明不会切换助手", block.contains("assistant_diff_no_switch"))
        assertTrue("旧包要在预览里点名", block.contains("assistant_diff_legacy_pack"))
    }
}

/**
 * E3 文案的七语言契约。漏一个键不会编译失败,只会在那种语言下显示成空或别的语言。
 */
class PersonaStringsTest {

    private val locales = listOf("values", "values-en", "values-es", "values-ja", "values-ko", "values-pt-rBR", "values-ru")

    private val keys = listOf(
        "assistant_diff_name", "assistant_diff_summary", "assistant_diff_system_prompt",
        "assistant_diff_persona_definition", "assistant_diff_thinking_chain", "assistant_diff_message_template",
        "assistant_diff_preset_messages", "assistant_diff_temperature", "assistant_diff_top_p",
        "assistant_diff_max_tokens", "assistant_diff_context_size", "assistant_diff_reasoning_level",
        "assistant_diff_memory_enabled", "assistant_diff_use_global_memory", "assistant_diff_allow_group_chat",
        "assistant_diff_will_create", "assistant_diff_will_change", "assistant_diff_legacy_pack",
        "assistant_diff_no_switch", "assistant_diff_more",
    )

    private fun read(locale: String): String {
        val candidates = listOf(
            File("app/src/main/res/$locale/strings_ui_viewmodel.xml"),
            File("src/main/res/$locale/strings_ui_viewmodel.xml"),
        )
        val file = candidates.firstOrNull { it.exists() } ?: error("找不到 $locale 的人设文案文件")
        return file.readText()
    }

    @Test
    fun `七语言都有全套差分文案`() {
        locales.forEach { locale ->
            val text = read(locale)
            keys.forEach { key ->
                val value = Regex("""<string name="$key">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
                    .find(text)?.groupValues?.getOrNull(1)
                assertTrue("$locale 缺 $key", value != null && value.isNotBlank())
            }
        }
    }

    @Test
    fun `带参数的两条文案占位符齐全`() {
        locales.forEach { locale ->
            val text = read(locale)
            val change = Regex("""<string name="assistant_diff_will_change">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
                .find(text)!!.groupValues[1]
            assertTrue("$locale 的 will_change 缺 %1\$d", change.contains("%1\$d"))
            val more = Regex("""<string name="assistant_diff_more">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
                .find(text)!!.groupValues[1]
            assertTrue("$locale 的 more 缺 %1\$d", more.contains("%1\$d"))
        }
    }
}
