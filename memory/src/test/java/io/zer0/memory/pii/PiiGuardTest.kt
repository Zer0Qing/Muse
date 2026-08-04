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
}
