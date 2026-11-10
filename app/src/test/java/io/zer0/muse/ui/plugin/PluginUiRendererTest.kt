package io.zer0.muse.ui.plugin

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.zer0.muse.data.plugin.PluginUiTree
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 声明式界面确实**渲染得出来**（而不是只解析得动）。
 *
 * 解析器测试保证"输入被正确理解"，这里保证"用户看得见内容"：
 * 一个只在单测里绿、页面上什么都不画的渲染器，对插件作者是同样糟糕的结果。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "zh-rCN") // 断言里用的是中文文案，锁定语言环境
class PluginUiRendererTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val tree = """
        {
          "type": "column",
          "children": [
            { "type": "heading", "text": "今日概览" },
            { "type": "stat", "label": "完成", "value": "12", "hint": "比昨天多 3" },
            { "type": "kv", "key": "阻塞项", "value": "等接口" },
            { "type": "progress", "value": 12, "max": 15, "label": "周目标" },
            { "type": "badge", "text": "待复核" },
            { "type": "list", "items": ["买牛奶", "写周报"] },
            { "type": "code", "text": "git status" },
            { "type": "caption", "text": "由示例插件提供" }
          ]
        }
    """.trimIndent()

    @Test
    fun `every declared node shows up on screen`() {
        val parsed = requireNotNull(PluginUiTree.parse(tree).root)

        composeTestRule.setContent {
            MaterialTheme { PluginUiTreeView(root = parsed, assetsRoot = null) }
        }

        listOf("今日概览", "完成", "12", "阻塞项", "等接口", "周目标", "待复核", "买牛奶", "写周报", "git status", "由示例插件提供")
            .forEach { expected ->
                composeTestRule.onNodeWithText(expected, substring = true).assertExists(
                    "节点内容没渲染出来：$expected",
                )
            }
    }

    @Test
    fun `nested rows and columns render their children too`() {
        val source = """
            {"type":"column","children":[
              {"type":"row","children":[{"type":"text","text":"左格"},{"type":"text","text":"右格"}]}
            ]}
        """.trimIndent()
        val parsed = requireNotNull(PluginUiTree.parse(source).root)

        composeTestRule.setContent {
            MaterialTheme { PluginUiTreeView(root = parsed, assetsRoot = null) }
        }

        composeTestRule.onNodeWithText("左格", substring = true).assertExists()
        composeTestRule.onNodeWithText("右格", substring = true).assertExists()
    }

    @Test
    fun `a broken view is surfaced with its reason instead of silently missing`() {
        // 静默不显示会让作者完全查不动；这里必须能看到"为什么没渲染"。
        val broken = PluginUiTree.parse("""{"type":"sparkline","points":[1]}""")
        assertParseFailed(broken)

        composeTestRule.setContent {
            MaterialTheme {
                PluginDeclarativeViewEntry(pluginName = "示例插件", loaded = broken, assetsRoot = null)
            }
        }

        val entry = composeTestRule.onNodeWithText("界面未能渲染", substring = true)
        entry.assertExists("解析失败时入口不该消失")
        entry.performClick()
        composeTestRule.onNodeWithText("sparkline", substring = true).assertExists("拒绝原因应能在弹窗里看到")
    }

    @Test
    fun `progress out of range renders a clamped bar instead of breaking layout`() {
        // 1e30 / -5 这类值若直接进 progress，会算出荒谬宽度
        val source = """{"type":"progress","value":1e30,"max":1.0,"label":"溢出"}"""
        val parsed = requireNotNull(PluginUiTree.parse(source).root)

        composeTestRule.setContent {
            MaterialTheme { PluginUiTreeView(root = parsed, assetsRoot = null) }
        }

        composeTestRule.onNodeWithText("溢出", substring = true).assertExists()
    }

    @Test
    fun `missing asset falls back to alt text rather than a broken image`() {
        val source = """{"type":"assetImage","path":"assets/nope.png","alt":"趋势图占位"}"""
        val parsed = requireNotNull(PluginUiTree.parse(source).root)

        composeTestRule.setContent {
            MaterialTheme { PluginUiTreeView(root = parsed, assetsRoot = File("build")) }
        }

        composeTestRule.onNodeWithText("趋势图占位", substring = true).assertExists()
    }

    @Test
    fun `asset path escaping the package is not loaded`() {
        val source = """{"type":"assetImage","path":"../../secret.png","alt":"越界图片"}"""
        val parsed = requireNotNull(PluginUiTree.parse(source).root)

        composeTestRule.setContent {
            MaterialTheme { PluginUiTreeView(root = parsed, assetsRoot = File("build")) }
        }

        // 越界解析必须退回 alt 文本：不能把包外文件显示出来
        composeTestRule.onNodeWithText("越界图片", substring = true).assertExists()
    }

    private fun assertParseFailed(parsed: PluginUiTree.Parsed) {
        assertTrue("测试前提：这份输入必须解析失败", !parsed.success)
    }
}
