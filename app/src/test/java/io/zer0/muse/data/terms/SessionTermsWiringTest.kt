package io.zer0.muse.data.terms

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v2.7.x(E2): 术语的注入与记录挂点。
 *
 * 这两处都是"改动很小、破坏方式很隐蔽"的接线:
 *  - 记错位置(记在装配侧)不会报错,只会让次数被重复计,于是确认提示来得太早或太晚;
 *  - 子代理注入术语也不会报错,只是把只在原会话成立的解释搬去别的语境用。
 * 所以这里按本项目既有的做法做源码级断言,并且逐条说明为什么值得钉住。
 */
class SessionTermsWiringTest {

    private fun read(relativeUnderMain: String): String {
        val candidates = listOf(
            File("app/src/main/java/io/zer0/muse/" + relativeUnderMain),
            File("src/main/java/io/zer0/muse/" + relativeUnderMain),
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("找不到 $relativeUnderMain，查找过 $candidates")
        return file.readText()
    }

    @Test
    fun `候选记录在发送侧而不是装配侧`() {
        val controller = read("ui/chat/ChatGenerationController.kt")
        val start = controller.indexOf("v2.7.x(E2): 记录术语候选")
        assertTrue("找不到术语记录段", start > 0)
        val block = controller.substring(start, minOf(controller.length, start + 900))

        assertTrue("必须真的调用 extract + recordObserved", block.contains("SessionTerms.extract(text)"))
        assertTrue(block.contains("recordObserved"))
        assertTrue("空候选不写库", block.contains("if (hits.isEmpty()) return@runCatching"))
        assertTrue("失败只记日志,不影响发送", block.contains("术语候选记录失败"))

        // 装配器里不许出现记录调用:装配会被重试与 outbox 回放重复执行
        val assembler = read("transformer/SystemPromptAssembler.kt")
        assertFalse("装配器不该写术语次数", assembler.contains("recordObserved"))
    }

    @Test
    fun `术语段注入受子代理与空会话两道闸`() {
        val assembler = read("transformer/SystemPromptAssembler.kt")

        assertTrue("装配器要拿到 store", assembler.contains("sessionTermStore: io.zer0.muse.data.terms.SessionTermStore? = null"))
        assertTrue("子代理不注入", assembler.contains("if (forSubagent) \"\" else buildSessionTermsSection("))
        assertTrue("没有会话 id 就不注入", assembler.contains("if (sessionId.isNullOrBlank()) return \"\""))
        assertTrue("拼装时与其它段同样判空", assembler.contains("if (sessionTerms.isNotBlank())"))
    }

    @Test
    fun `装配器与术语页共用同一个 store 实例`() {
        val module = read("AppPromptModule.kt")

        assertTrue("store 必须是单例", module.contains("single { io.zer0.muse.data.terms.SessionTermStore("))
        assertTrue("装配器取同一个实例", module.contains("sessionTermStore = get(),"))
        // 两处各 new 一个 store 本身不会分叉数据(同一个 dao),但会让"两处看到的次数不同"这种
        // 解释成本白白留下 —— 单例是更省事也更不容易出错的形状。
        assertFalse("装配器不该自己再建一个", module.contains("sessionTermStore = io.zer0.muse.data.terms.SessionTermStore("))
    }

    @Test
    fun `页面按助手取数而不是硬塞一个会话 id`() {
        val page = read("ui/settings/SettingsSessionTermsPage.kt")

        assertTrue("按助手列两种作用域", page.contains("store.listByAssistantScope(assistantId)"))
        assertTrue("助手 id 来自设置而不是写死", page.contains("settings.defaultAssistantIdFlow.first()"))
        assertTrue("状态标签走资源", page.contains("stringResource(statusLabel(term))"))
        assertTrue("待确认优先排序", page.contains("SessionTerms.awaitingConfirmation(entity.occurrences, entity.confirmed)"))
        // 注释里允许提到 runBlocking(解释为什么不用),真正的阻塞调用不许出现
        assertFalse("页面不许在首帧做阻塞读", page.contains("runBlocking {"))
    }
}

/**
 * E2 文案的七语言契约。漏一个键不会编译失败,只会在那种语言下显示空或别的语言。
 */
class SessionTermsStringsTest {

