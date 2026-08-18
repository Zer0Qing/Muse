package io.zer0.muse.mcp

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * SSE 断开保护测试。
 *
 * 重点覆盖真实崩溃路径:监听器关闭 endpoint channel 后,等待方应得到 null,
 * 而不是抛 ClosedReceiveChannelException。
 */
class McpClientSseGuardTest {

    @Test
    fun `closed endpoint channel returns null`() = runTest {
        val channel = Channel<String>(Channel.CONFLATED)
        channel.close()

        assertNull(awaitSseEndpoint(channel, timeoutMs = 1_000L))
    }

    @Test
    fun `endpoint event is returned`() = runTest {
        val channel = Channel<String>(Channel.CONFLATED)
        channel.trySend("endpoint")

        assertEquals("endpoint", awaitSseEndpoint(channel, timeoutMs = 1_000L))
        channel.close()
    }
}
