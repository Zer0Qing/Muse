package io.zer0.memory.deep

import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DeepMemory generation id 合同。
 *
 * generation id 必须随 session/revision/fingerprint 变化，保证旧 revision 无法复用。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DeepMemoryGenerationIdTest {

    @Test
    fun `generation id differs when session revision changes`() {
        val provider = io.zer0.memory.fact.FactDbProvider(
            androidx.test.core.app.ApplicationProvider.getApplicationContext(),
        )
        val noopLlm = object : io.zer0.memory.llm.MemoryLlmClient {
            override suspend fun callText(
                systemPrompt: String,
                userContent: String,
                model: io.zer0.ai.core.Model?,
                temperature: Float,
                maxTokens: Int,
                timeoutMs: Long,
            ): String = ""
        }
        val processor = DeepMemoryProcessor(provider, noopLlm)
        val old = processor.buildGenerationId("s", 0, "fp0")
        val newer = processor.buildGenerationId("s", 1, "fp1")

        assertNotEquals(old, newer)
        assertNotEquals("", old)
    }
}
