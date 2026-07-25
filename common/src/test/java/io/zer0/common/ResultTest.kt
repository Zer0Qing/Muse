package io.zer0.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Result] 通用结果封装的单元测试。
 *
 * 覆盖范围:
 *  - [resultOf] 成功路径与失败路径(含 CancellationException 重抛语义)
 *  - [Result.Success] / [Result.Error] 状态判定([isSuccess] / [isError])
 *  - [getOrNull] 成功/失败取值
 *  - [getOrThrow] 成功取值与失败抛异常
 *  - [onSuccess] / [onError] 回调触发与链式返回
 *  - [map] 转换 Success,Error 透传
 *  - [flatMap] 链式 Success/Error 传递
 */
class ResultTest {

    // ── resultOf{} 成功路径 ──────────────────────────────────────────────

    @Test
    fun `resultOf 成功返回 Success 并携带数据`() {
        val result = resultOf { 42 }
        assertTrue(result is Result.Success)
        assertEquals(42, (result as Result.Success).data)
    }

    @Test
    fun `resultOf 成功路径 isSuccess 为 true isError 为 false`() {
        val result = resultOf { "ok" }
        assertTrue(result.isSuccess)
        assertFalse(result.isError)
    }

    @Test
    fun `resultOf 块返回 null 时仍为 Success`() {
        val result = resultOf<String?> { null }
        assertTrue(result is Result.Success)
        assertNull(result.getOrNull())
    }

    // ── resultOf{} 失败路径 ──────────────────────────────────────────────

    @Test
    fun `resultOf 抛异常时返回 Error 并携带消息与原异常`() {
        val ex = IllegalStateException("boom")
        val result = resultOf<Int> { throw ex }
        assertTrue(result is Result.Error)
        val err = result as Result.Error
        assertEquals("boom", err.message)
        assertSame(ex, err.throwable)
    }

    @Test
    fun `resultOf 抛异常时 message 为空则使用 Unknown error 兜底`() {
        val ex = RuntimeException()
        val result = resultOf<Int> { throw ex }
        assertTrue(result is Result.Error)
        assertEquals("Unknown error", (result as Result.Error).message)
        assertSame(ex, err(result).throwable)
    }

    @Test
    fun `resultOf 抛异常时 isSuccess 为 false isError 为 true`() {
        val result = resultOf<Int> { throw IllegalArgumentException("bad") }
        assertFalse(result.isSuccess)
        assertTrue(result.isError)
    }

    @Test
    fun `resultOf 不吞 CancellationException 而是重抛`() {
        val cex = kotlin.coroutines.cancellation.CancellationException("cancelled")
        assertThrows(kotlin.coroutines.cancellation.CancellationException::class.java) {
            resultOf<Int> { throw cex }
        }
    }

    // ── getOrNull() 成功/失败 ────────────────────────────────────────────

    @Test
    fun `getOrNull 成功时返回数据`() {
        val result: Result<String> = resultOf { "hello" }
        assertEquals("hello", result.getOrNull())
    }

    @Test
    fun `getOrNull 失败时返回 null`() {
        val result: Result<String> = resultOf { throw RuntimeException("err") }
        assertNull(result.getOrNull())
    }

    // ── getOrThrow() 成功/失败 ───────────────────────────────────────────

    @Test
    fun `getOrThrow 成功时返回数据`() {
        val result: Result<Int> = resultOf { 7 }
        assertEquals(7, result.getOrThrow())
    }

    @Test
    fun `getOrThrow 失败且 throwable 非空时抛出原异常`() {
        val ex = IllegalStateException("original")
        val result: Result<Int> = resultOf { throw ex }
        val thrown = assertThrows(IllegalStateException::class.java) {
            result.getOrThrow()
        }
        assertSame(ex, thrown)
    }

    @Test
    fun `getOrThrow 失败且 throwable 为空时抛 IllegalStateException 并携带 message`() {
        // 构造 throwable 为 null 的 Error(直接实例化,不通过 resultOf)
        val result: Result<Int> = Result.Error(message = "no throwable", throwable = null)
        val thrown = assertThrows(IllegalStateException::class.java) {
            result.getOrThrow()
        }
        assertEquals("no throwable", thrown.message)
    }

    // ── onSuccess{} 回调 ────────────────────────────────────────────────

    @Test
    fun `onSuccess 在成功时触发回调并传入数据`() {
        var captured: String? = null
        var callCount = 0
        val result: Result<String> = resultOf { "data" }
        result.onSuccess { value ->
            captured = value
            callCount++
        }
        assertEquals("data", captured)
        assertEquals(1, callCount)
    }

    @Test
    fun `onSuccess 在失败时不触发回调`() {
        var callCount = 0
        val result: Result<String> = resultOf { throw RuntimeException("err") }
        result.onSuccess { callCount++ }
        assertEquals(0, callCount)
    }

    @Test
    fun `onSuccess 返回原 Result 实例便于链式调用`() {
        val result: Result<Int> = resultOf { 1 }
        val returned = result.onSuccess { /* no-op */ }
        assertSame(result, returned)
    }

    // ── onError{} 回调 ──────────────────────────────────────────────────

