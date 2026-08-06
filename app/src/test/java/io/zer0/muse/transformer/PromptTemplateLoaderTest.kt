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
        val generic = loader.render("decision_tree", null)
        val zh = loader.render("decision_tree", "zh")
        assertTrue("zh 模板应包含本地化内容: $zh", zh.contains("可用能力清单"))
        assertNotEquals(generic, zh)
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
}
