package io.zer0.muse.data.agentbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSuggestionEngineTest {

    @Test
    fun `returns at most three suggestions and prioritizes actionable work`() {
        val suggestions = AgentSuggestionEngine.generate(
            AgentSuggestionInput(
                hourOfDay = 9,
                activeTaskCount = 2,
                pendingApprovalCount = 1,
                enabledRoutineCount = 3,
                todayCalendarCount = 1,
            ),
        )

        assertEquals(3, suggestions.size)
        assertEquals(
            listOf(
                AgentSuggestionKind.PENDING_APPROVAL,
                AgentSuggestionKind.ACTIVE_TASK,
                AgentSuggestionKind.CALENDAR,
            ),
            suggestions.map { it.kind },
        )
        assertEquals(1, suggestions.first().titleCount)
    }

    @Test
    fun `falls back to the current time-of-day prompt when there is no work signal`() {
        val morning = AgentSuggestionEngine.generate(AgentSuggestionInput(hourOfDay = 8))
        val evening = AgentSuggestionEngine.generate(AgentSuggestionInput(hourOfDay = 20))
        val night = AgentSuggestionEngine.generate(AgentSuggestionInput(hourOfDay = 23))

        assertEquals(listOf(AgentSuggestionKind.MORNING_BRIEF), morning.map { it.kind })
        assertEquals(listOf(AgentSuggestionKind.EVENING_REVIEW), evening.map { it.kind })
        assertTrue(night.isEmpty())
    }

    @Test
    fun `combines calendar and reminder counts into one signal`() {
        val suggestions = AgentSuggestionEngine.generate(
            AgentSuggestionInput(
                hourOfDay = 14,
                todayCalendarCount = 2,
                pendingReminderCount = 1,
            ),
        )

        val calendar = suggestions.first { it.kind == AgentSuggestionKind.CALENDAR }
        assertEquals(3, calendar.titleCount)
    }
}
