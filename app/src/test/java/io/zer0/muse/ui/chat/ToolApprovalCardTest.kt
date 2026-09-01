package io.zer0.muse.ui.chat

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.R
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
}
