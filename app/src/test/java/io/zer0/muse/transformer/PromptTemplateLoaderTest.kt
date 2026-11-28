package io.zer0.muse.transformer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R-TEST-17: PromptTemplateLoader locale=null / 未知 locale / 缺失模板回落。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PromptTemplateLoaderTest {

    private val loader = PromptTemplateLoader(
        ApplicationProvider.getApplicationContext<Context>(),
    )

    @Test
    fun `locale null loads generic template instead of fallback`() {
        val text = loader.render("execution_rules", null, fallback = "fallback-value")
        assertTrue("generic 模板应包含执行准则内容: $text", text.contains("执行准则"))
        assertNotEquals("fallback-value", text)
    }

    @Test
    fun `zh locale prefers localized template`() {
        val zh = loader.render("execution_rules", "zh")
        val en = loader.render("execution_rules", "en")
        assertTrue("zh 模板应包含中文执行准则: $zh", zh.contains("执行准则"))
        assertTrue("en 模板应包含英文执行准则: $en", en.contains("Execution rules"))
        assertNotEquals("zh/en 本地化模板不应相同", zh, en)
    }

    @Test
    fun `unknown locale falls back to english not chinese`() {
        // v2.6.0: 非中文 locale 回落英文版,避免国际用户拿到中文提示词。
        val zz = loader.render("execution_rules", "zz")
        assertTrue("未知 locale 应落到英文模板(含 Execution rules): $zz", zz.contains("Execution rules"))
        assertEquals("未知 locale 与 en 应一致", loader.render("execution_rules", "en"), zz)
    }

    @Test
    fun `supported non-chinese locales fall back to english`() {
        // 日/韩/西/葡/俄尚无专属模板,应回落英文而非中文。
        val en = loader.render("execution_rules", "en")
        for (loc in listOf("ja", "ko", "es", "pt", "ru")) {
            assertEquals("$loc 应回落英文执行准则", en, loader.render("execution_rules", loc))
        }
    }

    @Test
    fun `chinese locale keeps chinese template`() {
        val zh = loader.render("execution_rules", "zh")
        assertTrue("中文 locale 应拿中文模板: $zh", zh.contains("执行准则"))
    }

    @Test
    fun `missing template returns fallback without crashing`() {
        assertEquals("fallback-value", loader.render("not_exist_template", null, fallback = "fallback-value"))
        assertEquals("fallback-value", loader.render("not_exist_template", "ja", fallback = "fallback-value"))
    }

    @Test
    fun `platform decl includes execution environment section`() {
        assertTrue("平台声明应包含执行环境自述", loader.render("platform_decl", "zh").contains("执行环境"))
        assertTrue("英文平台声明应包含执行环境", loader.render("platform_decl", "en").contains("Execution environment"))
    }

    /**
     * v2.6.0 (P2): 防漂移守卫 —— 每个已注册模板都必须存在 zh 与 en 两个版本,
     * 且两者不等（一为中文一为英文）。防止后续只改一侧、或新增模板漏英文版。
     */
    @Test
    fun `every registered template has distinct zh and en versions`() {
        val missing = mutableListOf<String>()
        for (name in PromptTemplateLoader.TEMPLATE_NAMES) {
            val zh = loader.render(name, "zh", fallback = "")
            val en = loader.render(name, "en", fallback = "")
            if (zh.isBlank()) missing += "$name(zh)"
            if (en.isBlank()) missing += "$name(en)"
            if (zh.isNotBlank() && zh == en) missing += "$name(zh==en 未真正国际化)"
        }
        assertTrue("模板缺失或中英相同: $missing", missing.isEmpty())
    }

    /**
     * v2.6.0 (P2): 非中文 locale 永不拿到中文模板 —— 防反弹。
     * 逐个模板用 ja 渲染，确认结果不含「中文 CJK 段落特征」而等于 en。
     */
    @Test
    fun `non-chinese locale never yields chinese template`() {
        val offenders = mutableListOf<String>()
        for (name in PromptTemplateLoader.TEMPLATE_NAMES) {
            val ja = loader.render(name, "ja", fallback = "")
            val en = loader.render(name, "en", fallback = "")
            if (ja.isNotBlank() && en.isNotBlank() && ja != en) offenders += name
        }
        assertTrue("以下模板对 ja 未回落英文: $offenders", offenders.isEmpty())
    }
}
