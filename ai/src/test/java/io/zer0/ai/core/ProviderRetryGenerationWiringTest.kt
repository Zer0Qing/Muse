package io.zer0.ai.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class ProviderRetryGenerationWiringTest {

    @Test
    fun `SSE providers guard callbacks with a connection generation`() {
        val openai = read("src/main/java/io/zer0/ai/openai/OpenAIProvider.kt", "ai/src/main/java/io/zer0/ai/openai/OpenAIProvider.kt")
        val anthropic =
            read("src/main/java/io/zer0/ai/anthropic/AnthropicProvider.kt", "ai/src/main/java/io/zer0/ai/anthropic/AnthropicProvider.kt")

        assertTrue(openai.contains("connectionGeneration"))
        assertTrue(openai.contains("myGeneration != connectionGeneration.get()"))
        assertTrue(anthropic.contains("connectionGeneration"))
        assertTrue(anthropic.contains("myGeneration != connectionGeneration.get()"))
    }

    private fun read(vararg paths: String): String = paths.map(Path::of)
        .firstOrNull(Files::exists)
        ?.let { Files.newBufferedReader(it).use { reader -> reader.readText() } }
        ?: error("source not found")
}
