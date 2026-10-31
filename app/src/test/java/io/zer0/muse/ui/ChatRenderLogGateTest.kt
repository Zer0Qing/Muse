package io.zer0.muse.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「非渲染消息」诊断日志的去重。
 *
 * 流式输出时可见列表一秒重算好几次,同值重复打日志会把 6 小时的调试日志冲成噪音;
 * 但这条日志本身是查「某条消息为什么不显示」的唯一入口,所以只去重、不删除。
 */
class ChatRenderLogGateTest {

    @Test
    fun `same hidden and total only logs once`() {
        val gate = ChatRenderLogGate()
        assertTrue(gate.shouldLog(hidden = 11, total = 87))
        assertFalse(gate.shouldLog(hidden = 11, total = 87))
        assertFalse(gate.shouldLog(hidden = 11, total = 87))
    }

    @Test
    fun `changed hidden or total logs again`() {
        val gate = ChatRenderLogGate()
        assertTrue(gate.shouldLog(hidden = 11, total = 87))
        assertTrue(gate.shouldLog(hidden = 12, total = 88))
        assertTrue(gate.shouldLog(hidden = 12, total = 89))
        assertFalse(gate.shouldLog(hidden = 12, total = 89))
    }

    @Test
    fun `no hidden messages never logs and forgets the last value`() {
        val gate = ChatRenderLogGate()
        assertTrue(gate.shouldLog(hidden = 11, total = 87))
        assertFalse(gate.shouldLog(hidden = 0, total = 87))
        // 中间归零过,同一对数字再出现算新事件
        assertTrue(gate.shouldLog(hidden = 11, total = 87))
    }
}
