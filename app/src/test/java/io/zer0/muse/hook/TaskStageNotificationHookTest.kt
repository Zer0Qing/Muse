package io.zer0.muse.hook

import io.mockk.mockk
import io.mockk.verify
import io.zer0.muse.notification.MuseNotificationManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 工具阶段的系统通知投影（对比清单 P2-03）。
 *
 * 要守住两件事：**开始时说清在做什么**、**结束时一定清掉**。
 * 后者尤其重要 —— 留一条常驻的"正在执行"通知，用户会以为任务卡住了。
 */
// 需要 Robolectric：通知管理器的默认参数会求值 MuseNotificationTarget（Android 类）
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TaskStageNotificationHookTest {

    private lateinit var notificationManager: MuseNotificationManager
    private lateinit var hook: TaskStageNotificationHook

    @Before
    fun setUp() {
        notificationManager = mockk(relaxed = true)
        hook = TaskStageNotificationHook(notificationManager)
    }

    @Test
    fun `starting a tool publishes its name to the notification`() = runBlocking {
        val action = hook.onToolCallRequested("screen_read", emptyMap())

        assertEquals("投影 hook 不能改变工具调用本身", ToolCallAction.Allow, action)
        verify(exactly = 1) {
            notificationManager.updateTaskStage(match { it != null && it.contains("screen_read") })
        }
    }

    @Test
    fun `finishing a tool clears the notification`() = runBlocking {
        hook.onToolExecutionResult(
            ToolExecutionResult(toolName = "screen_read", success = true, output = "ok", durationMs = 12L),
        )

        verify(exactly = 1) { notificationManager.updateTaskStage(null) }
    }

    @Test
    fun `the hook runs late so blocked calls are not announced`() {
        assertTrue("优先级要低，排在拦截类 hook 之后: ${hook.priority}", hook.priority <= 10)
    }

    @Test
    fun `the hook has a stable id so it can be deduplicated in the registry`() {
        assertEquals("task_stage_notification", hook.id)
        assertTrue(hook.enabled)
    }

    @Test
    fun `waiting on approval must stop claiming the tool is running`() = runBlocking {
        // 缺陷原样：onToolCallRequested 在审批之前就触发，等待期间通知栏一直挂着「正在执行：X」，
        // 而真正卡住的原因是没人按批准 —— 用户切出应用看到的是错话。
        hook.onToolCallRequested("shell", emptyMap())

        hook.onToolApprovalRequested("shell")

        verify(exactly = 1) {
            notificationManager.updateTaskStage(match { text -> text != null && text.contains("等你批准") && text.contains("shell") })
        }
        // 挂起之后不该再有新的「正在执行」写入（第一次是 onToolCallRequested 那条，已被改口覆盖）
        verify(exactly = 1) {
            notificationManager.updateTaskStage(match { text -> text != null && text.startsWith("正在执行") })
        }
    }

    @Test
    fun `approved resumes the running line`() = runBlocking {
        hook.onToolApprovalRequested("shell")
        hook.onToolPermissionChecked("shell", approved = true)

        verify(exactly = 1) {
            notificationManager.updateTaskStage(match { text -> text != null && text.contains("正在执行") && text.contains("shell") })
        }
    }

    @Test
    fun `denied or timed out clears the notification instead of lying`() = runBlocking {
        // 没批准（含 30 秒超时）这件事就不会发生，继续挂着"正在执行"比不通知更坏
        hook.onToolPermissionChecked("shell", approved = false)

        verify(exactly = 1) { notificationManager.updateTaskStage(null) }
    }
}
