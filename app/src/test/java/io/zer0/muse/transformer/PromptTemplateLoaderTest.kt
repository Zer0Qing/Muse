package io.zer0.muse.transformer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * R-TEST-17: PromptTemplateLoader locale=null / 未知 locale / 缺失模板回落。
 */
@RunWith(RobolectricTestRunner::class)
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
    fun `unknown locale falls back to generic template`() {
        assertEquals(
            loader.render("decision_tree", null),
            loader.render("decision_tree", "zz"),
        )
    }

    @Test
    fun `missing template returns fallback without crashing`() {
        assertEquals("fallback-value", loader.render("not_exist_template", null, fallback = "fallback-value"))
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
}
