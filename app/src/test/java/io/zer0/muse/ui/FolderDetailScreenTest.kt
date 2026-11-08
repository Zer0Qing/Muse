package io.zer0.muse.ui

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.R
import io.zer0.muse.data.session.SessionEntity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 38/文件夹详情页：空文件夹给出可读空态 + 两个动作入口，不再"建了没用"。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "zh-rCN")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FolderDetailScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `空文件夹显示空态与新建添加入口`() {
        rule.setContent {
            MaterialTheme {
                FolderDetailScreen(
                    folderName = "旅行",
                    sessions = emptyList(),
                    ungroupedSessions = emptyList(),
                    onBack = {},
                    onOpenSession = {},
                    onCreateInFolder = {},
                    onAddSession = {},
                    onRemoveSession = {},
                )
            }
        }
        rule.onNodeWithText(context.getString(R.string.folder_detail_empty_title)).assertExists()
        rule.onNodeWithText(context.getString(R.string.folder_detail_new_here)).assertExists()
        rule.onNodeWithText(context.getString(R.string.folder_detail_add_existing)).assertExists()
    }

    @Test
    fun `添加已有对话弹窗能打开且列出候选`() {
        rule.setContent {
            MaterialTheme {
                FolderDetailScreen(
                    folderName = "旅行",
                    sessions = emptyList(),
                    ungroupedSessions = listOf(
                        SessionEntity(id = "s1", title = "周末计划", createdAt = 0, updatedAt = 0),
                    ),
                    onBack = {},
                    onOpenSession = {},
                    onCreateInFolder = {},
                    onAddSession = {},
                    onRemoveSession = {},
                )
            }
        }
        rule.onNodeWithText(context.getString(R.string.folder_detail_add_existing)).performClick()
        // 修复前弹窗内容里又套了一层 verticalScroll，测量阶段会因无限高度约束直接崩；
        // 能列出候选行就说明这条路径已经通了。
        rule.onNodeWithText("周末计划").assertExists()
    }
}
