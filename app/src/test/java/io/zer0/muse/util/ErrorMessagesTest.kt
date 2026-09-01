package io.zer0.muse.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 网络错误分类纯函数测试(职责已从 ChatViewModel 收口到 [ErrorMessages])。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ErrorMessagesTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun classified(msg: String): String =
        ErrorMessages.classifyNetworkError(context, RuntimeException(msg))

    @Test
    fun `unresolvable host maps to network unresolvable`() {
        assertEquals(
            context.getString(R.string.err_chat_network_unresolvable),
            classified("unable to resolve host api.example.com"),
        )
    }

    @Test
    fun `unknownhost maps to network unresolvable`() {
        assertEquals(
            context.getString(R.string.err_chat_network_unresolvable),
            classified("UnknownHostException: api.example.com"),
        )
    }

    @Test
    fun `timeout maps to network timeout`() {
        assertEquals(
            context.getString(R.string.err_chat_network_timeout),
            classified("SocketTimeoutException: timeout"),
        )
    }

    @Test
    fun `401 and 403 map to auth invalid`() {
        assertEquals(context.getString(R.string.err_chat_auth_invalid), classified("HTTP 401 Unauthorized"))
        assertEquals(context.getString(R.string.err_chat_auth_invalid), classified("403 Forbidden"))
    }

    @Test
    fun `429 maps to rate limited`() {
        assertEquals(context.getString(R.string.err_chat_rate_limited), classified("429 Too Many Requests"))
    }

    @Test
    fun `5xx maps to server error`() {
        assertEquals(context.getString(R.string.err_chat_server_error), classified("HTTP 503 Service Unavailable"))
    }

    @Test
    fun `stream eof maps to stream broken`() {
        assertEquals(context.getString(R.string.err_chat_stream_broken), classified("stream closed unexpectedly eof"))
    }

    @Test
    fun `unmatched message falls back to request failed`() {
        val result = classified("some totally unknown failure")
        assertTrue("未匹配消息应回退到通用失败文案", result.isNotEmpty())
    }

    @Test
    fun `ERR prefixed message resolves and is not re-classified`() {
        val err = "ERR_rate_limited"
        assertEquals(
            context.getString(R.string.err_rate_limited),
            ErrorMessages.classifyNetworkError(context, RuntimeException(err)),
        )
    }
}
