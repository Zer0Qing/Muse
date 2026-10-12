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
        val text = loader.render("decision_tree", null, fallback = "fallback-value")
        assertTrue("generic 模板应包含决策规则内容: $text", text.contains("决策规则"))
        assertNotEquals("fallback-value", text)
    }

    @Test
    fun `zh locale prefers localized template`() {
        val zh = loader.render("decision_tree", "zh")
        val en = loader.render("decision_tree", "en")
        assertTrue("zh 模板应包含中文决策规则: $zh", zh.contains("决策规则"))
        assertTrue("en 模板应包含英文决策规则: $en", en.contains("Decision rules"))
        assertNotEquals("zh/en 本地化模板不应相同", zh, en)
    }

    @Test
    fun `unknown locale falls back to english not chinese`() {
        // v2.6.0: 非中文 locale 回落英文版,避免国际用户拿到中文提示词。
        val zz = loader.render("decision_tree", "zz")
        assertTrue("未知 locale 应落到英文模板(含 Decision rules): $zz", zz.contains("Decision rules"))
        assertEquals("未知 locale 与 en 应一致", loader.render("decision_tree", "en"), zz)
    }

    @Test
    fun `supported non-chinese locales fall back to english`() {
        // 日/韩/西/葡/俄尚无专属模板,应回落英文而非中文。
        val en = loader.render("tool_discipline", "en")
        for (loc in listOf("ja", "ko", "es", "pt", "ru")) {
            assertEquals("$loc 应回落英文工具纪律", en, loader.render("tool_discipline", loc))
        }
    }

    @Test
    fun `chinese locale keeps chinese template`() {
        val zh = loader.render("tool_discipline", "zh")
        assertTrue("中文 locale 应拿中文模板: $zh", zh.contains("工具使用纪律"))
    }

    @Test
    fun `missing template returns fallback without crashing`() {
        assertEquals("fallback-value", loader.render("not_exist_template", null, fallback = "fallback-value"))
        assertEquals("fallback-value", loader.render("not_exist_template", "ja", fallback = "fallback-value"))
    }

    @Test
    fun `action discipline templates exist and differ by locale`() {
        val zh = loader.render("action_discipline", "zh")
        val en = loader.render("action_discipline", "en")
        assertTrue("zh 行动纪律应包含关键词: $zh", zh.contains("行动纪律"))
        assertTrue("en 行动纪律应包含关键词: $en", en.contains("Action discipline"))
        assertNotEquals("zh/en 行动纪律不应相同", zh, en)
    }

    @Test
    fun `delivery contract templates exist for all locales`() {
        assertTrue(loader.render("delivery_contract", "zh").contains("交付契约"))
        assertTrue(loader.render("delivery_contract", "en").contains("Delivery contract"))
        assertTrue(loader.render("delivery_contract", null).contains("交付契约"))
    }

    @Test
    fun `platform decl includes execution environment section`() {
        assertTrue("平台声明应包含执行环境自述", loader.render("platform_decl", "zh").contains("执行环境"))
        assertTrue("英文平台声明应包含执行环境", loader.render("platform_decl", "en").contains("Execution environment"))
    }

    @Test
    fun `tool discipline declares three tier web priority`() {
        assertTrue("工具纪律应声明三级优先", loader.render("tool_discipline", "zh").contains("三级优先"))
        assertTrue("英文工具纪律应声明三级优先", loader.render("tool_discipline", "en").contains("Three-tier priority"))
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
