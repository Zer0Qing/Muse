package io.zer0.ai.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Provider 停止原因归一化测试。 */
class ChatStopReasonTest {

    @Test
    fun `recognizes length limited reasons across providers`() {
        listOf("length", "max_tokens", "MAX_TOKENS", "max_output_tokens", "token_limit_exceeded")
            .forEach { assertTrue("应识别长度终止原因: $it", ChatStopReason.isLengthLimited(it)) }
    }

    @Test
    fun `does not classify natural completion or tool calls as truncation`() {
        listOf(null, "stop", "end_turn", "completed", "tool_calls", "SAFETY")
            .forEach { assertFalse("不应识别为长度终止原因: $it", ChatStopReason.isLengthLimited(it)) }
    }
}
