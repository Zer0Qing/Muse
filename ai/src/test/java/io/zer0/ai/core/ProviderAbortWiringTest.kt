package io.zer0.ai.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class ProviderAbortWiringTest {

    @Test
    fun `Anthropic and Gemini streaming providers register and close abort listeners`() {
        val anthropic =
            read(
                "src/main/java/io/zer0/ai/anthropic/AnthropicProvider.kt",
                "ai/src/main/java/io/zer0/ai/anthropic/AnthropicProvider.kt",
            )
        val gemini =
            read(
                "src/main/java/io/zer0/ai/gemini/GeminiProvider.kt",
                "ai/src/main/java/io/zer0/ai/gemini/GeminiProvider.kt",
            )

        assertTrue(anthropic.contains("request.abortSignal.addAbortListener"))
        assertTrue(anthropic.contains("abortListener.close()"))
        assertTrue(gemini.contains("request.abortSignal.addAbortListener"))
        assertTrue(gemini.contains("abortListener.close()"))
    }

    private fun read(vararg paths: String): String = paths.map(Path::of)
        .firstOrNull(Files::exists)
        ?.let { Files.newBufferedReader(it).use { reader -> reader.readText() } }
        ?: error("source not found")
}
