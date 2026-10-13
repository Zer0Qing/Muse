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

    /**
     * v2.6.1 (#5): 工具调用停止原因识别 —— 用于阻止“reasoning 兜底成正文”。
     *
     * 模型以 tool_calls 停下时,输出属于工具协议;即使正文为空也不能把思考当回复上屏。
     */
    @Test
    fun `recognizes tool call stop reasons`() {
        listOf("tool_calls", "TOOL_CALLS", "function_call", "tool call", "function_calls")
            .forEach { assertTrue("应识别为工具调用停止: $it", ChatStopReason.isToolCallStop(it)) }
    }

    @Test
    fun `does not treat regular completion as tool call stop`() {
        listOf(null, "stop", "length", "max_tokens", "completed", "end_turn")
            .forEach { assertFalse("不应识别为工具调用停止: $it", ChatStopReason.isToolCallStop(it)) }
    }
}
