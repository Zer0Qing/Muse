package io.zer0.muse.ui

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.zer0.ai.core.RagCitation
import io.zer0.muse.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Phase 2: RAG 引用 chip 的 key 去重、无障碍描述与展开动作测试。
 *
 * 使用 zh-rCN 限定符让断言字符串与默认资源一致。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "zh-rCN")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RagCitationChipsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun citation(index: Int, title: String = "文档$index") = RagCitation(
        index = index,
        docId = "doc-$index",
        docTitle = title,
        chunkId = "chunk-$index",
        chunkIndex = 0,
        snippet = "片段内容$index",
        score = 0.9f,
        matchType = "vector",
    )

    @Test
    fun `重复 index 去重后只渲染一个 chip`() {
        composeTestRule.setContent {
            MaterialTheme {
                RagCitationChips(
                    citations = listOf(citation(1), citation(1, title = "重复文档")),
                )
            }
        }
        assertEquals(1, composeTestRule.onAllNodesWithText("[1]").fetchSemanticsNodes().size)
    }

    @Test
    fun `chip 提供引用编号与文档名的 contentDescription`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val expected = context.getString(
            R.string.chat_citation_chip_a11y,
            1,
            "文档1",
            context.getString(R.string.action_expand),
        )
        composeTestRule.setContent {
            MaterialTheme { RagCitationChips(citations = listOf(citation(1))) }
        }
        composeTestRule.onNodeWithContentDescription(expected).assertExists()
    }

    @Test
    fun `展开后显示摘要与复制动作`() {
        composeTestRule.setContent {
            MaterialTheme { RagCitationChips(citations = listOf(citation(1))) }
        }
        composeTestRule.onNodeWithText("[1]").performClick()
        composeTestRule.onNodeWithText("片段内容1").assertExists()
        composeTestRule.onNodeWithText("复制摘要").assertExists()
        // 未提供回调时不显示「打开文档」
        composeTestRule.onNodeWithText("打开文档").assertDoesNotExist()
    }

    @Test
    fun `提供 onOpenDocument 时展示动作并回调对应引用`() {
        var opened: RagCitation? = null
        composeTestRule.setContent {
            MaterialTheme {
                RagCitationChips(
                    citations = listOf(citation(2)),
                    onOpenDocument = { opened = it },
                )
            }
        }
        composeTestRule.onNodeWithText("[2]").performClick()
        composeTestRule.onNodeWithText("打开文档").performClick()
        assertEquals(2, opened?.index)
    }
}
