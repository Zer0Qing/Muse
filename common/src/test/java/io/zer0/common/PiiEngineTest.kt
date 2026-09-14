package io.zer0.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PiiEngineTest {
    @Test
    fun appEngineRoundTripsAndPreservesOffsets() {
        val input = "手机 13800138000,邮箱 a@b.com"
        val (masked, matches) = PiiEngine.maskApp(input)
        assertFalse(masked.contains("13800138000"))
        assertTrue(matches.any { it.type == PiiEngine.AppType.PHONE })
        assertEquals(input, PiiEngine.unmaskApp(masked, matches))
        assertEquals("13800138000", input.substring(matches.first { it.type == PiiEngine.AppType.PHONE }.start..matches.first { it.type == PiiEngine.AppType.PHONE }.end - 1))
    }

    @Test
    fun memoryEngineRemovesResidualTokensAfterUnmask() {
        val masked = PiiEngine.maskMemory("电话 13800138000")
        val restored = PiiEngine.unmaskMemory("[PHONE_1] [PHONE_2]", masked.map)
        assertTrue(restored.contains("13800138000"))
        assertFalse(restored.contains("[PHONE_2]"))
    }
}
