package io.zer0.muse.web

import org.junit.Assert.assertEquals
import org.junit.Test

class WebSearchQueryNormalizerTest {
    @Test
    fun `removes malformed smart quotes and outer ascii quotes`() {
        assertEquals(
            "ornith1.5-35ba3b 模型 OR model",
            WebSearchQueryNormalizer.normalize("\"\"ornith1.5-35ba3b”模型 OR model\"")
        )
    }

    @Test
    fun `keeps a normal query unchanged`() {
        assertEquals("DeepSeek V4 Flash 模型", WebSearchQueryNormalizer.normalize("DeepSeek V4 Flash 模型"))
    }

    @Test
    fun `collapses invisible characters and whitespace`() {
        assertEquals("OpenAI model", WebSearchQueryNormalizer.normalize("  OpenAI\u200B   model  "))
    }

    @Test
    fun `removes unmatched quote`() {
        assertEquals("ornith model", WebSearchQueryNormalizer.normalize("ornith \"model"))
    }
}
