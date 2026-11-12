package io.zer0.muse.schedule

import android.content.res.Resources
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.zer0.ai.ChatService
import io.zer0.memory.fact.FactEntity
import io.zer0.muse.R
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class GreetingHintGeneratorLocalizationTest {
    @Test
    fun failed_generation_uses_localized_fallback() = runTest {
        val resources = mockk<Resources>()
        every { resources.getString(R.string.greeting_event_exam, *anyVararg()) } returns "examen"
        every { resources.getString(R.string.greeting_tomorrow_event, *anyVararg()) } returns "Mañana: examen"
        every { resources.getString(R.string.greeting_hint_generation_prompt) } returns "Genera un recordatorio en español"
        val service = mockk<ChatService>()
        coEvery {
            service.completeText(messages = any(), temperature = any(), maxTokens = any())
        } throws IllegalStateException("offline")
        val facts = listOf(
            FactEntity(
                id = 1L,
                fact = "考试",
                time = LocalDate.now().plusDays(1).toString(),
                createdAt = "2026-08-07T12:00:00",
            ),
        )

        assertEquals("Mañana: examen", GreetingHintGenerator(service).generate(facts, resources))
    }
}
