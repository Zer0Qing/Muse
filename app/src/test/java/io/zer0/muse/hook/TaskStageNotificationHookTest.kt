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
}
