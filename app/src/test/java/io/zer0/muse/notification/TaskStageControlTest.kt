package io.zer0.muse.notification

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 通知栏「停止」→ 生成链路（对比清单 P2-03）。
 *
 * 分工是刻意的：通知层只发信号 + 收通知，**不直接取消协程** ——
 * 取消必须由持有它的那一层做，否则会出现"通知没了但任务还在跑"。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TaskStageControlTest {

    @Test
    fun `a stop request reaches the listener`() = runTest {
        var received = 0
        val job = launch { TaskStageControl.stopRequests.collect { received++ } }
        yield()

        TaskStageControl.requestStop()
        yield()

        assertEquals(1, received)
        job.cancel()
    }

    @Test
    fun `the stop action is delivered through the receiver`() {
        var received = 0
        val context = ApplicationProvider.getApplicationContext<Context>()
        val receiver = TaskStageActionReceiver()
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).launch {
            TaskStageControl.stopRequests.collect { received++ }
        }
        kotlinx.coroutines.runBlocking { yield() }

        receiver.onReceive(context, Intent(TaskStageActionReceiver.ACTION_STOP))

        assertTrue("停止动作必须能送达: received=$received", received >= 1)
        job.cancel()
    }

    @Test
    fun `an unrelated action is ignored`() {
        var received = 0
        val context = ApplicationProvider.getApplicationContext<Context>()
        val receiver = TaskStageActionReceiver()
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).launch {
            TaskStageControl.stopRequests.collect { received++ }
        }
        kotlinx.coroutines.runBlocking { yield() }

        receiver.onReceive(context, Intent("android.intent.action.SOMETHING_ELSE"))

        assertEquals("别的广播不能触发停止", 0, received)
        job.cancel()
    }

    @Test
    fun `the receiver shares the notification id used by the manager`() {
        // 两个 ID 分叉时，停止按钮会把通知留在原地 —— 用户会以为没停掉
        assertEquals(1005, TaskStageActionReceiver.NOTIF_ID_TASK_STAGE)
    }
}