    private val locales = listOf("values", "values-en", "values-es", "values-ja", "values-ko", "values-pt-rBR", "values-ru")

    private val keys = listOf(
        "settings_screen_session_terms", "settings_screen_session_terms_desc",
        "settings_session_terms_loading", "settings_session_terms_empty",
        "settings_session_terms_awaiting", "settings_session_terms_awaiting_hint",
        "settings_session_terms_no_meaning", "settings_session_terms_occurrences",
        "settings_session_terms_manage", "settings_session_terms_action_title",
        "settings_session_terms_action_body", "settings_session_terms_confirm",
        "settings_session_terms_promote", "settings_session_terms_toast_confirmed",
        "settings_session_terms_toast_promoted", "settings_session_terms_scope_assistant",
        "settings_session_terms_scope_confirmed", "settings_session_terms_scope_candidate",
    )

    private fun read(locale: String): String {
        val candidates = listOf(
            File("app/src/main/res/$locale/strings_settings_sub.xml"),
            File("src/main/res/$locale/strings_settings_sub.xml"),
        )
        val file = candidates.firstOrNull { it.exists() } ?: error("找不到 $locale 的 strings_settings_sub.xml")
        return file.readText()
    }

    private fun value(text: String, key: String): String? =
        Regex("""<string name="$key">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).find(text)?.groupValues?.getOrNull(1)

    @Test
    fun `七语言都带全套术语页文案`() {
        locales.forEach { locale ->
            val text = read(locale)
            keys.forEach { key ->
                val value = value(text, key)
                assertTrue("$locale 缺 $key", value != null && value.isNotBlank())
            }
        }
    }

    @Test
    fun `带参数的文案占位符数量对得上`() {
        locales.forEach { locale ->
            val text = read(locale)
            val awaiting = value(text, "settings_session_terms_awaiting")!!
            assertTrue("$locale 的 awaiting 需要一个整数参数", awaiting.contains("%1" + '$' + "d"))

            val occurrences = value(text, "settings_session_terms_occurrences")!!
            assertTrue("$locale 的 occurrences 需要一个整数参数", occurrences.contains("%1" + '$' + "d"))

            val title = value(text, "settings_session_terms_action_title")!!
            assertTrue("$locale 的 action_title 需要一个字符串参数", title.contains("%1" + '$' + "s"))

            val body = value(text, "settings_session_terms_action_body")!!
            assertTrue("$locale 的 action_body 需要整数与字符串两个参数", body.contains("%1" + '$' + "d") && body.contains("%2" + '$' + "s"))
        }
    }

    @Test
    fun `目录条目已登记且组内计数同步`() {
        // 这个类里的 read() 是按语言目录取文案的,不能拿它去读源码 —— 上一版就是这么读的,
        // 于是"找不到文件"报在一个完全无关的路径上。
        val catalog = listOf(
            File("app/src/main/java/io/zer0/muse/ui/settings/SettingsCatalog.kt"),
            File("src/main/java/io/zer0/muse/ui/settings/SettingsCatalog.kt"),
        ).firstOrNull { it.exists() }?.readText() ?: error("找不到 SettingsCatalog.kt")
        assertTrue("条目 id 未登记", catalog.contains("SESSION_TERMS,"))
        assertTrue("条目未挂路由", catalog.contains("SettingsEntryAction.Navigate(SettingsSessionTermsRoute)"))

        val layout = listOf(
            File("app/src/test/java/io/zer0/muse/ui/settings/SettingsGroupLayoutTest.kt"),
            File("src/test/java/io/zer0/muse/ui/settings/SettingsGroupLayoutTest.kt"),
        ).firstOrNull { it.exists() }?.readText() ?: error("找不到 SettingsGroupLayoutTest.kt")
        assertTrue("布局期望没同步(条目数)", layout.contains("EXPECTED_ENTRY_COUNT = 47"))
        assertTrue("记忆组没把术语页算进去", layout.contains("SettingsEntryId.SESSION_TERMS,"))
    }
}
