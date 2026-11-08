package io.zer0.muse.ui.settings

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.R
import io.zer0.muse.data.assistant.AssistantEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 约定页渲染层（无状态内容）。
 *
 * **测到哪一层，写清楚**：Robolectric 下 `LazyColumn` 只组合首屏，
 * 位于后面分节的节点（语音档位、音色入口）在这里断言不到，
 * 硬写"数量 == 1"只会得到恒真的 0==0 —— 那是假判据，不写。
 * 那些判断改由纯函数层钉住：`AgentDirectivesUiStateTest`（作用域回落、分组、超量计数）
 * 与 `VoiceReplyPolicyTest`（档位政策与默认档不注入），都做过变异验证。
 *
 * 共享组件 `MuseChip` 也没有 `selectable` 语义（选中只体现在颜色上），
 * 所以"哪一颗被选中"在 UI 层无法断言；这条限制留给组件层去补，不在我这里绕。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "zh-rCN")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentDirectivesContentTest {

    @get:Rule
    val rule = createComposeRule()

    private fun countOf(text: String): Int = rule.onAllNodesWithText(text).fetchSemanticsNodes().size

    private fun renderWith(assistants: List<AssistantEntity>) {
        rule.setContent {
            AgentDirectivesContent(
                assistants = assistants,
                agentId = AGENT,
                forbidden = emptyList(),
                desired = emptyList(),
                omittedCount = 0,
                onBack = {},
                onSelectAgent = {},
                onSave = { _, _ -> },
                onToggle = { _, _ -> },
                onMove = { _, _ -> },
                onDelete = {},
                onOpenTimbre = {},
            )
        }
    }

    @Test
    fun `没有约定时显示空态而不是空白`() {
        renderWith(listOf(AssistantEntity(id = AGENT, name = "阿绫")))
        assertEquals("一行都不能没有，否则用户以为功能坏了", 1, countOf("还没有写过。比如：别叫我宝贝"))
    }

    /**
     * 两段的空态示例必须是两句不同的话。
     *
     * 以前两段共用 `agent_directives_empty`，后果有两个：「尽量做到」里举的是
     * 「别叫我宝贝」这种「绝对不要」的例子；而且屏幕上是两句一模一样的空态提示。
     * 不在这里数全局文本次数：本节文件头已经写了，Robolectric 下后面分节可能根本不组合，
     * 那种断言会退化成 0==0。所以判据收到资源层：两句不一样是实质，资源层可稳定地测。
     */
    @Test
    fun `两段空态不许共用同一句示例`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertNotEquals(
            "「尽量做到」要有自己的示例，不能复述「绝对不要」的那句",
            context.getString(R.string.agent_directives_empty),
            context.getString(R.string.agent_directives_empty_desired),
        )
    }

    @Test
    fun `空态里不许出现两个同名的添加入口`() {
        renderWith(listOf(AssistantEntity(id = AGENT, name = "阿绫")))
        // 这一条是渲染测试实测出来的缺陷：空态行的副标题与段尾入口都写「添加一条…」
        assertEquals("同一段里同一个动作只能有一个入口", 1, countOf("添加一条「不要做」"))
    }

    @Test
    fun `单个助手时不出现助手选择条`() {
        renderWith(listOf(AssistantEntity(id = AGENT, name = "阿绫")))
        assertEquals("只有一个助手还让用户选一次是多余的一步", 0, countOf("阿绫"))
    }

    /**
     * 上一条的**正例**：多个助手时同一位置确实会出现名字。
     *
     * 没有它，上一条的 `0` 可能只是"这一屏根本没组合出来"（Robolectric 下 LazyColumn 只组合首屏），
     * 那就是一条恒真的假断言 —— 我今天已经栽在这种判据上两次了。
     */
    @Test
    fun `多个助手时选择条确实出现`() {
        renderWith(
            listOf(
                AssistantEntity(id = AGENT, name = "阿绫"),
                AssistantEntity(id = "a2", name = "阿远"),
            ),
        )
        assertEquals(1, countOf("阿绫"))
        assertEquals(1, countOf("阿远"))
    }
}

private const val AGENT = "a1"
