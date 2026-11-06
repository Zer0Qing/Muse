package io.zer0.muse.data.plugin.market

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v2.7.x(PL3): 市场版本建议的接线与「不许动的东西」。
 *
 * 三条线各自对应一种真实风险:
 *  1. 列表行与详情面板必须共用同一个判定 —— 两处各比一次版本,迟早给出两个答案;
 *  2. 七语言文案不能漏 —— 漏了不会编译失败,只会在那种语言下显示空或别的语言;
 *  3. **目录条目的字段不许随便加**:`PluginCatalog.canonicalPayload` 是把整份目录
 *     用 `AppJson`(`encodeDefaults = true`)再序列化当签名载荷,加一个字段就会让
 *     现存每一份已签名目录验签失败。这条用测试钉住,免得下一个人"顺手加个 versions[]"。
 */
class PluginMarketVersionsUiTest {

    private val page: String by lazy { readSource("ui/settings/PluginManagePage.kt") }

    private val catalog: String by lazy { readSource("data/plugin/market/PluginCatalog.kt") }

    /** 单测的工作目录历史上有两种跑法(模块目录 / 仓库根),两条路径都试,找不到就明确报错。 */
    private fun readSource(relativeUnderPluginUi: String): String {
        val candidates = listOf(
            File("app/src/main/java/io/zer0/muse/" + relativeUnderPluginUi),
            File("src/main/java/io/zer0/muse/" + relativeUnderPluginUi),
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("找不到 $relativeUnderPluginUi，查找过 $candidates")
        return file.readText()
    }

    @Test
    fun `列表行与详情面板用的是同一个判定`() {
        assertTrue("列表行应传 advice", page.contains("advice = versionView.advice"))
        assertTrue("详情面板应接 versionView", page.contains("versionView = PluginMarketVersions.view("))
        assertTrue("可切回版本来自本机留档而不是目录", page.contains("pluginManager.listRetainedVersions("))
        assertTrue("App 版本取构建值", page.contains("BuildConfig.VERSION_NAME"))
    }

    @Test
    fun `每种建议都有对应文案而不是漏一个显示空白`() {
        val mapped = MarketVersionAdvice.entries.filter { page.contains("MarketVersionAdvice.${it.name}") }
        // CurrentIsLatest / InstallAvailable / UpgradeAvailable 沿用既有的已安装/安装/更新文案,
        // 其余三种必须有专门的说明。
        listOf(
            MarketVersionAdvice.UnknownVersion,
            MarketVersionAdvice.DowngradeAvailable,
            MarketVersionAdvice.NeedsNewerApp,
        ).forEach { advice ->
            assertTrue("建议 $advice 没有在界面上被说出来", mapped.contains(advice))
        }
    }

    @Test
    fun `未安装时不声称兼容`() {
        assertTrue(
            "没拿到 manifest 前只能说「下载后确认」",
            page.contains("muse_plugins_market_advice_compat_unconfirmed"),
        )
        val sheet = page.substringAfter("private fun MarketEntryDetailSheet(").substringBefore("@Composable")
        assertTrue("兼容说明不该出现在已安装分支里被当成结论", sheet.contains("else -> stringResource(R.string.muse_plugins_market_advice_compat_unconfirmed)"))
    }

    @Test
    fun `目录条目没有新增会改变签名字节的字段`() {
        // canonicalPayload = AppJson(encodeDefaults=true).encodeToString(整份 PluginCatalog)
        // → 条目加字段 = 改签名字节 = 现存目录全部验签失败。
        assertTrue("canonicalPayload 仍然序列化整份目录", catalog.contains("AppJson.encodeToString(\n        PluginCatalog.serializer(),"))
        val entryBlock = catalog.substringAfter("data class PluginCatalogEntry(").substringBefore(")")
        assertFalse("条目里出现了 versions 字段:要先做签名三端对账(PL2)", entryBlock.contains("versions"))
        assertTrue("schemaVersion 仍然是 1", catalog.contains("schemaVersion != 1"))
    }

    @Test
    fun `五条新文案在七个语言包都齐全`() {
        val keys = listOf(
            "muse_plugins_market_advice_downgrade",
            "muse_plugins_market_advice_unknown_version",
            "muse_plugins_market_advice_needs_newer_app",
            "muse_plugins_market_advice_compat_unconfirmed",
            "muse_plugins_market_advice_retained",
        )
        val locales = listOf("values", "values-en", "values-es", "values-ja", "values-ko", "values-pt-rBR", "values-ru")
        locales.forEach { locale ->
            val file = listOf(
                File("app/src/main/res/$locale/strings_ui_main.xml"),
                File("src/main/res/$locale/strings_ui_main.xml"),
            ).firstOrNull { it.exists() } ?: error("找不到 $locale 的 strings_ui_main.xml")
            val text = file.readText()
            keys.forEach { key ->
                val value = Regex(
                    """<string name="$key">(.*?)</string>""",
                    RegexOption.DOT_MATCHES_ALL,
                ).find(text)?.groupValues?.getOrNull(1)
                assertTrue("$locale 缺 $key", value != null && value.isNotBlank())
            }
            val retained = Regex("""<string name="muse_plugins_market_advice_retained">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
                .find(text)!!.groupValues[1]
            // 字面量是 %1$s。用 '$' 字符拼出来，免得模板转义把断言自己写错(已经错过一次)。
            assertTrue("$locale 的存档说明带位置参数", retained.contains("%1" + '$' + "s"))
        }
    }
}
