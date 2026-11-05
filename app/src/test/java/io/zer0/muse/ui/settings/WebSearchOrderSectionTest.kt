package io.zer0.muse.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import io.zer0.muse.web.AUTO_CHAIN_PROVIDER_KEYS
import io.zer0.muse.web.WebSearchConfig
import io.zer0.muse.web.CustomSearchEngine
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 「搜索顺序」列表（整改计划 第 1 条）的渲染判据。
 *
 * 这条链的顺序在实现里**只有一份**：`AUTO_CHAIN_PROVIDER_KEYS`（链构造与界面共用）。
 * 界面一旦自己抄一份清单，用户拖完看到的是假顺序 —— 保存的顺序与实际尝试顺序不一致，
 * 这类"第二份表"的坑在本项目里已经反复出现（能力表、上限表、挂载表都漂过）。
 * 所以这里钉两件事：
 *  1. 渲染出的行**恰好**是那批键，一个不多一个不少；
 *  2. 键前缀 `custom:` 是内部实现，不许漏到界面上。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "zh-rCN")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WebSearchOrderSectionTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun show(config: WebSearchConfig) {
        composeTestRule.setContent {
            MaterialTheme {
                WebSearchOrderSection(webSearchConfig = config, onSave = {})
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun countOf(text: String): Int =
        composeTestRule.onAllNodesWithText(text).fetchSemanticsNodes().size

    @Test
    fun `默认渲染的行数恰好等于内置顺序表`() {
        show(WebSearchConfig())
        val present = AUTO_CHAIN_PROVIDER_KEYS.filter { countOf(it) > 0 }
        assertEquals(
            "有引擎没被渲染出来（界面漏了项）: ${AUTO_CHAIN_PROVIDER_KEYS - present.toSet()}",
            AUTO_CHAIN_PROVIDER_KEYS.size,
            present.size,
        )
        AUTO_CHAIN_PROVIDER_KEYS.forEach { key ->
            assertEquals("引擎「$key」重复渲染", 1, countOf(key))
        }
    }

    @Test
    fun `自定义引擎名要出现且不带键前缀`() {
        val custom = CustomSearchEngine(name = "我的引擎", endpoint = "https://example.com/search", enabled = true)
        show(WebSearchConfig(customEngines = listOf(custom)))
        assertEquals("自定义引擎要出现在顺序表里", 1, countOf("我的引擎"))
        assertEquals("内部键前缀不该漏到界面上", 0, countOf("custom:我的引擎"))
    }

    @Test
    fun `停用或无端点的自定义引擎不进列表`() {
        val off = CustomSearchEngine(name = "停用引擎", endpoint = "https://a.test", enabled = false)
        val blank = CustomSearchEngine(name = "空端点引擎", endpoint = "", enabled = true)
        show(WebSearchConfig(customEngines = listOf(off, blank)))
        assertEquals("停用引擎不该占一行", 0, countOf("停用引擎"))
        assertEquals("没填端点的引擎不该占一行", 0, countOf("空端点引擎"))
    }
}
