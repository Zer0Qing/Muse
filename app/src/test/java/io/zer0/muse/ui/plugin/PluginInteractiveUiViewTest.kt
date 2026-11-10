package io.zer0.muse.ui.plugin

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import io.zer0.muse.data.plugin.PluginUiTree
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 交互视图的三条用户体验规矩（设计文档 §8.3）。
 *
 * 这些断言看起来"很产品"，但它们防的正是最容易被实现细节破坏的东西：
 * 禁用不说明原因、连点写两次、失败把用户输入清空。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "zh-rCN")
class PluginInteractiveUiViewTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val formTree = """
        {
          "type": "form",
          "children": [
            { "type": "heading", "text": "新建任务" },
            { "type": "input", "key": "title", "label": "标题", "placeholder": "写点什么" },
            { "type": "input", "key": "count", "label": "数量", "kind": "number" },
            { "type": "input", "key": "done", "label": "已完成", "kind": "toggle" },
            { "type": "submit", "label": "保存", "handler": "save" }
          ]
        }
    """.trimIndent()

    private fun tree(source: String = formTree): PluginUiTree.Node = requireNotNull(PluginUiTree.parse(source).root)

    @Test
    fun `unauthorized view disables submit and states the reason`() {
        var calls = 0
        composeTestRule.setContent {
            MaterialTheme {
                PluginInteractiveUiView(
                    root = tree(),
                    assetsRoot = null,
                    interactiveEnabled = false,
                    disabledReason = "需要先授予「交互界面」能力",
                    onSubmit = { _, _ ->
                        calls++
                        PluginUiSubmitOutcome.Rejected("不该被调用")
                    },
                )
            }
        }

        composeTestRule.onNodeWithText("需要先授予「交互界面」能力", substring = true).assertExists()
        composeTestRule.onNodeWithText("保存", substring = true).performClick()
        assertEquals("未授权时点提交不得触发执行", 0, calls)
    }

    @Test
    fun `successful submit replaces the whole view with the returned tree`() {
        val next = """{"type":"column","children":[{"type":"text","text":"已保存"}]}"""
        composeTestRule.setContent {
            MaterialTheme {
                PluginInteractiveUiView(
                    root = tree(),
                    assetsRoot = null,
                    interactiveEnabled = true,
                    disabledReason = null,
                    onSubmit = { _, _ -> PluginUiSubmitOutcome.Rendered(PluginUiTree.parse(next)) },
                )
            }
        }

        composeTestRule.onNodeWithText("保存", substring = true).performClick()

        composeTestRule.onNodeWithText("已保存", substring = true).assertExists()
    }

    @Test
    fun `failed submit keeps what the user typed and shows the reason`() {
        composeTestRule.setContent {
            MaterialTheme {
                PluginInteractiveUiView(
                    root = tree(),
                    assetsRoot = null,
                    interactiveEnabled = true,
                    disabledReason = null,
                    onSubmit = { _, _ -> PluginUiSubmitOutcome.Rejected("存储写入失败：磁盘已满") },
                )
            }
        }

        composeTestRule.onAllNodes(hasSetTextAction())[0].performTextInput("买菜")
        composeTestRule.onNodeWithText("保存", substring = true).performClick()

        composeTestRule.onNodeWithText("存储写入失败：磁盘已满", substring = true).assertExists()
        // 输入框还在，且内容没被清掉 —— 让用户重打一遍是拿别人的时间换作者的方便
        composeTestRule.onNodeWithText("买菜", substring = true).assertExists()
    }

    @Test
    fun `rapid double tap runs the handler once`() {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        composeTestRule.setContent {
            MaterialTheme {
                PluginInteractiveUiView(
                    root = tree(),
                    assetsRoot = null,
                    interactiveEnabled = true,
                    disabledReason = null,
                    onSubmit = { _, _ ->
                        calls++
                        gate.await()
                        PluginUiSubmitOutcome.Rejected("完成")
                    },
                )
            }
        }

        composeTestRule.onNodeWithText("保存", substring = true).performClick()
        composeTestRule.onNodeWithText("保存", substring = true).performClick()

        assertEquals("连点只能跑一次", 1, calls)
        // 进行中按钮显示转圈并拒绝点击（组件内部 clickable = enabled && !loading）：
        // 这就是用户能看到的反馈，不必再弹一句"上一次仍在执行"。
        composeTestRule.onNodeWithText("保存", substring = true).assertIsNotEnabled()
        gate.complete(Unit)
    }

    @Test
    fun `form values are typed for the plugin`() {
        val inputs = collectInputs(tree())

        val json = valuesJson(
            inputs,
            mapOf("title" to "买菜", "count" to "12", "done" to "true"),
        )

        assertTrue("文本按字符串: $json", json.contains("\"title\":\"买菜\""))
        assertTrue("数字按数字（输入项 kind 实为 ${inputs.map { it.key + "=" + it.kind }}）: $json", json.contains("\"count\":12"))
        assertTrue("开关按布尔: $json", json.contains("\"done\":true"))
    }

    @Test
    fun `non numeric text in a number field falls back to string instead of crashing`() {
        val inputs = collectInputs(tree())

        val json = valuesJson(inputs, mapOf("count" to "十二"))

        assertTrue("不能因为用户打了中文就丢字段: $json", json.contains("\"count\":\"十二\""))
    }

    @Test
    fun `interactive detection only looks at input and submit`() {
        assertTrue(hasInteractiveNodes(tree()))
        val readOnly = requireNotNull(
            PluginUiTree.parse("""{"type":"column","children":[{"type":"text","text":"只是文字"}]}""").root,
        )
        assertTrue("只读树不该被判成交互", !hasInteractiveNodes(readOnly))
    }
}
