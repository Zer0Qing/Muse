package io.zer0.muse.tools.channel

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * R-TEST-15: channel_pass 工具定义与执行回调。
 */
class ChannelPassToolTest {

    @Test
    fun `tool definition exposes channel_pass`() = runTest {
        var passedReason: String? = "unset"
        val tool = ChannelPassTool(onPass = { passedReason = it })

        val def = tool.toolDef()
        assertEquals("channel_pass", def.name)
        assertTrue("reason" in def.parameters)

        val result = tool.execute(emptyMap())
        assertEquals("已跳过本轮", result)
        assertNull(passedReason)
    }

    @Test
    fun `execute forwards reason and trims blank`() = runTest {
        var passedReason: String? = "unset"
        val tool = ChannelPassTool(onPass = { passedReason = it })

        tool.execute(mapOf("reason" to "  waiting for others  "))
        assertEquals("waiting for others", passedReason)

        tool.execute(mapOf("reason" to "   "))
        assertNull(passedReason)
    }
}
