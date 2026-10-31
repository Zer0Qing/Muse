package io.zer0.muse.ui.chat

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.zer0.common.AppJson
import io.zer0.muse.R
import io.zer0.muse.tools.toolArgsToMap
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * I-AUDIT(P1-2/I-P2-2): 工具审批卡语义测试。
 *
 * 此前全项目 Compose UI 测试仅 TokenStatsBarTest 一个,审批卡 onApprove/onDeny
 * 接线错误无法在 CI 被发现。本测试锁死两个安全关键路径:批准触发 onApprove、
 * 拒绝需要二次确认才触发 onDeny。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ToolApprovalCardTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun approveButton_triggersOnApproveOnce() {
        var approved = 0
        var denied: String? = null
        composeTestRule.setContent {
            MaterialTheme {
                ToolApprovalCard(
                    toolName = "web_search",
                    argumentsPreview = "{\"q\":\"weather\"}",
                    onApprove = { approved++ },
                    onDeny = { denied = it },
                )
            }
        }
        composeTestRule.onNodeWithText(context.getString(R.string.tool_approval_approve)).performClick()
        assertTrue("点击批准应触发 onApprove", approved == 1)
        assertNull("批准不应触发 onDeny", denied)
    }

    @Test
    fun denyButton_requiresConfirmationThenTriggersOnDeny() {
        var approved = false
        var denied = 0
        composeTestRule.setContent {
            MaterialTheme {
                ToolApprovalCard(
                    toolName = "web_search",
                    argumentsPreview = "{\"q\":\"weather\"}",
                    onApprove = { approved = true },
                    onDeny = { denied++ },
                )
            }
        }
        // 第一次点"拒绝"只是展开理由输入框,不立即回调 onDeny
        composeTestRule.onNodeWithText(context.getString(R.string.tool_approval_deny)).performClick()
        assertFalse("第一次点拒绝不应触发 onDeny", denied > 0)
        // 第二次点"确认拒绝"才回调 onDeny
        composeTestRule.onNodeWithText(context.getString(R.string.tool_approval_confirm_deny)).performClick()
        assertTrue("确认拒绝应触发 onDeny", denied == 1)
        assertFalse("拒绝不应触发 onApprove", approved)
    }

    /**
     * 大体积参数:折叠态必须告诉用户「还有多少字符没看到」,展开后能拿到结尾那段正文。
     *
     * 这是真实事故场景:4KB 正文的写入请求,卡片只显示开头,用户以为已经看清了。
     */
    @Test
    fun longArguments_showHiddenCountThenExpandToFullContent() {
        val body = "A".repeat(3_700) + "TAIL_MARKER"
        val fullArgs = mapOf("path" to "notes/long.md", "content" to body)
        composeTestRule.setContent {
            MaterialTheme {
                ToolApprovalCard(
                    toolName = "write_file",
                    argumentsPreview = "{\"path\":\"notes/long.md\",\"content\":\"AAA…",
                    argumentsFull = fullArgs,
                    onApprove = {},
                    onDeny = {},
                )
            }
        }
        // 折叠态:预览之外必须明确写出省略了多少字符
        val expectedNote = context.getString(R.string.tool_approval_args_hidden, body.length - 300)
        composeTestRule.onNodeWithText(expectedNote, substring = true).assertExists()
        // 全量参数在场时摘要才成立(此前 200 字符预览无法解析,大字参数只剩原始转义串)
        composeTestRule.onNodeWithText(
            context.getString(R.string.tool_approval_write_summary, "long.md", body.length),
        ).assertExists()
        // 结尾标记在折叠态不可见(用户看到的就是预览)
        composeTestRule.onNodeWithText("TAIL_MARKER", substring = true).assertDoesNotExist()

        // 点展开后才能看到完整正文
        composeTestRule.onNodeWithText(context.getString(R.string.tool_approval_show_full_args), substring = true)
            .performClick()
        composeTestRule.onNodeWithText(body).assertExists()
        composeTestRule.onNodeWithText("TAIL_MARKER", substring = true).assertExists()
        // 展开后提示切换为收起,不再声称「有字符没显示」
        composeTestRule.onNodeWithText(expectedNote, substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText(context.getString(R.string.tool_approval_hide_full_args)).assertExists()
    }

    /**
     * 展开后看到的正文必须与工具实际拿到的值逐字一致。
     *
     * 参数在传输层是 JSON 文本(content 里的换行写作 `\n` 转义),执行前由 [toolArgsToMap]
     * 解码成真换行。卡片如果直接展示原始 JSON,用户看到的就是 `\n` 字面量 —— 那等于
     * 「看了,但看的不是同一份东西」。本用例锁死:卡片展开后展示的是解码值。
     */
    @Test
    fun expandedContent_matchesTheValueTheToolExecutes() {
        val rawJson = "{\"path\":\"notes/long.md\",\"content\":\"第一行\\n第二行\\n" +
            "B".repeat(400) + "\\nEND_MARKER\"}"
        // 与生产链路同一套解析: 执行层拿到的就是解码后的字符串
        val executed = toolArgsToMap(AppJson.decodeFromString(JsonObject.serializer(), rawJson))
        val executedContent = executed["content"] as String
        assertTrue("执行值里应是真换行", executedContent.contains("\n"))
        assertFalse("执行值里不应有反斜杠字面量", executedContent.contains("\\n"))

        composeTestRule.setContent {
            MaterialTheme {
                ToolApprovalCard(
                    toolName = "write_file",
                    argumentsPreview = rawJson.take(200),
                    argumentsFull = executed,
                    onApprove = {},
                    onDeny = {},
                )
            }
        }
        composeTestRule.onNodeWithText(context.getString(R.string.tool_approval_show_full_args), substring = true)
            .performClick()

        // 展开后逐字展示执行值,而不是 JSON 转义后的形态
        composeTestRule.onNodeWithText(executedContent).assertExists()
        composeTestRule.onNodeWithText("第一行\\n第二行", substring = true).assertDoesNotExist()
    }

    @Test
    fun hiddenCharacters_countsOnlyWhatIsNotShown() {
        assertEquals("截断时应报出剩余字符数", 3_411, hiddenCharacters(3_711, 300))
        assertEquals("没截断时不应报数", 0, hiddenCharacters(120, 120))
        assertEquals("展示长度超出总量时不应为负", 0, hiddenCharacters(80, 200))
    }

    @Test
    fun approvalArgumentEntries_prefersFullArguments() {
        val entries = approvalArgumentEntries(
            preview = "{\"path\":\"a.md\",\"content\":\"tru",
            full = mapOf("path" to "a.md", "content" to "truncated-not-this"),
        )
        assertEquals(listOf("path" to "a.md", "content" to "truncated-not-this"), entries)
    }

    @Test
    fun approvalArgumentEntries_fallsBackToParsablePreview() {
        assertEquals(
            listOf("q" to "weather"),
            approvalArgumentEntries(preview = "{\"q\":\"weather\"}", full = emptyMap()),
        )
        // 预览被截断成非法 JSON 且没有全量参数时,不应凭空造条目
        assertTrue(approvalArgumentEntries(preview = "{\"q\":\"wea", full = emptyMap()).isEmpty())
    }

    @Test
    fun approvalValueOf_and_extraArguments_skipSummaryKeys() {
        val entries = listOf("path" to "a.md", "content" to "body", "encoding" to "utf-8")
        assertEquals("a.md", approvalValueOf(entries, listOf("path", "file_path")))
        assertEquals("body", approvalValueOf(entries, listOf("content", "text")))
        assertEquals(listOf("encoding" to "utf-8"), approvalExtraArguments(entries))
    }

    @Test
    fun approvalRawJson_prettyPrintsEveryKey() {
        val raw = approvalRawJson(listOf("path" to "a.md", "content" to "x"))
        assertTrue("应保留全部 key", raw != null && raw.contains("\"path\"") && raw.contains("\"content\""))
        assertNull("无条目时不应编造 JSON", approvalRawJson(emptyList()))
    }
}
