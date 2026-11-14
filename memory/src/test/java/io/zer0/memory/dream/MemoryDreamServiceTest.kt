package io.zer0.memory.dream

import io.zer0.memory.runtime.MemoryScopeKey
import io.zer0.memory.runtime.MemoryWriteGate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryDreamServiceTest {

    private val scope = MemoryScopeKey(assistantId = "default", scope = "main", spaceId = "default")

    @Test
    fun `fingerprint changes when content changes`() {
        val a = fingerprintCompiled(mapOf("facts" to "A"))
        val b = fingerprintCompiled(mapOf("facts" to "B"))
        assertNotEquals(a, b)
    }

    @Test
    fun `start publishes a revision and restore finds it`() = runTest {
        val service = DefaultMemoryDreamService()
        val result = service.start(
            scope,
            DreamTrigger.MANUAL,
            mapOf(
                "facts" to "- 用户喜欢猫",
                "today" to "今天",
                "week" to "本周",
                "longterm" to "长期",
            ),
        )
        assertTrue(result is DreamStartResult.Started)
        val runId = (result as DreamStartResult.Started).runId

        val restore = service.restore(scope, runId)
        assertTrue(restore is DreamRestoreResult.Restored)
        assertFalse(service.status(scope).running)
    }

    @Test
    fun `publish rejects next sections empty`() {
        val state = MemoryDreamState()
        state.start("run")
        val accepted = state.publish(
            MemoryDreamRevision(
                revisionId = "bad",
                trigger = DreamTrigger.MANUAL,
                beforeFingerprint = fingerprintCompiled(mapOf("facts" to "A")),
                before = mapOf("facts" to "A"),
                nextSections = emptyMap(),
                publishedAt = "now",
            ),
        )
        assertFalse(accepted)
    }

    @Test
    fun `state rejects second concurrent run`() {
        val state = MemoryDreamState()
        assertTrue(state.start("run1") is DreamStartResult.Started)
        assertTrue(state.start("run2") is DreamStartResult.Rejected)
    }

    @Test
    fun `hash fence rejects changed snapshot and keeps previous revision`() = runTest {
        var latest = mapOf("facts" to "A")
        val service = DefaultMemoryDreamService(snapshotReader = { latest })
        val first = service.start(scope, DreamTrigger.MANUAL, latest)
        assertTrue(first is DreamStartResult.Started)
        val firstId = (first as DreamStartResult.Started).runId
        latest = mapOf("facts" to "B")
        val rejected = service.start(scope, DreamTrigger.MANUAL, mapOf("facts" to "A"))
        assertTrue(rejected is DreamStartResult.Rejected)
        assertTrue(service.restore(scope, firstId) is DreamRestoreResult.Restored)
    }

    @Test
    fun `failed restore does not change active revision`() = runTest {
        val service = DefaultMemoryDreamService()
        val result = service.start(scope, DreamTrigger.MANUAL, mapOf("facts" to "A")) as DreamStartResult.Started
        assertTrue(service.restore(scope, "missing") is DreamRestoreResult.Rejected)
        assertTrue(service.restore(scope, result.runId) is DreamRestoreResult.Restored)
        assertEquals(result.runId, service.status(scope).lastRevisionId)
    }

    @Test
    fun `write gate rejects dream start and restore`() = runTest {
        val gate = object : MemoryWriteGate {
            override fun isBlocked(scope: MemoryScopeKey) = true
            override suspend fun <T> withWritesPaused(scope: MemoryScopeKey, block: suspend () -> T): T = block()
        }
        val service = DefaultMemoryDreamService(writeGate = gate)
        assertTrue(service.start(scope, DreamTrigger.MANUAL, mapOf("facts" to "A")) is DreamStartResult.Rejected)
        assertTrue(service.restore(scope, "missing") is DreamRestoreResult.Rejected)
    }
}
