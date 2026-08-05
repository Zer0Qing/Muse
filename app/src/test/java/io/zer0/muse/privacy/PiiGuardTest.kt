package io.zer0.muse.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R-TEST-09: PII 脱敏与还原回归测试。
 *
 * 覆盖手机号(11 位/带空格)、邮箱、身份证(15/18 位)的 mask/unmask 往返。
 */
class PiiGuardTest {

    @Test
    fun maskAndUnmaskRoundTripForPhoneEmailAndIdCard() {
        val cases = listOf(
            "13800138000",
            "138 0013 8000",
            "zhang@example.com",
            "11010119900307751X",
            "110101900307751",
        )
        for (input in cases) {
            val (masked, matches) = PiiGuard.mask(input)
            assertFalse("masked=$masked input=$input", masked.contains(input))
            assertTrue("input=$input matches=${matches.size}", matches.isNotEmpty())
            assertEquals("还原应与原文一致 input=$input", input, PiiGuard.unmask(masked, matches))
        }
    }

    @Test
    fun maskAndUnmaskKeepSurroundingText() {
        val input = "手机 13800138000,邮箱 a@b.com"
        val (masked, matches) = PiiGuard.mask(input)
        assertTrue(masked.contains("手机"))
        assertTrue(masked.contains("邮箱"))
        assertFalse(masked.contains("13800138000"))
        assertEquals(input, PiiGuard.unmask(masked, matches))
    }
}
