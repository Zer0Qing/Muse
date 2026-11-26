package io.zer0.muse.data.agentbot

import androidx.work.NetworkType
import io.zer0.memory.fact.FactStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AgentDreamWorkerTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    @Test
    fun `night key keeps after-midnight runs in the previous night`() {
        val lateEvening = Instant.parse("2026-11-25T23:40:00+08:00").toEpochMilli()
        val earlyMorning = Instant.parse("2026-11-26T02:10:00+08:00").toEpochMilli()
        val nextEvening = Instant.parse("2026-11-26T22:00:00+08:00").toEpochMilli()

        assertEquals("2026-11-25", AgentDreamNightGate.nightKey(lateEvening, zone))
        assertEquals("2026-11-25", AgentDreamNightGate.nightKey(earlyMorning, zone))
        assertEquals("2026-11-26", AgentDreamNightGate.nightKey(nextEvening, zone))
    }

    @Test
    fun `nightly work requires charging unmetered network and device idle`() {
        val constraints = AgentDreamScheduler.nightlyConstraints()

        assertTrue(constraints.requiresCharging())
        assertTrue(constraints.requiresDeviceIdle())
        assertEquals(NetworkType.UNMETERED, constraints.requiredNetworkType)
    }

    @Test
    fun `next night delay points at the next two am`() {
        val lateEvening = Instant.parse("2026-11-25T23:30:00+08:00").toEpochMilli()
        val afterMidnight = Instant.parse("2026-11-26T02:30:00+08:00").toEpochMilli()

        assertEquals((2L * 60L + 30L) * 60L * 1000L, AgentDreamScheduler.millisUntilNextNight(lateEvening, zone))
        assertEquals(23L * 60L * 60L * 1000L + 30L * 60L * 1000L, AgentDreamScheduler.millisUntilNextNight(afterMidnight, zone))
    }

    @Test
    fun `engine archives temporary facts dedupes preferences and plans next day`() = runTest {
        val memoryStore = FakeMemoryStore(archived = 3, merged = 2)
        val agenda = AgentDreamAgenda(
            calendarEvents = listOf(
                AgentDreamCalendarEvent("评审", 1_000L),
                AgentDreamCalendarEvent("健身", 2_000L),
            ),
            dueWatchers = listOf(AgentDreamWatcherBrief("watcher-1", "价格", 3_000L)),
            openGoals = listOf(
                AgentDreamGoalBrief("goal-1", "论文", 40),
                AgentDreamGoalBrief("goal-2", "跑步", 10),
            ),
        )
        val engine = AgentDreamEngine(
            memoryStore = memoryStore,
            agendaSource = FakeAgendaSource(agenda),
            summaryFormatter = AgentDreamSummaryFormatter { "晨间摘要" },
            clock = { 1_000L },
            zoneId = { zone },
        )

        val report = engine.run()

        assertEquals(3, report.archivedTemporaryCount)
        assertEquals(2, report.mergedPreferenceCount)
        assertEquals("晨间摘要", report.morningSummary)
        assertEquals(
            listOf(AgentDreamSignalKind.CALENDAR, AgentDreamSignalKind.WATCHER, AgentDreamSignalKind.GOAL),
            report.nextDaySignals.map { it.kind },
        )
        assertEquals(listOf(2, 1, 2), report.nextDaySignals.map { it.itemCount })
        assertEquals(0, report.notificationsSent)
        assertTrue(report.hasWork)
    }

    @Test
    fun `archive failure does not block preference merge or next-day planning`() = runTest {
        val engine = AgentDreamEngine(
            memoryStore = FakeMemoryStore(
                archived = 0,
                merged = 4,
                archiveFailure = IllegalStateException("archive failed"),
            ),
            agendaSource = FakeAgendaSource(
                AgentDreamAgenda(openGoals = listOf(AgentDreamGoalBrief("goal-1", "论文", 40))),
            ),
            summaryFormatter = AgentDreamSummaryFormatter { "摘要" },
            clock = { 1_000L },
            zoneId = { zone },
        )

        val report = engine.run()

        assertEquals(0, report.archivedTemporaryCount)
        assertEquals(4, report.mergedPreferenceCount)
        assertTrue(report.warnings.contains("archive"))
        assertEquals(listOf(AgentDreamSignalKind.GOAL), report.nextDaySignals.map { it.kind })
    }

    @Test
    fun `empty agenda produces no summary and no suggestions`() = runTest {
        val engine = AgentDreamEngine(
            memoryStore = FakeMemoryStore(),
            agendaSource = FakeAgendaSource(AgentDreamAgenda()),
            summaryFormatter = AgentDreamSummaryFormatter { "不应出现" },
            clock = { 1_000L },
            zoneId = { zone },
        )

        val report = engine.run()

        assertEquals("", report.morningSummary)
        assertTrue(report.nextDaySignals.isEmpty())
        assertFalse(report.hasWork)
    }

    @Test
    fun `preference rules accept preference categories and tags`() {
        assertTrue(AgentDreamPreferenceRules.isLongTermPreference("preference", emptyList()))
        assertTrue(AgentDreamPreferenceRules.isLongTermPreference("habit", emptyList()))
        assertTrue(AgentDreamPreferenceRules.isLongTermPreference("general", listOf("偏好")))
        assertFalse(AgentDreamPreferenceRules.isLongTermPreference("event", listOf("日程")))
    }

    @Test
    fun `only text equivalent preference facts can merge`() {
        val facts = listOf(
            FactStore.Fact(id = 1L, fact = "喜欢深色主题", category = "preference"),
            FactStore.Fact(id = 2L, fact = " 喜欢 深色主题。", category = "preference"),
            // 多了一个独有条件：相似但不等价，合并会丢掉「字号调大」。
            FactStore.Fact(id = 3L, fact = "喜欢深色主题，字号调大", category = "preference"),
        )

        val clusters = AgentDreamPreferenceRules.equivalentClusters(facts).map { cluster ->
            cluster.map { it.id }
        }

        assertEquals(listOf(listOf(1L, 2L)), clusters)
    }

    @Test
    fun `archive purge removes only the target assistant rows`() {
        val directory = Files.createTempDirectory("dream-archive").toFile()
        val store = AgentDreamArchiveStore(directory)
        try {
            assertTrue(store.append(listOf(archiveRow("a1", 1L), archiveRow("a2", 2L))))

            assertEquals(1, store.purgeAssistant("a1"))

            val text = File(directory, "temporary-facts.jsonl").readText()
            assertFalse(text.contains("\"assistantId\":\"a1\""))
            assertTrue(text.contains("\"assistantId\":\"a2\""))
            assertEquals(1, store.clear())
            assertFalse(File(directory, "temporary-facts.jsonl").exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `dream signals fill the first three suggestion slots`() {
        val suggestions = AgentSuggestionEngine.generate(
            AgentSuggestionInput(
                hourOfDay = 9,
                dreamNextDaySignals = listOf(
                    AgentDreamNextDaySignal(AgentDreamSignalKind.GOAL, 2, 94),
                    AgentDreamNextDaySignal(AgentDreamSignalKind.CALENDAR, 3, 96),
                    AgentDreamNextDaySignal(AgentDreamSignalKind.WATCHER, 1, 95),
                ),
            ),
        )

        assertEquals(3, suggestions.size)
        assertEquals(
            listOf(AgentSuggestionKind.CALENDAR, AgentSuggestionKind.ROUTINE, AgentSuggestionKind.ACTIVE_TASK),
            suggestions.map { it.kind },
        )
        assertEquals(listOf("dream_calendar", "dream_watcher", "dream_goal"), suggestions.map { it.id })
        assertTrue(suggestions.none { it.id == "morning_brief" })
    }

    @Test
    fun `report can feed the suggestion engine directly`() {
        val report = AgentDreamReport(
            nextDaySignals = listOf(
                AgentDreamNextDaySignal(AgentDreamSignalKind.CALENDAR, 2, 96),
            ),
        )

        val suggestions = AgentSuggestionEngine.generate(AgentSuggestionInput(hourOfDay = 20), report)

        assertEquals("dream_calendar", suggestions.first().id)
        assertEquals(2, suggestions.first().titleCount)
    }

    @Test
    fun `zero-count dream signals are ignored`() {
        val suggestions = AgentSuggestionEngine.generate(
            AgentSuggestionInput(
                hourOfDay = 9,
                dreamNextDaySignals = listOf(
                    AgentDreamNextDaySignal(AgentDreamSignalKind.CALENDAR, 0, 96),
                ),
            ),
        )

        assertEquals(listOf("morning_brief"), suggestions.map { it.id })
    }
}

private fun archiveRow(assistantId: String, factId: Long) = AgentDreamArchiveRow(
    archivedAt = "2026-11-26T02:00:00Z",
    assistantId = assistantId,
    factId = factId,
    content = "临时事实 $factId",
    category = "event",
    tagsJson = "[]",
    expiresAt = "2026-11-25T00:00:00Z",
    scope = "main",
    spaceId = "default",
)

private class FakeMemoryStore(
    private val archived: Int = 0,
    private val merged: Int = 0,
    private val archiveFailure: Exception? = null,
) : AgentDreamMemoryStore {

    override suspend fun archiveExpiredTemporaryFacts(now: Instant): Int {
        val failure = archiveFailure
        if (failure != null) throw failure
        return archived
    }

    override suspend fun dedupeLongTermPreferences(): Int = merged
}

private class FakeAgendaSource(
    private val agenda: AgentDreamAgenda,
    private val failure: Exception? = null,
) : AgentDreamAgendaSource {

    override suspend fun loadTomorrow(now: Instant, zoneId: ZoneId): AgentDreamAgenda {
        val error = failure
        if (error != null) throw error
        return agenda
    }
}
