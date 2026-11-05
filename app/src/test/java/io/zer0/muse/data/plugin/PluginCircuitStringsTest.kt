package io.zer0.muse.data.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * PL1 收尾:插件页熔断文案的七语言契约。
 *
 * 漏一个语言包不会编译失败,只会让某种语言下那行状态显示成别的语言或空白;
 * 占位符类型写错(`%1$s` 与 `%1$d` 互换)更糟 —— 运行时直接抛
 * `IllegalFormatConversionException`,而单测里没有 Android 资源系统,抓不到。
 * 所以这里按文件读原文钉三件事:键齐全、占位符同型、页面确实在引用这两个键。
 */
class PluginCircuitStringsTest {

    private val locales = listOf(
        "values",
        "values-en",
        "values-es",
        "values-ja",
        "values-ko",
        "values-pt-rBR",
        "values-ru",
    )

    private val circuitKeys = listOf("plugin_row_circuit_cooling", "plugin_row_circuit_probing")

    private fun circuitStrings(locale: String): Map<String, String> {
        val candidates = listOf(
            Path.of("app/src/main/res/$locale/strings_features.xml"),
            Path.of("src/main/res/$locale/strings_features.xml"),
        )
        val file = candidates.firstOrNull(Files::exists)
            ?: error("找不到 $locale 的 strings_features.xml,查找过 $candidates")
        val text = Files.newBufferedReader(file).use { it.readText() }
        return circuitKeys.associateWith { key ->
            val match = Regex("""<string name="$key">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).find(text)
                ?: error("$locale 缺文案 $key —— 缺了那行状态在这种语言下什么都不显示")
            match.groupValues[1]
        }
    }

    @Test
    fun `七语言都带熔断文案且不为空`() {
        locales.forEach { locale ->
            circuitStrings(locale).forEach { (key, value) ->
                assertTrue("$locale 的 $key 是空文案", value.isNotBlank())
            }
        }
    }

    @Test
    fun `冷却文案只接受一个整数占位符`() {
        val defaults = circuitStrings("values").getValue("plugin_row_circuit_cooling")
        assertEquals("默认包的冷却文案应只有一个 %1\$d", listOf("%1\$d"), placeholdersOf(defaults))
        locales.forEach { locale ->
            assertEquals(
                "$locale 的冷却文案占位符与默认包不一致,运行时会抛 IllegalFormatConversionException",
                placeholdersOf(defaults),
                placeholdersOf(circuitStrings(locale).getValue("plugin_row_circuit_cooling")),
            )
        }
        assertEquals("半开文案不该带任何占位符", emptyList<String>(), placeholdersOf(circuitStrings("values").getValue("plugin_row_circuit_probing")))
    }

    @Test
    fun `插件页确实在引用这两个键`() {
        val candidates = listOf(
            Path.of("app/src/main/java/io/zer0/muse/ui/settings/PluginManagePage.kt"),
            Path.of("src/main/java/io/zer0/muse/ui/settings/PluginManagePage.kt"),
        )
        val file = candidates.firstOrNull(Files::exists) ?: error("找不到 PluginManagePage.kt")
        val source = Files.newBufferedReader(file).use { it.readText() }
        circuitKeys.forEach { key ->
            assertTrue("插件页没引用 R.string.$key —— 文案存在但没人显示,等于没有", source.contains("R.string.$key"))
        }
        assertTrue("展示走的是纯读的 circuitView,不是会改状态的 blockReason", source.contains("circuitView(plugin.id)"))
    }

    private fun placeholdersOf(value: String): List<String> = Regex("""%\d+\$[a-zA-Z]""").findAll(value).map { it.value }.sorted().toList()
}
