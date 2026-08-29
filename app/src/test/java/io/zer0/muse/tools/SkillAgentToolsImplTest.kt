package io.zer0.muse.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.ui.taskcard.AgentPlan
import io.zer0.muse.ui.taskcard.AgentPlanStep
import io.zer0.muse.ui.taskcard.AgentPlanStepStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SkillAgentToolsImplTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun taskPlan_createsPlanAndUpdateStep() = runBlocking {
        val tools = SkillAgentToolsImpl(context, mockk<AssistantRepository>(relaxed = true), null, null)

        val summary = tools.execTaskPlan(
            mapOf(
                "title" to "调研计划",
                "steps" to """[{"title":"Step A"},{"title":"Step B"}]""",
            ),
        )

        assertTrue(summary.contains("planId"))
        val planId = Regex("planId: (plan-\\d+)").find(summary)?.groupValues?.get(1)
            ?: error("planId missing")
        assertEquals(2, tools.getActivePlans()[planId]?.steps?.size)

        val updated = tools.execUpdatePlanStep(
            mapOf(
                "planId" to planId,
                "stepIndex" to "0",
                "status" to "done",
            ),
        )
        assertTrue(updated.contains("已更新"))
        assertEquals(
            AgentPlanStepStatus.DONE,
            tools.getActivePlans()[planId]?.steps?.first()?.status,
        )
    }

    @Test
    fun restoredPlans_are_isolated_by_session_and_can_be_updated() = runBlocking {
        val tools = SkillAgentToolsImpl(context, mockk<AssistantRepository>(relaxed = true), null, null)
        val sessionA = "session-a"
        val sessionB = "session-b"
        val planA = AgentPlan(
            id = "plan-a",
            title = "A",
            steps = listOf(AgentPlanStep(id = "step-a", title = "A step")),
            sessionId = sessionA,
        )
        val planB = AgentPlan(
            id = "plan-b",
            title = "B",
            steps = listOf(AgentPlanStep(id = "step-b", title = "B step")),
            sessionId = sessionB,
        )

        tools.restoreActivePlans(mapOf(planA.id to planA), sessionA)
        tools.restoreActivePlans(mapOf(planB.id to planB), sessionB)

        assertEquals(setOf("plan-a"), tools.getActivePlans(sessionA).keys)
        assertEquals(setOf("plan-b"), tools.getActivePlans(sessionB).keys)
        assertTrue(tools.execUpdatePlanStep(
            mapOf("planId" to "plan-a", "stepIndex" to "0", "status" to "done"),
            sessionA,
        ).contains("已更新"))
        assertEquals(
            AgentPlanStepStatus.DONE,
            tools.getActivePlans(sessionA).getValue("plan-a").steps.single().status,
        )
        assertEquals(
            AgentPlanStepStatus.PENDING,
            tools.getActivePlans(sessionB).getValue("plan-b").steps.single().status,
        )
        assertFalse(tools.execUpdatePlanStep(
            mapOf("planId" to "plan-a", "stepIndex" to "0", "status" to "done"),
            sessionB,
        ).contains("已更新"))
    }
}
