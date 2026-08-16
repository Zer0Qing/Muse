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

    // A-20: BANK_CARD 覆盖 16-19 位数字卡号(与 memory 版 CREDIT_CARD 对齐)。

    @Test
    fun maskMasksBankCardOfSixteenToNineteenDigits() {
        val inputs = listOf(
            "卡号 6222021234567890",       // 16 位
            "卡号 62220212345678901",      // 17 位
            "卡号 622202123456789012",     // 18 位
            "卡号 6222021234567890123",    // 19 位
        )
        for (input in inputs) {
            val card = input.removePrefix("卡号 ").trim()
            val (masked, matches) = PiiGuard.mask(input)
            assertFalse(
                "A-20 未遮蔽 BANK_CARD: input=$input masked=$masked",
                masked.contains(card),
            )
            assertTrue(
                "A-20 未检测到 BANK_CARD: input=$input matches=${matches.size}",
                matches.any { it.type == PiiGuard.PiiType.BANK_CARD },
            )
            assertEquals("还原应与原文一致 input=$input", input, PiiGuard.unmask(masked, matches))
        }
    }

    @Test
    fun maskMasksSixteenDigitBankCardBoundaryOnly() {
        // 16 位卡号紧贴在非数字字符间应被遮蔽(边界保护,前后不能再有数字)。
        val input = "卡 6222021234567890 尾"
        val (masked, matches) = PiiGuard.mask(input)
        assertFalse(masked.contains("6222021234567890"))
        assertTrue(matches.any { it.type == PiiGuard.PiiType.BANK_CARD })
        assertEquals(input, PiiGuard.unmask(masked, matches))
    }
}
