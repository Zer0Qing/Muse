package io.zer0.memory.pii

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PiiGuardTest {

    @Test
    fun scrubMasksChineseNameAndAddress() {
        val result = PiiGuard.scrub("我叫张明，住成都市武侯区")
        assertFalse(result.cleaned.contains("张明"))
        assertFalse(result.cleaned.contains("成都市武侯区"))
        assertTrue(result.cleaned.contains("[REDACTED]"))
        assertTrue("name" in result.detected)
        assertTrue("detected=${result.detected} cleaned=${result.cleaned}", "address" in result.detected)
    }

    @Test
    fun scrubKeepsWhitelistedCommonWords() {
        val result = PiiGuard.scrub("我需要明白这个问题")
        assertTrue(result.cleaned.contains("明白"))
        assertTrue(result.cleaned.contains("问题"))
    }

    @Test
    fun scrubMasksPhoneEmailAndIdCardTableDriven() {
        val cases = listOf(
            PiiCase("联系 13800138000", listOf("13800138000"), listOf("phone")),
            PiiCase("联系 138 0013 8000", listOf("138 0013 8000"), listOf("phone")),
            PiiCase("邮箱 zhang@example.com", listOf("zhang@example.com"), listOf("email")),
            PiiCase("身份证 11010119900307751X", listOf("11010119900307751X"), listOf("id_card")),
            PiiCase("身份证 110101900307751", listOf("110101900307751"), listOf("id_card")),
        )
        for (case in cases) {
            val result = PiiGuard.scrub(case.input)
            assertTrue("input=${case.input} detected=${result.detected}", case.expectedTypes.all { it in result.detected })
            for (secret in case.secrets) {
                assertFalse("input=${case.input} cleaned=${result.cleaned}", result.cleaned.contains(secret))
            }
            assertTrue("input=${case.input} cleaned=${result.cleaned}", result.cleaned.contains("[REDACTED]"))
        }
    }

    private data class PiiCase(
        val input: String,
        val secrets: List<String>,
        val expectedTypes: List<String>,
    )
}
