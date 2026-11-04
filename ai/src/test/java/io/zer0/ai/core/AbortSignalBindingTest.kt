package io.zer0.ai.core

import io.zer0.common.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `abortedOnCancel` 的语义边界。
 *
 * 这个信号存在的意义是"让 Provider 里那几十处 `aborted` 判定有真值可判"，
 * 所以**假阳性同样是 bug**：正常收完或上游报错都置位的话，
 * Provider 会以为用户停了而不再重连，那比恒 false 更糟。
 * 因此正反两面都钉。
 */
class AbortSignalBindingTest {

    @After
    fun tearDown() {
        Logger.sink = null
    }

    @Test
    fun `下游取消时信号置位`() = runTest {
        val signal = AbortSignal()
        val job = launch {
            flow<Any> {
                emit("first")
                // 发出一个事件后长期挂住：模拟"服务端还在吐、用户点了停止"
                delay(10_000)
            }.abortedOnCancel(signal).collect { }
        }
        // 关键：只推进到**挂起点**（runCurrent），不要用 advanceUntilIdle ——
        // 后者会把 delay(10_000) 直接跑完，流就变成"正常结束"，取消根本没发生过。
        // （我第一次就是这么写的，探针测出 cause=null、job 未取消。）
        testScheduler.runCurrent()
        assertTrue("前置条件：协程应仍在挂起中", job.isActive)
        job.cancelAndJoinSelf()
        testScheduler.advanceUntilIdle()

        assertTrue("取消之后信号必须置位，否则 Provider 的 aborted 分支仍然是死的", signal.aborted)
    }

    @Test
    fun `正常收完不算中止`() {
        runTest {
            val signal = AbortSignal()
            val seen = mutableListOf<Int>()
            flowOf(1, 2, 3).abortedOnCancel(signal).collect { seen += it }
            assertTrue("事件应当照常透出：$seen", seen == listOf(1, 2, 3))
            assertFalse("正常结束不能置位，否则 Provider 会误以为用户停了而不再重连", signal.aborted)
        }
    }

    @Test
    fun `上游抛错不算中止`() = runTest {
        val signal = AbortSignal()
        val boom = IllegalStateException("上游炸了")
        val caught = runCatching {
            flow<Int> {
                emit(1)
                throw boom
            }.abortedOnCancel(signal).toListCollect()
        }.exceptionOrNull()
        assertTrue("异常要原样传下去：$caught", caught === boom)
        assertFalse("这是错误，不是用户停止", signal.aborted)
    }

    @Test
    fun `重复取消不会把状态改回去`() = runTest {
        val signal = AbortSignal()
        val job = launch {
            flow<Any> {
                emit("x")
                delay(10_000)
            }.abortedOnCancel(signal).collect { }
        }
        testScheduler.runCurrent()
        job.cancel()
        testScheduler.advanceUntilIdle()
        job.cancel() // 再取消一次也不该翻回 false
        assertTrue(signal.aborted)
    }

    private suspend fun <T> kotlinx.coroutines.flow.Flow<T>.toListCollect(): List<T> {
        val out = ArrayList<T>()
        collect { out += it }
        return out
    }

    private suspend fun Job.cancelAndJoinSelf() {
        cancel(CancellationException("测试模拟用户停止"))
    }
}
