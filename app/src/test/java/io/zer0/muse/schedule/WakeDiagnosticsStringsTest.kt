package io.zer0.muse.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * 后台唤醒诊断的文案资源一致性。
 *
 * 这个面板的文案分布在 7 个语言包(默认 + 6 个 locale)。漏一个键不会编译失败,
 * 只会在那种语言下露出别的语言或空字符串,所以用测试钉住三件事:
 *  1. 键集合在各语言包里一致;
 *  2. 占位符类型(`%1$s` / `%1$d`)与默认包一致,避免运行时 `IllegalFormatConversionException`;
 *  3. 状态名不翻译 —— 用户和开发者搜索、反馈时用的就是 `WorkInfo.State` 的英文名。
 *
 * 键名与枚举的对应关系也被钉住:新增一个状态或任务类型时,如果不补文案,这里先失败。
 * 本测试做过变异自检:故意把某个键改名后`every locale carries the same wake keys`会准确报出语言包与键名。
 */
class WakeDiagnosticsStringsTest {

    private val locales = listOf(
        "values",
        "values-en",
        "values-ja",
        "values-ko",
        "values-ru",
        "values-es",
        "values-pt-rBR",
    )

    private fun wakeStrings(locale: String): Map<String, String> {
        // 单测工作目录是模块目录,但历史上有按仓库根跑的用法,两条路都试。
        val candidates = listOf(
            Path.of("app/src/main/res/$locale/strings_features.xml"),
            Path.of("src/main/res/$locale/strings_features.xml"),
        )
        val file = candidates.firstOrNull(Files::exists)
            ?: error("找不到 $locale 的 strings_features.xml,查找过 $candidates")
        val text = Files.newBufferedReader(file).use { it.readText() }
        val matches = WAKE_STRING.findAll(text).toList()
        val keys = matches.map { it.groupValues[1] }
        assertEquals("$locale 有重复的 wake_* 键", keys.distinct().size, keys.size)
        return matches.associate { it.groupValues[1] to it.groupValues[2] }
    }

    private fun placeholdersOf(value: String): List<String> =
        PLACEHOLDER.findAll(value).map { it.value }.sorted().toList()

    @Test
    fun `every locale carries the same wake keys`() {
        val defaultKeys = wakeStrings("values").keys
        assertTrue("默认包没有 wake_* 文案", defaultKeys.isNotEmpty())
        locales.forEach { locale ->
            assertEquals("$locale 的 wake_* 键集合与默认包不一致", defaultKeys, wakeStrings(locale).keys)
        }
    }

    @Test
    fun `placeholder types match the default locale`() {
        val defaults = wakeStrings("values")
        locales.filter { locale -> locale != "values" }.forEach { locale ->
            val localized = wakeStrings(locale)
            defaults.forEach { (key, value) ->
                assertEquals(
                    "$locale 的 $key 占位符与默认包不一致",
                    placeholdersOf(value),
                    placeholdersOf(localized.getValue(key)),
                )
            }
        }
    }

    @Test
    fun `state names stay untranslated in every locale`() {
        // 状态名与 adb 输出、系统日志里的写法一致,翻译后反而搜不到。
        val expected = WakeState.values().associate { state -> "wake_state_${state.name.lowercase()}" to state.name }
        locales.forEach { locale ->
            val strings = wakeStrings(locale)
            expected.forEach { (key, value) ->
                assertEquals("$locale 的 $key 必须保持英文原文(WorkInfo.State 名)", value, strings.getValue(key))
            }
        }
    }

    @Test
    fun `state kind and stop reason keys follow the enums`() {
        val defaults = wakeStrings("values")
        WakeState.values().forEach { state ->
            assertTrue("缺少状态文案 wake_state_${state.name.lowercase()}", defaults.containsKey("wake_state_${state.name.lowercase()}"))
        }
        WakeWorkKind.values().forEach { kind ->
            assertTrue("缺少任务类型文案 wake_kind_${kind.name.lowercase()}", defaults.containsKey("wake_kind_${kind.name.lowercase()}"))
        }
        // 每个停跑原因都要有一句给用户看的话,不多不少。
        assertEquals(
            "停跑原因文案数量与 WakeStopReason 枚举不一致",
            WakeStopReason.values().size,
            defaults.keys.count { key -> key.startsWith("wake_stop_") },
        )
    }

    @Test
    fun `panel chrome keys exist so an empty block cannot pass`() {
        val defaults = wakeStrings("values")
        listOf(
            "wake_diagnostics_title",
            "wake_diagnostics_copy",
            "wake_diagnostics_next_run",
            "wake_diagnostics_stop_reason",
        ).forEach { key ->
            assertTrue("缺少面板文案 $key", defaults.containsKey(key))
        }
    }

    private companion object {
        val WAKE_STRING = Regex("""<string name="(wake_[a-z_]+)"[^>]*>([^<]*)</string>""")
        val PLACEHOLDER = Regex("""%\d+\$[sd]""")
    }
}
