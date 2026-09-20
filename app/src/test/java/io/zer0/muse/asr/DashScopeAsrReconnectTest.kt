package io.zer0.muse.asr

import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.WebSocket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3: DashScope ASR 断线重连定向测试。
 *
 * 覆盖:
 *  - 指数退避序列与上限(DashScopeReconnectPolicy);
 *  - 断线动作判定(忽略终态/停止中/dispose,Listening 重连,耗尽降级);
 *  - DashScopeSession 断线通知语义(onFailure/onClosing 通知,onClosed 不通知);
 *  - 退避等待不吞 CancellationException(取消后协程真正结束);
 *  - 重连耗尽后的用户可见降级文案。
 *
 * 运行: ./gradlew :app:testDebugUnitTest --tests "*DashScopeAsrReconnectTest*"
 */
class DashScopeAsrReconnectTest {

    private val json = Json { ignoreUnknownKeys = true }

    // ── 退避策略 ─────────────────────────────────────────────────────────

    @Test
    fun `backoff doubles exponentially and caps at max delay`() {
        assertEquals(DashScopeReconnectPolicy.BASE_DELAY_MS, DashScopeReconnectPolicy.delayForAttempt(0))
        assertEquals(1_000L, DashScopeReconnectPolicy.delayForAttempt(1))
        assertEquals(2_000L, DashScopeReconnectPolicy.delayForAttempt(2))
        assertEquals(4_000L, DashScopeReconnectPolicy.delayForAttempt(3))
        assertEquals(8_000L, DashScopeReconnectPolicy.delayForAttempt(4))
        // 超过上限后封顶,且大 attempt 不因移位溢出变负
        assertEquals(DashScopeReconnectPolicy.MAX_DELAY_MS, DashScopeReconnectPolicy.delayForAttempt(10))
        assertTrue(DashScopeReconnectPolicy.delayForAttempt(Int.MAX_VALUE) > 0)
        // 负数/非法 attempt 回退到首次退避
        assertEquals(DashScopeReconnectPolicy.BASE_DELAY_MS, DashScopeReconnectPolicy.delayForAttempt(-3))
    }

    @Test
    fun `exhausted message is user visible degradation with manual retry hint`() {
        val message = DashScopeReconnectPolicy.EXHAUSTED_MESSAGE
        assertTrue(message.contains("重连 3 次"))
        assertTrue(message.contains("已保留"))
        assertTrue(message.contains("重新开始录音"))
    }

    // ── 断线动作判定 ─────────────────────────────────────────────────────

    @Test
    fun `terminal and stopping states never trigger reconnect`() {
        assertEquals(
            AsrDisconnectAction.IGNORE,
            resolveAsrDisconnectAction(ASRStatus.Idle, isDisposed = false, reconnectAttempt = 0),
        )
        assertEquals(
            AsrDisconnectAction.IGNORE,
            resolveAsrDisconnectAction(ASRStatus.Error, isDisposed = false, reconnectAttempt = 0),
        )
        assertEquals(
            AsrDisconnectAction.IGNORE,
            resolveAsrDisconnectAction(ASRStatus.Stopping, isDisposed = false, reconnectAttempt = 0),
        )
        // 首次连接/重连建立中的断开由 establishConnection 自行处理,避免双重计数
        assertEquals(
            AsrDisconnectAction.IGNORE,
            resolveAsrDisconnectAction(ASRStatus.Connecting, isDisposed = false, reconnectAttempt = 0),
        )
    }

    @Test
    fun `listening disconnect reconnects until attempts are exhausted`() {
        assertEquals(
            AsrDisconnectAction.RECONNECT,
            resolveAsrDisconnectAction(ASRStatus.Listening, isDisposed = false, reconnectAttempt = 0),
        )
        assertEquals(
            AsrDisconnectAction.RECONNECT,
            resolveAsrDisconnectAction(ASRStatus.Reconnecting, isDisposed = false, reconnectAttempt = 2),
        )
        assertEquals(
            AsrDisconnectAction.GIVE_UP,
            resolveAsrDisconnectAction(
                ASRStatus.Listening,
                isDisposed = false,
                reconnectAttempt = DashScopeReconnectPolicy.MAX_ATTEMPTS,
            ),
        )
    }

    @Test
    fun `disposed controller never reconnects`() {
        assertEquals(
            AsrDisconnectAction.IGNORE,
            resolveAsrDisconnectAction(ASRStatus.Listening, isDisposed = true, reconnectAttempt = 0),
        )
        assertEquals(
            AsrDisconnectAction.IGNORE,
            resolveAsrDisconnectAction(ASRStatus.Reconnecting, isDisposed = true, reconnectAttempt = 1),
        )
    }

    // ── 会话断线通知 ────────────────────────────────────────────────────

    @Test
    fun `session notifies disconnect on websocket failure`() = runBlocking {
        val session = DashScopeSession("task-1", json) { }
        var notified: String? = null
        session.onDisconnected = { notified = it }

        session.onFailure(mockk(relaxed = true), java.io.IOException("boom"), null)

        assertTrue("onFailure 应携带异常原因", notified?.contains("boom") == true)
        // 通道已关闭:等待事件立即返回 null(重连尝试不会挂死)
        assertNull(session.waitForEvent("task-started", 100))
    }

    @Test
    fun `session notifies disconnect when server initiates close`() {
        val session = DashScopeSession("task-1", json) { }
        var notified: String? = null
        session.onDisconnected = { notified = it }
        val ws = mockk<WebSocket>(relaxed = true)

        session.onClosing(ws, 1001, "going away")

        assertTrue(notified?.contains("1001") == true)
        verify { ws.close(1001, "going away") }
    }

    @Test
    fun `local close does not notify disconnect`() {
        val session = DashScopeSession("task-1", json) { }
        var notified: String? = null
        session.onDisconnected = { notified = it }

        // 本地 close()/cleanupConnection 只触发 onClosed,绝不能自触发重连
        session.onClosed(mockk(relaxed = true), 1000, "session ended")

        assertNull(notified)
    }

    // ── 退避取消语义 ────────────────────────────────────────────────────

    @Test
    fun `backoff wait propagates cancellation without being swallowed`() = runBlocking {
        val job = launch { awaitReconnectBackoff(5) } // 8s,下面主动取消
        delay(50)
        job.cancel()
        job.join()

        assertTrue("取消后协程必须真正结束(退避等待不得吞 CancellationException)", job.isCancelled)
    }
}
