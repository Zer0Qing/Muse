package io.zer0.muse.ui.taskcard

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.zer0.muse.R
import io.zer0.muse.data.subagent.SubagentThreadStore
import io.zer0.muse.tools.DeferredResultStore
import io.zer0.muse.tools.DelegationChainTracker
import io.zer0.muse.tools.DelegationPauseManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 协作界面的真机渲染测试（细化路线 P1）。
 *
 * 这三张卡是用户看委派过程的**唯一窗口**，此前在测试里一次都没出现过 ——
 * 逻辑层有 8 个测试类守着，界面层全是黑的：节点渲染顺序错、状态标签接错、
 * 确认后回调不触发，都不会有人知道。
 *
 * 断言一律绑**资源 id 而不是中文串**：换语言或改文案时测试不该跟着红，
 * 而"该出现的文字没出现"才是真正的回归信号。
 */
@RunWith(AndroidJUnit4::class)
class TaskCardSurfacesDeviceTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Application get() = ApplicationProvider.getApplicationContext()

    private fun s(id: Int, vararg args: Any): String = context.getString(id, *args)

    private fun node(requestId: String, parent: String?, status: DelegationNodeStatus, targetName: String, task: String) =
        DelegationChainTracker.ChainNode(
            requestId = requestId,
            parentRequestId = parent,
            task = task,
            targetType = "assistant",
            targetId = "a-$requestId",
            targetName = targetName,
            status = status,
            startedAt = 1_700_000_000_000L,
            finishedAt = if (status == DelegationNodeStatus.RUNNING) null else 1_700_000_005_000L,
        )

    @Test
    fun emptyChainShowsHintInsteadOfACardFrame() {
        compose.setContent { DelegationChainCard(roots = emptyList()) }

        compose.onNodeWithText(s(R.string.delegation_no_chain)).assertExists()
        compose.onNodeWithText(s(R.string.delegation_chain_title)).assertDoesNotExist()
    }

    @Test
    fun chainCardRendersTitleNodeCountAndPausedStatus() {
        val roots = listOf(
            node("r1", null, DelegationNodeStatus.RUNNING, "小数", "整理本周记录"),
            node("r2", "r1", DelegationNodeStatus.PAUSED, "小美", "等你确认再发"),
        )

        compose.setContent { DelegationChainCard(roots = roots) }

        compose.onNodeWithText(s(R.string.delegation_chain_title)).assertExists()
        compose.onNodeWithText(s(R.string.delegation_node_count, 2)).assertExists()
        compose.onNodeWithText("小数").assertExists()
        compose.onNodeWithText("小美").assertExists()
        // 暂停中的节点必须把「等待确认」显示出来：这是用户唯一知道要动手的地方
        compose.onNodeWithText(s(R.string.delegation_status_paused)).assertExists()
    }

    @Test
    fun confirmDialogEmitsTheChosenDecision() {
        val request = DelegationPauseManager.PauseRequest(
            requestId = "p1",
            taskId = "t1",
            taskTitle = "对外发送草稿",
            taskDescription = "要把内容发给外部",
            targetType = "team",
            targetName = "写作组",
            reason = "高风险动作，先确认",
        )
        val received = mutableListOf<DelegationPauseManager.PauseResponse>()

        compose.setContent {
            DelegationConfirmDialog(pauseRequest = request, onSubmit = { received += it })
        }

        compose.onNodeWithText("高风险动作，先确认").assertExists()
        compose.onNodeWithText(DelegationPauseManager.PauseOption.APPROVE.label).performClick()

        assertEquals("点批准要继续应把 APPROVE 交回去", 1, received.size)
        assertEquals(DelegationPauseManager.PauseDecision.APPROVE, received.single().decision)
    }

    @Test
    fun confirmDialogEmitsCancelWhenCancelOptionIsPresent() {
        val request = DelegationPauseManager.PauseRequest(
            requestId = "p2",
            taskId = "t2",
            taskTitle = "标题",
            taskDescription = "描述",
            targetType = "assistant",
            targetName = "小数",
            reason = "要确认",
            // 默认只有「批准/拒绝」两项：不显式给 CANCEL 就不会画"取消委派"。
            // 这条测试因此自己把三项摆全，而不是假设界面一定会给。
            options = listOf(
                DelegationPauseManager.PauseOption.APPROVE,
                DelegationPauseManager.PauseOption.REJECT,
                DelegationPauseManager.PauseOption.CANCEL,
            ),
        )
        val received = mutableListOf<DelegationPauseManager.PauseResponse>()

        compose.setContent {
            DelegationConfirmDialog(pauseRequest = request, onSubmit = { received += it })
        }
        // 用户没选任何一项就关掉：必须算「取消整个委派」，不能静默什么都不发
        compose.onNodeWithText(DelegationPauseManager.PauseOption.CANCEL.label).performClick()

        assertEquals(DelegationPauseManager.PauseDecision.CANCEL, received.single().decision)
    }

    @Test
    fun nullPauseRequestRendersNothing() {
        compose.setContent {
            DelegationConfirmDialog(pauseRequest = null, onSubmit = { error("不该有提交") })
        }

        compose.onNodeWithText(DelegationPauseManager.PauseOption.APPROVE.label).assertDoesNotExist()
    }

    @Test
    fun taskListCardIsCollapsedThenRevealsRowsAndCancelReachesTheRightTask() {
        val threads = listOf(
            SubagentThreadStore.ThreadEntry(
                threadId = "thread-abc-123",
                assistantId = "assistant-1",
                parentSessionId = "session-1",
                createdAt = 1_700_000_000_000L,
            ),
        )
        val tasks = listOf(
            DeferredResultStore.DeferredTask(
                taskId = "task-1",
                parentSessionId = "session-1",
                threadId = "thread-abc-123",
                label = "汇总会议纪要",
                taskSummary = "把三份记录并成一份",
                status = DeferredResultStore.TaskStatus.PENDING,
            ),
        )
        val cancelled = mutableListOf<String>()

        compose.setContent {
            SubagentTaskListCard(
                activeThreads = threads,
                pendingTasks = tasks,
                onCancel = { cancelled += it },
            )
        }

        val title = s(R.string.subagent_task_list_title)
        compose.onNodeWithText(title).assertExists()
        // 默认是折叠的：明细行不该一上来就占屏（这是这张卡的既定行为，测试要把它钉住）
        compose.onNodeWithText("汇总会议纪要").assertDoesNotExist()

        // 整行可点展开
        compose.onNodeWithText(title).performClick()
        compose.onNodeWithText("汇总会议纪要").assertExists()
        compose.onNodeWithText("把三份记录并成一份").assertExists()

        // 取消按钮点到哪条，回调就该收到哪条的 taskId
        compose.onNodeWithContentDescription(s(R.string.subagent_task_cancel)).performClick()
        assertEquals(listOf("task-1"), cancelled)
    }

    @Test
    fun emptyTaskListRendersNoCard() {
        var cancelled = 0

        compose.setContent {
            SubagentTaskListCard(
                activeThreads = emptyList(),
                pendingTasks = emptyList(),
                onCancel = { cancelled++ },
            )
        }

        compose.onNodeWithText(s(R.string.subagent_task_list_title)).assertDoesNotExist()
        assertTrue("空列表不该有任何取消动作发生", cancelled == 0)
    }
}
