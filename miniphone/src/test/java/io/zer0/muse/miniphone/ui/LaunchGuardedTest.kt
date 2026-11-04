package io.zer0.muse.miniphone.ui

import io.zer0.common.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `launchGuarded` 的契约：兜住真错误、**绝不吞取消**、每一条都留痕。
 *
 * 全部用 `runTest` 的标准调度器 + `runCurrent`，不 sleep、不靠时间猜：
 * 这几条都是"立刻可判定"的语义，一旦引入时间依赖就变成偶发噪声。
 * 日志一律按自己的 tag 过滤后再断言 —— `Logger.sink` 是进程级全局，
 * 别的测试类遗留协程也在往里写。
 */
class LaunchGuardedTest {

    private val seen = mutableListOf<Pair<String, String>>()

    @After
    fun tearDown() {
        Logger.sink = null
        seen.clear()
    }

    private fun capture() {
        seen.clear()
        Logger.sink = { _, tag, msg, _ -> seen += tag to msg }
    }

    private fun ourLogs() = seen.filter { it.first == TAG }

    @Test
    fun `真异常被兜住且留下一条能定位的日志`() = runTest {
        capture()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())

        val job = scope.launchGuarded(TAG, "点赞") { throw IllegalStateException("db is busy") }
        job.join()

        val log = ourLogs().singleOrNull()
        assertNotNull("兜住了却不记日志，用户就成了「点了没反应且无从查起」：$seen", log)
        assertTrue("要说明是哪个用户动作：${log!!.second}", log.second.contains("点赞"))
        assertTrue("要带上原因：${log.second}", log.second.contains("db is busy"))
        // 被兜住的异常不该让 job 处于失败/取消态：它就是一次正常结束
        assertTrue("job 应正常完成", job.isCompleted && !job.isCancelled)
    }

    @Test
    fun `成功路径一条日志都不写`() = runTest {
        capture()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        var done = 0

        scope.launchGuarded(TAG, "刷新动态") { done += 1 }.join()

        assertEquals(1, done)
        assertTrue("成功不该产生噪声：${ourLogs()}", ourLogs().isEmpty())
    }

    @Test
    fun `onError 收尾自己失败也要留痕（否则恢复界面这一步又是一次静默）`() = runTest {
        capture()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())

        scope.launchGuarded(
            TAG,
            "发动态",
            onError = { throw IllegalStateException("收起 loading 时也炸了") },
        ) {
            throw IllegalStateException("发布失败")
        }.join()

        val logs = ourLogs()
        assertEquals("应有两条：主失败 + 收尾失败，实际 $logs", 2, logs.size)
        assertTrue("主失败要带原因：${logs[0].second}", logs[0].second.contains("发布失败"))
        assertTrue("第二条要点明是收尾：${logs[1].second}", logs[1].second.contains("收尾"))
    }

    @Test
    fun `块内抛取消异常时不算失败也不写失败日志`() = runTest {
        capture()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())

        val job = scope.launchGuarded(TAG, "刷新动态") {
            throw kotlinx.coroutines.CancellationException("cancelled by owner")
        }
        job.join()

        assertTrue("取消要如实反映在 job 状态上（不能伪装成成功）", job.isCancelled)
        assertTrue(
            "取消不是失败，不该留下误导性的「失败（已兜住」日志：${ourLogs()}",
            ourLogs().none { it.second.contains("失败（已兜住") },
        )
    }

    @Test
    fun `作用域被取消时任务不继续跑完也不写日志`() = runTest {
        capture()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        var finished = false

        val launched = scope.launchGuarded(TAG, "加载更多") {
            delay(10_000)
            finished = true
        }
        runCurrent()
        scope.cancel()
        launched.join()

        assertFalse("被取消的任务不该跑到完成", finished)
        assertTrue("守卫不该把取消变成正常完成", launched.isCancelled)
        assertTrue("取消不该写失败日志：${ourLogs()}", ourLogs().isEmpty())
    }

    private companion object {
        const val TAG = "LaunchGuardedTestTag"
    }
}
