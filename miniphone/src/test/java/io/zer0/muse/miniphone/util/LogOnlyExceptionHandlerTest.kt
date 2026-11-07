package io.zer0.muse.miniphone.util

import io.zer0.common.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `logOnlyExceptionHandler` 的契约：**记录、不外抛、不拖死同伴，且不参与取消**。
 *
 * 这个处理器是给模块内部长期作用域用的（录音采集、语音播放推进）。
 * 挂之前，这些作用域里的子任务抛出的意料外异常会落到线程默认处理器 —— 用户看到的
 * 是整个应用弹掉；挂之后，只死那一单任务，原因留在日志里。
 *
 * 注意作用域怎么造：**不能把 `runTest` 的 `coroutineContext` 混进来** ——
 * `SupervisorJob() + coroutineContext` 里右边的 Job 会顶掉左边的，
 * 于是"被测的后台作用域"变成测试自己的孩子，异常会被测试框架当成失败收走，
 * 测的就不是处理器而是框架了（第一版就栽在这里）。
 */
class LogOnlyExceptionHandlerTest {

    private val seen = mutableListOf<Pair<String, String>>()
    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun tearDown() {
        Logger.sink = null
        seen.clear()
        scopes.forEach { it.cancel() }
        scopes.clear()
    }

    private fun newScope(tag: String): CoroutineScope {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined + logOnlyExceptionHandler(tag))
        scopes += scope
        return scope
    }

    private fun capture() {
        seen.clear()
        Logger.sink = { _, tag, msg, _ -> seen += tag to msg }
    }

    private fun ourLogs(tag: String) = seen.filter { it.first == tag }

    @Test
    fun `子任务的异常被记录而不让父作用域陪葬`() = runTest {
        val tag = "TestBgScope"
        capture()
        val supervisor = SupervisorJob()
        val scope = CoroutineScope(supervisor + Dispatchers.Unconfined + logOnlyExceptionHandler(tag))
        scopes += scope

        val failing = scope.launch { throw IllegalStateException("采集线程炸了") }
        failing.join()

        val log = ourLogs(tag).singleOrNull()
        assertNotNull("异常没被记录就等于换了个地方静默：$seen", log)
        assertTrue("要带上原因：${log!!.second}", log.second.contains("采集线程炸了"))
        assertTrue("要点明没有让应用崩掉：${log.second}", log.second.contains("未让应用崩掉"))

        // 与"没挂处理器"的区别就在这两条断言上：父亲还活着，同伴照样能跑
        assertTrue("父作用域不该因为一个子任务失败而取消", supervisor.isActive)
        var siblingRan = false
        scope.launch { siblingRan = true }.join()
        assertTrue("同伴任务照样能跑", siblingRan)
    }

    @Test
    fun `取消不该走这个处理器`() = runTest {
        val tag = "TestCancelScope"
        capture()
        val scope = newScope(tag)

        val job = scope.launch { delay(60_000) }
        job.cancel()
        job.join()

        assertTrue("任务确实被取消", job.isCancelled)
        assertTrue(
            "取消是正常控制流，不该被当成「没人接的异常」记一条（否则取消越多日志越脏）：${ourLogs(tag)}",
            ourLogs(tag).isEmpty(),
        )
    }

    @Test
    fun `一条异常只记一条日志且带上原文`() = runTest {
        val tag = "TestPayload"
        capture()
        val scope = newScope(tag)

        scope.launch { throw IllegalArgumentException("bad argument 42") }.join()

        val logs = ourLogs(tag)
        assertEquals("只应一条：$logs", 1, logs.size)
        assertTrue("原文要完整，别截掉关键信息：${logs[0].second}", logs[0].second.contains("bad argument 42"))
    }
}