    @Test
    fun `onError 在失败时触发回调并传入 message 与 throwable`() {
        val ex = IllegalStateException("boom")
        var capturedMsg: String? = null
        var capturedT: Throwable? = null
        var callCount = 0
        val result: Result<Int> = resultOf { throw ex }
        result.onError { msg, t ->
            capturedMsg = msg
            capturedT = t
            callCount++
        }
        assertEquals("boom", capturedMsg)
        assertSame(ex, capturedT)
        assertEquals(1, callCount)
    }

    @Test
    fun `onError 在成功时不触发回调`() {
        var callCount = 0
        val result: Result<Int> = resultOf { 1 }
        result.onError { _, _ -> callCount++ }
        assertEquals(0, callCount)
    }

    @Test
    fun `onError 回调收到 throwable 为 null 的情况`() {
        val result: Result<Int> = Result.Error(message = "msg", throwable = null)
        var receivedT: Throwable? = "sentinel".let { null }
        result.onError { _, t -> receivedT = t }
        assertNull(receivedT)
    }

    @Test
    fun `onError 返回原 Result 实例便于链式调用`() {
        val result: Result<Int> = resultOf { throw RuntimeException("err") }
        val returned = result.onError { _, _ -> /* no-op */ }
        assertSame(result, returned)
    }

    // ── map{} 转换 ──────────────────────────────────────────────────────

    @Test
    fun `map 成功时对数据应用转换`() {
        val result: Result<Int> = resultOf { 5 }
        val mapped = result.map { it * 2 }
        assertTrue(mapped is Result.Success)
        assertEquals(10, mapped.getOrNull())
    }

    @Test
    fun `map 成功时转换类型`() {
        val result: Result<Int> = resultOf { 42 }
        val mapped: Result<String> = result.map { "value=$it" }
        assertEquals("value=42", mapped.getOrNull())
    }

    @Test
    fun `map 失败时透传 Error 实例`() {
        val ex = IllegalStateException("boom")
        val result: Result<Int> = resultOf { throw ex }
        val mapped = result.map { it * 2 }
        assertTrue(mapped is Result.Error)
        val err = mapped as Result.Error
        assertEquals("boom", err.message)
        assertSame(ex, err.throwable)
    }

    @Test
    fun `map 失败时不调用 transform`() {
        var transformCalled = false
        val result: Result<Int> = resultOf { throw RuntimeException("err") }
        result.map { transformCalled = true; it }
        assertFalse(transformCalled)
    }

    // ── flatMap{} 链式 ──────────────────────────────────────────────────

    @Test
    fun `flatMap 成功时把数据交给 transform 产生新 Result`() {
        val result: Result<Int> = resultOf { 3 }
        val flat = result.flatMap<Int> { resultOf { it + 10 } }
        assertTrue(flat is Result.Success)
        assertEquals(13, flat.getOrNull())
    }

    @Test
    fun `flatMap 成功时 transform 可返回 Error 形成失败链`() {
        val result: Result<Int> = resultOf { 3 }
        val flat: Result<String> = result.flatMap {
            Result.Error(message = "downstream failure", throwable = null)
        }
        assertTrue(flat is Result.Error)
        assertEquals("downstream failure", (flat as Result.Error).message)
    }

    @Test
    fun `flatMap 失败时透传原 Error 不调用 transform`() {
        val ex = IllegalStateException("upstream")
        val result: Result<Int> = resultOf { throw ex }
        var transformCalled = false
        val flat: Result<String> = result.flatMap<String> {
            transformCalled = true
            resultOf { "ok" }
        }
        assertTrue(flat is Result.Error)
        assertFalse(transformCalled)
        val err = flat as Result.Error
        assertEquals("upstream", err.message)
        assertSame(ex, err.throwable)
    }

    @Test
    fun `flatMap 链式多步组合保持成功`() {
        val final: Result<Int> = resultOf { 1 }
            .flatMap { resultOf { it + 2 } }
            .flatMap { resultOf { it * 3 } }
        assertEquals(9, final.getOrNull())
    }

    @Test
    fun `flatMap 链式任一步失败则整体失败`() {
        val final: Result<Int> = resultOf { 1 }
            .flatMap<Int> { resultOf<Int> { throw IllegalStateException("step2") } }
            .flatMap { resultOf { it * 3 } }
        assertTrue(final is Result.Error)
        assertEquals("step2", (final as Result.Error).message)
    }

    // ── 综合链式:onSuccess / onError / map / flatMap 组合 ───────────────

    @Test
    fun `综合链式 map 后 onSuccess 收到转换后的值`() {
        var captured: String? = null
        resultOf { 10 }
            .map { it.toString() }
            .onSuccess { captured = it }
        assertEquals("10", captured)
    }

    @Test
    fun `综合链式 flatMap 失败时 onError 收到失败信息`() {
        var capturedMsg: String? = null
        var capturedT: Throwable? = null
        resultOf { 10 }
            .flatMap<Int> { resultOf<Int> { throw IllegalArgumentException("down") } }
            .onError { msg, t ->
                capturedMsg = msg
                capturedT = t
            }
        assertEquals("down", capturedMsg)
        assertNotNull(capturedT)
        assertTrue(capturedT is IllegalArgumentException)
    }

    // ── 辅助函数 ────────────────────────────────────────────────────────

    /** 从 [Result] 中取出 Error 实例(测试辅助,假定调用者已知是 Error)。 */
    private fun <T> err(result: Result<T>): Result.Error =
        (result as? Result.Error) ?: error("预期 Error,实际 $result")
}
