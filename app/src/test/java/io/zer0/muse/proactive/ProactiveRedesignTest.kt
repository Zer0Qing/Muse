package io.zer0.muse.proactive

import io.zer0.memory.fact.FactStore
import io.zer0.muse.data.proactive.PendingThoughtEntity
import io.zer0.muse.data.proactive.ProactiveIntensity
import io.zer0.muse.data.proactive.ProactiveRelationEntity
import io.zer0.muse.data.proactive.ProactiveThoughtRepository
import io.zer0.muse.data.proactive.ProactiveTimingEngine
import io.zer0.muse.data.proactive.ThoughtDeriver
import io.zer0.muse.data.proactive.ThoughtKind
import io.zer0.muse.data.proactive.ThoughtStatus
import io.zer0.muse.data.proactive.ThoughtUrgency
import io.zer0.muse.transformer.InternalMarkupSanitizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Calendar

class ProactiveRedesignTest {

    @Test
    fun `extractProactiveThoughts parses xml and bracket tags and strips them from display`() {
        val raw = """
            好的，今晚早点休息。
            <proactive_thought kind="REMINDER" urgency="IMMEDIATE" due_hours="2" weight="0.9">晚上9点提醒提交周报</proactive_thought>
            [proactive_thought kind=CARE urgency=WINDOW weight=0.75]明天问一下体检结果怎么样[/proactive_thought]
            <proactive_thought kind="FOLLOW_UP">   </proactive_thought>
        """.trimIndent()

        val extracted = InternalMarkupSanitizer.extractProactiveThoughts(raw)
        assertEquals(2, extracted.size)
        assertEquals("REMINDER", extracted[0].kind)
        assertEquals("IMMEDIATE", extracted[0].urgency)
        assertEquals(2f, extracted[0].dueHours)
        assertEquals("晚上9点提醒提交周报", extracted[0].seed)

        assertEquals("CARE", extracted[1].kind)
        assertEquals("WINDOW", extracted[1].urgency)
        assertEquals("明天问一下体检结果怎么样", extracted[1].seed)

        val cleaned = InternalMarkupSanitizer.stripForDisplay(raw)
        assertEquals("好的，今晚早点休息。", cleaned)
    }

    @Test
    fun `extractProactiveThoughts handles unclosed tag cleanly`() {
        val raw = "明天见<proactive_thought kind=\"CARE\">还没闭合的内部标签"
        val extracted = InternalMarkupSanitizer.extractProactiveThoughts(raw)
        assertTrue(extracted.isEmpty())
        assertEquals("明天见", InternalMarkupSanitizer.stripForDisplay(raw))
    }

    @Test
    fun `candidate filter prioritizes unsurfaced items enforces 24h cooldown and caps max surface count`() {
        val now = 1_700_000_000_000L
        val hour = 3_600_000L

        val done = entity("done", status = ThoughtStatus.DONE, surfaceCount = 0, lastSurfacedAt = null, weight = 1.0f, createdAt = now)
        val exhausted =
            entity(
                "maxed",
                status = ThoughtStatus.OPEN,
                surfaceCount = ProactiveThoughtRepository.MAX_SURFACE_COUNT,
                lastSurfacedAt = now - 48 * hour,
                weight = 0.99f,
                createdAt = now,
            )
        val cooledDown =
            entity("cool", status = ThoughtStatus.OPEN, surfaceCount = 1, lastSurfacedAt = now - 2 * hour, weight = 0.98f, createdAt = now)
        val resurfaceReady =
            entity(
                "reopen",
                status = ThoughtStatus.OPEN,
                surfaceCount = 1,
                lastSurfacedAt = now - 25 * hour,
                weight = 0.95f,
                createdAt = now,
            )
        val freshLowWeight =
            entity("fresh", status = ThoughtStatus.OPEN, surfaceCount = 0, lastSurfacedAt = null, weight = 0.30f, createdAt = now)

        val selected = ProactiveCandidateFilter.select(
            candidates = listOf(done, exhausted, cooledDown, resurfaceReady, freshLowWeight),
            intensity = ProactiveIntensity.STANDARD,
            now = now,
        )

        assertEquals(listOf("fresh", "reopen"), selected.map { it.id })
    }

    @Test
    fun `reply parser rejects natural language prose refusals as Skip`() {
        val refusal = "好的，我现在不需要发任何主动消息，我会保持安静等待用户。"
        val result = ProactiveReplyParser.parse(refusal)
        assertTrue("Prose refusal must map to Skip, got $result", result is ProactiveReplyParser.Result.Skip)
    }

    @Test
    fun `timing engine marks daily guarantee under default 4h interval and 22h silence`() {
        val now = Calendar.getInstance().apply {
            clear()
            set(2026, Calendar.NOVEMBER, 26, 14, 0, 0)
        }.timeInMillis
        val hourMs = 3_600_000L

        val decision = ProactiveTimingEngine.evaluate(
            ProactiveTimingEngine.Input(
                now = now,
                relation = ProactiveRelationEntity(
                    assistantId = "a1",
                    intimacy = 0.5f,
                    cooldownUntil = 0L,
                    intensity = ProactiveIntensity.STANDARD.name,
                    budgetDate = "2026-11-26",
                    spentToday = 0,
                    earnedToday = 0f,
                ),
                allowedHourStart = 8,
                allowedHourEnd = 22,
                allDayAllowed = false,
                lastUserMessageAt = 0L,
                isGenerating = false,
                lastProactiveSentAt = now - 23 * hourMs,
                lastProactiveAttemptAt = now - 23 * hourMs,
                pacing = ProactiveTimingEngine.Pacing(intervalMinutes = 240),
                dailyGuaranteeMs = ProactiveTimingEngine.DAILY_GUARANTEE_MS,
            ),
        )

        assertEquals(ProactiveTimingEngine.Verdict.OK, decision.verdict)
        assertTrue(decision.dailyGuarantee)
    }

    @Test
    fun `thought deriver derives same day events and english event keywords relative to base now`() {
        val now = Calendar.getInstance().apply {
            clear()
            set(2026, Calendar.NOVEMBER, 26, 9, 0, 0)
        }.timeInMillis
        val todayIso = Instant.ofEpochMilli(now)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .toString()

        val fact = FactStore.Fact(
            id = 42L,
            fact = "Team interview and product demo",
            time = todayIso,
            category = "event",
            importance = 2,
        )

        val derived = ThoughtDeriver.fromFacts(
            facts = listOf(fact),
            assistantId = "a1",
            now = now,
        )

        assertEquals(1, derived.size)
        assertEquals(ThoughtKind.REMINDER.name, derived.first().kind)
        assertEquals(ThoughtUrgency.IMMEDIATE.name, derived.first().urgency)
    }

    private fun entity(id: String, status: ThoughtStatus, surfaceCount: Int, lastSurfacedAt: Long?, weight: Float, createdAt: Long) =
        PendingThoughtEntity(
            id = id,
            assistantId = "a1",
            kind = ThoughtKind.CARE.name,
            seed = "seed-$id",
            status = status.name,
            surfaceCount = surfaceCount,
            lastSurfacedAt = lastSurfacedAt,
            weight = weight,
            createdAt = createdAt,
        )
}
