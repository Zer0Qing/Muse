package io.zer0.muse.tools

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * R-TEST-18: CoverGenerator 空 LLM 输出降级为固定绘图指令。
 */
class CoverGeneratorFallbackTest {

    @Test
    fun `blank output falls back to fixed directive`() {
        val prompt = CoverGenerator.resolveCoverPrompt("   ", "我的文档")
        assertEquals("Minimal modern banner cover for: 我的文档, no text, 16:9 aspect ratio", prompt)
    }

    @Test
    fun `non blank output is trimmed and kept`() {
        assertEquals("a vivid banner", CoverGenerator.resolveCoverPrompt("  a vivid banner  ", "我的文档"))
    }
}
